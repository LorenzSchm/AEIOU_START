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

    boolean clearlyWorseThan(Offer best, String preference) {
      if ("fastest".equals(preference) && sample.days() != best.days())
        return sample.days() > best.days();
      return estimate().multiply(new BigDecimal("0.95")).compareTo(best.totalEur()) > 0;
    }
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

  private record QueryPlan(List<RateQuery> ordered, boolean complete, boolean predictable) {}

  private QueryPlan plan(Shipment shipment, Map<MarketMemory.Key, MarketMemory.Sample> history) {
    boolean complete = true;
    var queries = new ArrayList<RateQuery>();
    var higherBreaks = new ArrayList<RateQuery>();
    var ports = get("/v1/portal/ports");
    for (Object raw : (List<?>) ports.get("lanes")) {
      var lane = (Map<?, ?>) raw;
      if (!shipment.origin().equals(lane.get("origin"))
          || !shipment.destination().equals(lane.get("destination"))) continue;
      for (Object airline : (List<?>) lane.get("airlines")) {
        var carrier = (Map<?, ?>) airline;
        String code = carrier.get("code").toString();
        String name = Ledger.str(carrier, "name");
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
    boolean predictable = !queries.isEmpty() && known.size() == queries.size()
        && known.stream().map(q -> q.sample().version()).distinct().count() == 1;
    return new QueryPlan(ordered, complete, predictable);
  }

  synchronized Rates rates(Shipment shipment, Instant now) {
    return rates(shipment, now, List.of(), null);
  }

  synchronized Rates rates(Shipment shipment, Instant now, List<Offer> verified, String priorVersion) {
    List<Offer> offers = new ArrayList<>();
    if (now.isBefore(retryAt)) return new Rates(offers, false);
    boolean complete = true;
    boolean reset = false;
    String version = priorVersion;
    var history = memory.recent(shipment, now);
    try {
      var plan = plan(shipment, history);
      complete = plan.complete();
      boolean canShorten = complete && plan.predictable() && verified.isEmpty();
      var checkedAirlines = new HashSet<String>();
      Offer best = null;
      for (var query : plan.ordered()) {
        if (!reset && verified.stream().anyMatch(o -> o.airline().equals(query.name())
            && o.billedKg().compareTo(query.billed()) == 0 && o.validUntil() != null
            && o.validUntil().isAfter(now.plusSeconds(120)))) continue;
        if (canShorten && checkedAirlines.size() >= 2 && query.clearlyWorseThan(best, shipment.preference())) break;
        try {
          var live = rate(shipment, query, now);
          var offer = live.offer();
          if (version != null && !version.equals(live.version())) {
            memory.forget(shipment);
            // The dataset changed midway. Restart on a single version on the next tick.
            return new Rates(List.of(), false, true, null);
          }
          version = live.version();
          if (!reset && query.sample() != null && query.sample().disagrees(offer, version)) {
            memory.forget(shipment);
            reset = true;
            canShorten = false;
            if (!verified.isEmpty()) return new Rates(List.of(), false, true, null);
          }
          if (version.isBlank() || offer.rateId() == null || offer.rateId().isBlank()
              || offer.billedKg().compareTo(query.billed()) != 0
              || !offer.validUntil().isAfter(now.plusSeconds(120))) canShorten = false;
          if (query.key() != null && offer.billedKg().compareTo(query.billed()) == 0)
            memory.record(shipment, query.key(), offer, version, now);
          offers.add(offer);
          checkedAirlines.add(query.airline());
          if (best == null || Offer.order(shipment.preference()).compare(offer, best) < 0) best = offer;
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
          canShorten = false;
        }
      }
    } catch (RestClientException ex) {
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
