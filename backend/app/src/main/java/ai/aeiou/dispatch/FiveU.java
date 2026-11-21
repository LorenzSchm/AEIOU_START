package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
class FiveU {
  private final RestClient client;
  private final RestClient gateway;
  private final MarketMemory memory;
  private Instant retryAt = Instant.EPOCH;

  FiveU(@Value("${dispatch.base-url}") String base, @Value("${dispatch.key}") String key, Ledger ledger) {
    client = client(base, key, 4);
    gateway = client(base, key, 15);
    memory = new MarketMemory(ledger);
  }

  private static RestClient client(String base, String key, int seconds) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(seconds));
    return RestClient.builder()
        .baseUrl(base)
        .requestFactory(factory)
        .defaultHeader("X-API-Key", key)
        .build();
  }

  com.fasterxml.jackson.databind.JsonNode parseRfq(Map<String, Object> payload) {
    return gateway
        .post()
        .uri("/v1/gateway/chat/completions")
        .body(payload)
        .retrieve()
        .body(com.fasterxml.jackson.databind.JsonNode.class);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> get(String uri, Object... variables) {
    return client.get().uri(uri, variables).retrieve().body(Map.class);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> post(String uri, Map<String, Object> payload) {
    return client.post().uri(uri).body(payload).retrieve().body(Map.class);
  }

  byte[] bytes(long mid, long aid) {
    return client
        .get()
        .uri("/v1/mailbox/messages/{m}/attachments/{a}", mid, aid)
        .retrieve()
        .body(byte[].class);
  }

  record Rates(List<Offer> offers, boolean complete, boolean reset, String version) {
    Rates(List<Offer> offers, boolean complete) { this(offers, complete, false, null); }
  }

  private record RateQuery(String airline, String name, BigDecimal weight, BigDecimal billed,
                           MarketMemory.Key key, MarketMemory.Sample sample) {
    BigDecimal estimate() { return sample.estimate(billed); }
  }
  private record LiveRate(Offer offer, String version) {}

  private LiveRate rate(Shipment shipment, RateQuery query, Instant now) {
    var r =
        get(
            "/v1/portal/rates?origin={o}&destination={d}&airline={a}&weight_kg={w}&volume_cbm={v}",
            shipment.origin(),
            shipment.destination(),
            query.airline(),
            query.weight(),
            shipment.volumeCbm());
    var fx =
        switch (Ledger.str(r, "currency")) {
          case "EUR" -> BigDecimal.ONE;
          case "USD" -> new BigDecimal(Ledger.str(r, "fx_eur_per_usd"));
          default -> throw new IllegalArgumentException("Missing FX");
        };
    var expiry = Ledger.instant(r.get("valid_until"));
    var price = new BigDecimal(Ledger.str(r, "total")).multiply(fx);
    if (fx.signum() <= 0 || price.signum() <= 0 || expiry == null || !expiry.isAfter(now))
      throw new IllegalArgumentException("Invalid rate");
    var offer = new Offer(
        Ledger.str(r, "airline"),
        Integer.parseInt(Ledger.str(r, "transit_days")),
        price,
        new BigDecimal(Ledger.str(r, "chargeable_weight_kg")),
        "portal",
        Ledger.str(r, "rate_id"),
        expiry);
    if (offer.billedKg().signum() <= 0) throw new IllegalArgumentException("Invalid billed weight");
    return new LiveRate(offer, Ledger.str(r, "dataset_version"));
  }

  private record QueryPlan(List<RateQuery> ordered, boolean complete) {}
  private record Carrier(String code, String name) {}
  private List<?> lanes = List.of();
  private Instant lanesExpire = Instant.MIN;

  private synchronized List<Carrier> carriers(Shipment shipment, Instant now) {
    if (!now.isBefore(lanesExpire)) {
      var ports = get("/v1/portal/ports");
      if (ports == null || !(ports.get("lanes") instanceof List<?> published))
        throw new IllegalArgumentException("Missing portal lanes");
      lanes = published;
      lanesExpire = now.plusSeconds(30);
    }
    var result = new ArrayList<Carrier>();
    for (var raw : lanes) {
      if (!(raw instanceof Map<?, ?> lane)
          || !shipment.origin().equals(lane.get("origin"))
          || !shipment.destination().equals(lane.get("destination"))) continue;
      if (!(lane.get("airlines") instanceof List<?> airlines))
        throw new IllegalArgumentException("Missing lane airlines");
      for (var airline : airlines) {
        if (!(airline instanceof Map<?, ?> c) || Ledger.str(c, "code").isBlank())
          throw new IllegalArgumentException("Missing airline code");
        result.add(new Carrier(Ledger.str(c, "code"), Ledger.str(c, "name")));
      }
    }
    return result;
  }

  Set<String> portalAirlines(Shipment shipment, Instant now) {
    var names = new HashSet<String>();
    try {
      for (var carrier : carriers(shipment, now))
        if (!carrier.name().isBlank()) names.add(carrier.name());
    } catch (RestClientException | IllegalArgumentException unavailable) {
      // The invitation wave can still use carrier declarations and kit tariffs.
    }
    return names;
  }

  private QueryPlan plan(Shipment shipment, Instant now, Map<MarketMemory.Key, MarketMemory.Sample> history) {
    boolean complete = true;
    var queries = new ArrayList<RateQuery>();
    var higherBreaks = new ArrayList<RateQuery>();
    for (var carrier : carriers(shipment, now)) {
      String code = carrier.code();
      String name = carrier.name();
      var billed = shipment.chargeableKg();
      var breaks = new TreeSet<BigDecimal>();
      try {
        var brackets = get("/v1/portal/brackets?airline={a}", code);
        if (!(brackets.get("breaks_kg") instanceof List<?> published))
          throw new IllegalArgumentException("Missing portal weight brackets");
        for (var kg : published) breaks.add(new BigDecimal(kg.toString()));
      } catch (RestClientException | IllegalArgumentException ex) {
        complete = false;
      }
      if (!breaks.isEmpty()) breaks.add(BigDecimal.ZERO);
      queries.add(query(code, name, shipment.weightKg(), billed, breaks.floor(billed), history));
      var next = breaks.higher(billed);
      if (next != null) higherBreaks.add(query(code, name, next, next, next, history));
    }
    queries.addAll(higherBreaks);
    var predicted = Comparator.comparing(RateQuery::estimate);
    if ("fastest".equals(shipment.preference()))
      predicted = Comparator.comparingDouble((RateQuery q) -> q.sample().days()).thenComparing(predicted);
    var known = queries.stream().filter(q -> q.sample() != null).sorted(predicted).toList();
    var ordered = new ArrayList<RateQuery>();
    if (!known.isEmpty()) ordered.add(known.getFirst());
    queries.stream().filter(q -> q.sample() == null).forEach(ordered::add);
    ordered.addAll(known.stream().skip(1).toList());
    return new QueryPlan(ordered, complete);
  }

  synchronized Rates rates(Shipment shipment, Instant now) {
    return rates(shipment, now, List.of(), null);
  }

  synchronized Rates rates(Shipment shipment, Instant now, List<Offer> verified, String priorVersion) {
    List<Offer> offers = new ArrayList<>();
    if (now.isBefore(retryAt)) return new Rates(offers, false, false, priorVersion);
    boolean complete = true;
    boolean reset = !verified.isEmpty() && (priorVersion == null || priorVersion.isBlank());
    String version = reset ? null : priorVersion;
    var history = memory.recent(shipment, now);
    try {
      var plan = plan(shipment, now, history);
      complete = plan.complete();
      for (var query : plan.ordered()) {
        if (priorVersion != null && !priorVersion.isBlank() && verified.stream().anyMatch(o ->
            o.airline().equals(query.name()) && o.billedKg().compareTo(query.billed()) == 0
                && o.rateId() != null && !o.rateId().isBlank() && o.validUntil() != null
                && o.validUntil().isAfter(now.plusSeconds(120)))) continue;
        try {
          var live = rate(shipment, query, now);
          var offer = live.offer();
          if (version != null && !version.equals(live.version())) {
            memory.forget(shipment);
            // The dataset changed midway. Restart on a single version on the next tick.
            return new Rates(List.of(), false, true, null);
          }
          version = live.version();
          if (!history.isEmpty() && query.sample() != null && !query.sample().version().equals(version)) {
            memory.forget(shipment);
            history.clear();
          }
          if (query.key() != null && offer.billedKg().compareTo(query.billed()) == 0)
            memory.record(shipment, query.key(), offer, version, now);
          offers.add(offer);
        } catch (HttpClientErrorException.TooManyRequests ex) {
          String seconds =
              ex.getResponseHeaders() == null
                  ? null
                  : ex.getResponseHeaders().getFirst("Retry-After");
          retryAt =
              now.plusSeconds(
                  seconds != null && seconds.matches("\\d+") ? Long.parseLong(seconds) : 60);
          return new Rates(offers, false, reset, version);
        } catch (RestClientException | IllegalArgumentException ex) {
          complete = false;
        }
      }
    } catch (RestClientException | IllegalArgumentException ex) {
      complete = false;
    }
    return new Rates(offers, complete, reset, version);
  }

  private static RateQuery query(String code, String name, BigDecimal weight, BigDecimal billed,
      BigDecimal bracket, Map<MarketMemory.Key, MarketMemory.Sample> history) {
    var key = bracket == null ? null : new MarketMemory.Key(code, bracket.stripTrailingZeros().toPlainString());
    return new RateQuery(code, name, weight, billed, key, key == null ? null : history.get(key));
  }
}
