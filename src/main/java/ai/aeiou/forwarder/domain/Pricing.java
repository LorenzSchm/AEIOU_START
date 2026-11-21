package ai.aeiou.forwarder.domain;

import static ai.aeiou.forwarder.domain.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.util.*;

public final class Pricing {
  private Pricing() {}

  public static BigDecimal decimal(JsonNode n, String field) {
    return new BigDecimal(n.get(field).asText());
  }

  public static BigDecimal cents(BigDecimal x) {
    return x.setScale(2, RoundingMode.HALF_UP);
  }

  public static ObjectNode money(BigDecimal amount, String currency) {
    return obj("amount", cents(amount).toPlainString(), "currency", currency);
  }

  public record Charges(
      BigDecimal billed, BigDecimal freight, BigDecimal fuel, BigDecimal security) {
    public BigDecimal total() {
      return freight.add(fuel).add(security);
    }
  }

  public static Charges price(
      JsonNode lane, BigDecimal gross, BigDecimal volume, boolean permitHigherBreak) {
    if (gross.signum() <= 0 || volume.signum() < 0)
      throw new IllegalArgumentException("Invalid physical quantities");
    var actual =
        gross.max(volume.multiply(new BigDecimal(lane.path("volumeFactor").asText("167"))));
    List<BigDecimal> bases = new ArrayList<>(List.of(actual));
    if (permitHigherBreak)
      for (var b : lane.get("breaks")) {
        var boundary = decimal(b, "fromKg");
        if (boundary.compareTo(actual) > 0) bases.add(boundary);
      }
    return bases.stream()
        .map(
            kg -> {
              BigDecimal rate = null;
              for (var b : lane.get("breaks"))
                if (decimal(b, "fromKg").compareTo(kg) <= 0) rate = decimal(b, "ratePerKg");
              if (rate == null) throw new IllegalArgumentException("No applicable break");
              return new Charges(
                  kg,
                  cents(kg.multiply(rate).max(decimal(lane, "minimum"))),
                  cents(kg.multiply(decimal(lane, "fuelPerKg"))),
                  cents(kg.multiply(decimal(lane, "securityPerKg"))));
            })
        .min(Comparator.comparing(Charges::total).thenComparing(Charges::billed))
        .orElseThrow();
  }

  public static ObjectNode tariff(
      JsonNode tariff, JsonNode lane, BigDecimal gross, BigDecimal volume, int revision) {
    Charges c = price(lane, gross, volume, lane.path("permitHigherBreak").asBoolean());
    String currency = str(lane, "currency"), id = str(tariff, "id");
    ArrayNode items = arr();
    for (var component :
        List.of(
            Map.entry("FREIGHT", c.freight),
            Map.entry("FUEL", c.fuel),
            Map.entry("SECURITY", c.security)))
      items.add(
          obj(
              "kind",
              component.getKey(),
              "label",
              component.getKey().toLowerCase(Locale.ROOT),
              "amount",
              money(component.getValue(), currency),
              "basis",
              c.billed.toPlainString() + " billed kg",
              "evidence",
              lane.get("evidence")));
    var total = money(c.total(), currency);
    boolean comparable = currency.equals("EUR");
    return obj(
        "id",
        id + "_" + str(lane, "origin") + str(lane, "destination") + "_r" + revision,
        "provider",
        tariff.get("carrier"),
        "source",
        "TARIFF",
        "tariffVersion",
        tariff.get("version"),
        "requestRevision",
        revision,
        "eligible",
        comparable,
        "reasons",
        comparable ? arr() : arr("Missing effective FX"),
        "billedKg",
        c.billed.stripTrailingZeros().toPlainString(),
        "quotedTotal",
        total,
        "comparisonTotal",
        comparable ? total : null,
        "transitDays",
        lane.get("transitDays"),
        "lineItems",
        items,
        "evidence",
        lane.get("evidence"),
        "validUntil",
        LocalDate.parse(str(tariff, "validTo"))
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toString());
  }

  public static Optional<JsonNode> choose(
      List<JsonNode> offers, String objective, Instant at, int revision, String pin) {
    if (!Set.of("CHEAPEST", "FASTEST").contains(objective)) return Optional.empty();
    List<JsonNode> eligible =
        offers.stream()
            .filter(
                x ->
                    x.path("eligible").asBoolean()
                        && x.has("comparisonTotal")
                        && x.has("transitDays")
                        && x.path("requestRevision").asInt() == revision
                        && (!x.has("validUntil")
                            || Instant.parse(str(x, "validUntil")).isAfter(at)))
            .toList();
    if (!pin.isEmpty()) return eligible.stream().filter(x -> str(x, "id").equals(pin)).findFirst();
    Comparator<JsonNode> cost =
        Comparator.comparing(x -> decimal(x.get("comparisonTotal"), "amount"));
    Comparator<JsonNode> time = Comparator.comparingInt(x -> x.get("transitDays").asInt());
    Comparator<JsonNode> stable =
        Comparator.comparing((JsonNode x) -> str(x, "provider")).thenComparing(x -> str(x, "id"));
    List<JsonNode> pool =
        new ArrayList<>(eligible.stream().filter(x -> !str(x, "source").equals("BID")).toList());
    var bids = eligible.stream().filter(x -> str(x, "source").equals("BID")).toList();
    if (!bids.isEmpty()) {
      var low = decimal(bids.stream().min(cost).orElseThrow().get("comparisonTotal"), "amount");
      var high = decimal(bids.stream().max(cost).orElseThrow().get("comparisonTotal"), "amount");
      int fast = bids.stream().min(time).orElseThrow().get("transitDays").asInt();
      int slow = bids.stream().max(time).orElseThrow().get("transitDays").asInt();
      pool.add(
          bids.stream()
              .min(
                  Comparator.comparing(
                          (JsonNode b) ->
                              normalize(
                                      decimal(b.get("comparisonTotal"), "amount").subtract(low),
                                      high.subtract(low))
                                  .add(
                                      normalize(
                                          BigDecimal.valueOf(b.get("transitDays").asInt() - fast),
                                          BigDecimal.valueOf(slow - fast))))
                      .thenComparing(stable))
              .orElseThrow());
    }
    return pool.stream()
        .min(
            (objective.equals("FASTEST") ? time.thenComparing(cost) : cost.thenComparing(time))
                .thenComparing(stable));
  }

  private static BigDecimal normalize(BigDecimal delta, BigDecimal span) {
    return span.signum() == 0 ? BigDecimal.ZERO : delta.divide(span, MathContext.DECIMAL128);
  }
}
