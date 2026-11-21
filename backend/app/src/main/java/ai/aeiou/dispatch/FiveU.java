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
  private final MarketMemory marketMemory;
  private Instant retryAt = Instant.EPOCH;
  private List<?> lanes = List.of();
  private Instant lanesExpire = Instant.EPOCH;
  private Instant directoryRetryAt = Instant.EPOCH;

  FiveU(@Value("${dispatch.base-url}") String base, @Value("${dispatch.key}") String key,
      MarketMemory marketMemory) {
    client = client(base, key, 4);
    gateway = client(base, key, 15);
    this.marketMemory = marketMemory;
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
    Rates(List<Offer> offers, boolean complete) {
      this(offers, complete, false, null);
    }
  }

  private record RateQuery(
      String airline, String name, BigDecimal weight, BigDecimal billed, BigDecimal bracket) {
    MarketMemory.Key key() {
      return bracket == null ? null : new MarketMemory.Key(airline, bracket);
    }

    boolean coveredBy(Offer offer, Instant now) {
      return (airline.equalsIgnoreCase(offer.airline())
              || (!name.isBlank() && name.equalsIgnoreCase(offer.airline())))
          && billed.compareTo(offer.billedKg()) == 0
          && offer.rateId() != null && !offer.rateId().isBlank()
          && offer.validUntil() != null && offer.validUntil().isAfter(now.plusSeconds(4));
    }
  }

  private record LiveRate(Offer offer, String version) {}

  private static Comparator<RateQuery> queryOrder(Shipment shipment,
      Map<MarketMemory.Key, MarketMemory.Sample> samples) {
    Comparator<RateQuery> byPrice = Comparator.comparing(query -> {
      var sample = samples.get(query.key());
      return sample == null ? null : sample.estimate(query.billed());
    }, Comparator.nullsLast(Comparator.naturalOrder()));
    if (!"fastest".equals(shipment.preference())) return byPrice;
    Comparator<RateQuery> byTransit = Comparator.comparingDouble(query -> {
      var sample = samples.get(query.key());
      return sample == null ? Double.POSITIVE_INFINITY : sample.days();
    });
    return byTransit.thenComparing(byPrice);
  }

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
    return new LiveRate(offer, Ledger.str(r, "dataset_version"));
  }

  private List<?> portalLanes(Instant now) {
    if (!now.isBefore(lanesExpire)) {
      var ports = get("/v1/portal/ports");
      if (ports == null || !(ports.get("lanes") instanceof List<?> published))
        throw new IllegalArgumentException("Missing portal lanes");
      lanes = published;
      lanesExpire = now.plusSeconds(30);
    }
    return lanes;
  }

  synchronized String airlineName(String code, Instant now) {
    if (!now.isBefore(directoryRetryAt)) {
      try {
        portalLanes(now);
      } catch (RestClientException | IllegalArgumentException ex) {
        directoryRetryAt = now.plusSeconds(30);
      }
    }
    for (Object raw : lanes) {
      if (!(raw instanceof Map<?, ?> lane) || !(lane.get("airlines") instanceof List<?> airlines))
        continue;
      for (Object airline : airlines) {
        if (airline instanceof Map<?, ?> carrier && code.equalsIgnoreCase(Ledger.str(carrier, "code"))) {
          String name = Ledger.str(carrier, "name");
          if (!name.isBlank()) return name;
        }
      }
    }
    return null;
  }

  Rates rates(Shipment shipment, Instant now) {
    return rates(shipment, now, List.of(), null);
  }

  synchronized Rates rates(Shipment shipment, Instant now, List<Offer> verified, String priorVersion) {
    List<Offer> offers = new ArrayList<>();
    if (now.isBefore(retryAt)) return new Rates(offers, false, false, priorVersion);
    var confirmed = new LinkedHashMap<MarketMemory.Key, Offer>();
    boolean complete = true;
    String version = priorVersion;
    try {
      var queries = new ArrayList<RateQuery>();
      var higherBreaks = new ArrayList<RateQuery>();
      var chargeable = shipment.chargeableKg();
      for (Object raw : portalLanes(now)) {
        var lane = (Map<?, ?>) raw;
        if (!shipment.origin().equals(lane.get("origin"))
            || !shipment.destination().equals(lane.get("destination"))) continue;
        for (Object airline : (List<?>) lane.get("airlines")) {
          var carrier = (Map<?, ?>) airline;
          String code = Ledger.str(carrier, "code");
          String name = Ledger.str(carrier, "name");
          BigDecimal bracket = null;
          try {
            var brackets = get("/v1/portal/brackets?airline={a}", code);
            if (!(brackets.get("breaks_kg") instanceof List<?> published))
              throw new IllegalArgumentException("Missing portal weight brackets");
            var breaks = new TreeSet<BigDecimal>();
            for (var kg : published) breaks.add(new BigDecimal(kg.toString()));
            if (!breaks.isEmpty()) {
              breaks.add(BigDecimal.ZERO);
              bracket = breaks.floor(chargeable);
              var next = breaks.higher(chargeable);
              if (next != null) higherBreaks.add(new RateQuery(code, name, next, next, next));
            }
          } catch (RestClientException | IllegalArgumentException ex) {
            complete = false;
          }
          queries.add(new RateQuery(code, name, shipment.weightKg(), chargeable, bracket));
        }
      }
      // Confirm base rates for every airline before comparing optional buy-ups.
      var samples = marketMemory.recent(shipment, now);
      if (!samples.isEmpty()) {
        var order = queryOrder(shipment, samples);
        queries.sort(order);
        higherBreaks.sort(order);
      }
      queries.addAll(higherBreaks);
      for (var query : queries) {
        if (priorVersion != null && verified.stream().anyMatch(offer -> query.coveredBy(offer, now)))
          continue;
        try {
          var live = rate(shipment, query, now);
          if (version != null && !version.equals(live.version())) {
            // The next pass must verify all offers against a single dataset.
            return new Rates(List.of(), false, true, null);
          }
          version = live.version();
          var offer = live.offer();
          offers.add(offer);
          var key = query.key();
          if (key != null && !offer.rateId().isBlank()
              && offer.billedKg().compareTo(query.billed()) == 0) {
            confirmed.put(key, offer);
          }
        } catch (HttpClientErrorException.TooManyRequests ex) {
          String seconds =
              ex.getResponseHeaders() == null
                  ? null
                  : ex.getResponseHeaders().getFirst("Retry-After");
          retryAt =
              now.plusSeconds(
                  seconds != null && seconds.matches("\\d+") ? Long.parseLong(seconds) : 60);
          complete = false;
          break;
        } catch (RestClientException | IllegalArgumentException ex) {
          complete = false;
        }
      }
    } catch (RestClientException | IllegalArgumentException ex) {
      complete = false;
    }
    marketMemory.record(shipment, confirmed, now);
    // Replace offers from before dataset versions were stored once fresh rates are available.
    boolean replaceLegacyOffers = priorVersion == null && !verified.isEmpty() && !offers.isEmpty();
    return new Rates(offers, complete, replaceLegacyOffers, version);
  }
}
