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
  private Instant retryAt = Instant.EPOCH;

  FiveU(@Value("${dispatch.base-url}") String base, @Value("${dispatch.key}") String key) {
    client = client(base, key, 4);
    gateway = client(base, key, 15);
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

  record Rates(List<Offer> offers, boolean complete) {}

  synchronized Rates rates(Shipment s, Instant now) {
    List<Offer> offers = new ArrayList<>();
    if (now.isBefore(retryAt)) return new Rates(offers, false);
    boolean complete = true;
    try {
      var ports = get("/v1/portal/ports");
      for (Object raw : (List<?>) ports.get("lanes")) {
        var lane = (Map<?, ?>) raw;
        if (!s.origin().equals(lane.get("origin"))
            || !s.destination().equals(lane.get("destination"))) continue;
        for (Object airline : (List<?>) lane.get("airlines")) {
          try {
            var r =
                get(
                    "/v1/portal/rates?origin={o}&destination={d}&airline={a}&weight_kg={w}&volume_cbm={v}",
                    s.origin(),
                    s.destination(),
                    ((Map<?, ?>) airline).get("code"),
                    s.weightKg(),
                    s.volumeCbm());
            String currency = Ledger.str(r, "currency");
            var fx =
                switch (currency) {
                  case "EUR" -> BigDecimal.ONE;
                  case "USD" -> new BigDecimal(Ledger.str(r, "fx_eur_per_usd"));
                  default -> throw new IllegalArgumentException("Missing FX");
                };
            if (fx.signum() <= 0) throw new IllegalArgumentException("Invalid FX");
            var expiry = Objects.requireNonNull(Ledger.instant(r.get("valid_until")));
            var price = new BigDecimal(Ledger.str(r, "total")).multiply(fx);
            if (price.signum() > 0 && expiry.isAfter(now))
              offers.add(
                  new Offer(
                      Ledger.str(r, "airline"),
                      Integer.parseInt(Ledger.str(r, "transit_days")),
                      price,
                      new BigDecimal(Ledger.str(r, "chargeable_weight_kg")),
                      "portal",
                      Ledger.str(r, "rate_id"),
                      expiry));
          } catch (HttpClientErrorException.TooManyRequests ex) {
            String seconds =
                ex.getResponseHeaders() == null
                    ? null
                    : ex.getResponseHeaders().getFirst("Retry-After");
            retryAt =
                now.plusSeconds(
                    seconds != null && seconds.matches("\\d+") ? Long.parseLong(seconds) : 60);
            return new Rates(offers, false);
          } catch (RestClientException | IllegalArgumentException ex) {
            complete = false;
          }
        }
      }
    } catch (RestClientException ex) {
      complete = false;
    }
    return new Rates(offers, complete);
  }
}
