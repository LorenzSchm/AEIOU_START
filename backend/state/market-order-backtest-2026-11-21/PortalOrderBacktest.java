package ai.aeiou.dispatch;

import ai.aeiou.freight.Offer;
import ai.aeiou.freight.Shipment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public final class PortalOrderBacktest {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
  private static final List<Integer> ASSUMED_BRACKETS = List.of(0, 45, 100, 250, 300, 500, 1000, 2000);

  private record Query(String airline, BigDecimal billed) {
    Query {
      billed = billed.stripTrailingZeros();
    }
  }

  private static final class RecordedPortal extends FiveU {
    final Map<Query, Offer> recorded;
    final List<String> carrierCodes;
    final List<Query> reads = new ArrayList<>();
    final Shipment shipment;

    RecordedPortal(Ledger database, Map<Query, Offer> recorded, List<String> carrierCodes,
        Shipment shipment) {
      super("http://127.0.0.1:1", "offline", new MarketMemory(database));
      this.recorded = recorded;
      this.carrierCodes = carrierCodes;
      this.shipment = shipment;
    }

    @Override Map<String, Object> get(String uri, Object... variables) {
      if (uri.equals("/v1/portal/ports")) {
        return Map.of("lanes", List.of(Map.of("origin", shipment.origin(),
            "destination", shipment.destination(), "airlines",
            carrierCodes.stream().map(code -> Map.of("code", code)).toList())));
      }
      if (uri.startsWith("/v1/portal/brackets")) {
        var chargeable = shipment.chargeableKg();
        var brackets = new TreeSet<BigDecimal>();
        ASSUMED_BRACKETS.stream().map(BigDecimal::valueOf)
            .filter(bracket -> bracket.compareTo(chargeable) <= 0).forEach(brackets::add);
        recorded.keySet().stream().filter(query -> variables[0].equals(query.airline())
            && query.billed().compareTo(chargeable) > 0).map(Query::billed).forEach(brackets::add);
        return Map.of("breaks_kg", List.copyOf(brackets));
      }
      if (!uri.startsWith("/v1/portal/rates")) throw new AssertionError("Unexpected request " + uri);
      var query = new Query((String) variables[2],
          ((BigDecimal) variables[3]).max(shipment.chargeableKg()));
      reads.add(query);
      var offer = recorded.get(query);
      if (offer == null) throw new AssertionError("No archived rate for " + query);
      return Map.of("airline", offer.airline(), "currency", "EUR", "total", offer.totalEur(),
          "chargeable_weight_kg", offer.billedKg(), "transit_days", (int) offer.days(),
          "valid_until", offer.validUntil().toString(), "rate_id", offer.rateId());
    }
  }

  private static void verifyRates(RecordedPortal portal, FiveU.Rates rates) {
    if (!rates.complete() || portal.reads.size() != portal.recorded.size()
        || !new HashSet<>(portal.reads).equals(portal.recorded.keySet())
        || !new HashSet<>(rates.offers()).equals(new HashSet<>(portal.recorded.values()))) {
      throw new AssertionError("Replay differs from archived offer set");
    }
  }

  public static void main(String[] args) throws Exception {
    var archive = JSON.readTree(Path.of(args[0]).toFile());
    var portalDirectory = JSON.readTree(Path.of(args[1]).toFile()).at("/lanes/lanes");
    var directory = Path.of(args[2]);
    var databaseDirectory = Files.createTempDirectory(directory, "market-order-");
    var database = new Ledger(databaseDirectory.resolve("history.sqlite").toString(), JSON);
    var rows = new ArrayList<Map<String, Object>>();
    Instant previous = Instant.MIN;
    for (var round : archive.path("rounds")) {
      var result = new LinkedHashMap<String, Object>();
      int number = round.at("/official/round_no").asInt();
      result.put("round", number);
      var shipment = JSON.treeToValue(round.path("historical_shipment"), Shipment.class);
      result.put("lane", shipment.origin() + "-" + shipment.destination());
      result.put("preference", shipment.preference());
      rows.add(result);
      var offers = round.path("historical_portal");
      if (offers.isEmpty()) {
        result.put("status", "no recorded portal offers");
        continue;
      }
      var carrierCodes = new LinkedHashMap<String, String>();
      for (JsonNode lane : portalDirectory) {
        if (!lane.path("origin").asText().equals(shipment.origin())
            || !lane.path("destination").asText().equals(shipment.destination())) continue;
        for (var carrier : lane.path("airlines")) {
          carrierCodes.put(carrier.path("name").asText(), carrier.path("code").asText());
        }
      }
      var recorded = new LinkedHashMap<Query, Offer>();
      Instant firstObservation = Instant.MAX;
      for (var entry : offers) {
        var offer = JSON.treeToValue(entry.path("offer"), Offer.class);
        var code = Objects.requireNonNull(carrierCodes.get(offer.airline()));
        recorded.putIfAbsent(new Query(code, offer.billedKg()), offer);
        var seen = Instant.parse(entry.path("available_at").asText());
        if (seen.isBefore(firstObservation)) firstObservation = seen;
      }
      if (!previous.isBefore(firstObservation)) throw new AssertionError("Rounds out of order");
      previous = firstObservation;
      var history = new MarketMemory(database).recent(shipment, firstObservation);
      var coldDatabase = new Ledger(databaseDirectory.resolve("baseline-" + number + ".sqlite").toString(), JSON);
      var cold = new RecordedPortal(coldDatabase, recorded, List.copyOf(carrierCodes.values()), shipment);
      var warm = new RecordedPortal(database, recorded, List.copyOf(carrierCodes.values()), shipment);
      var baseline = cold.rates(shipment, firstObservation);
      var reordered = warm.rates(shipment, firstObservation);
      verifyRates(cold, baseline);
      verifyRates(warm, reordered);
      var winner = Collections.min(baseline.offers(), Offer.order(shipment.preference()));
      var winnerQuery = new Query(carrierCodes.get(winner.airline()), winner.billedKg());
      if (!winner.equals(Collections.min(reordered.offers(), Offer.order(shipment.preference())))) {
        throw new AssertionError("Confirmed winner changed in round " + number);
      }
      result.put("status", "scored");
      result.put("recent_samples_on_lane", history.size());
      result.put("scheduled_rate_checks_per_policy", recorded.size());
      result.put("winner", winner.airline());
      result.put("winner_eur", winner.totalEur());
      result.put("baseline_winner_position", cold.reads.indexOf(winnerQuery) + 1);
      result.put("priority_winner_position", warm.reads.indexOf(winnerQuery) + 1);
      result.put("baseline_order", cold.reads);
      result.put("priority_order", warm.reads);
    }
    JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("rounds.json").toFile(), rows);
    try (var files = Files.list(databaseDirectory)) {
      for (var path : files.toList()) Files.delete(path);
    }
    Files.delete(databaseDirectory);
  }
}
