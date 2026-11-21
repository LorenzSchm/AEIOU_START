package ai.aeiou.dispatch;

import ai.aeiou.freight.Offer;
import ai.aeiou.freight.Shipment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public final class MarketPriceBacktest {
  // The archive has billed weights and some buy-ups, but no per-carrier bracket metadata.
  private static String assumedBracket(BigDecimal kg) {
    for (int threshold : List.of(2000, 1000, 500, 300, 100, 45, 0)) {
      if (kg.compareTo(BigDecimal.valueOf(threshold)) >= 0) return Integer.toString(threshold);
    }
    throw new IllegalArgumentException("Negative billed weight");
  }

  public static void main(String[] args) throws Exception {
    var directory = Path.of(args[0]);
    var mapper = new ObjectMapper().findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    var db = Files.createTempFile(directory, "market-", ".sqlite");
    var ledger = new Ledger(db.toString(), mapper);
    var memory = new MarketMemory(ledger);
    var rows = new ArrayList<Map<String, Object>>();
    var history = new ArrayList<Map.Entry<Shipment, Map.Entry<Instant, Offer>>>();
    var input = mapper.readTree(directory.resolve("input.json").toFile());
    for (var round : input.path("rounds")) {
      int number = round.path("official").path("round_no").asInt();
      var sentAt = Instant.parse(round.at("/rfq/received_at").asText());
      var shipment = mapper.treeToValue(round.path("historical_shipment"), Shipment.class);
      if (shipment == null || shipment.weightKg() == null || shipment.volumeCbm() == null) continue;
      for (var it = history.iterator(); it.hasNext(); ) {
        var record = it.next();
        var observation = record.getValue();
        if (!observation.getKey().isBefore(sentAt)) continue;
        var offer = observation.getValue();
        memory.record(record.getKey(), new MarketMemory.Key(offer.airline(), assumedBracket(offer.billedKg())),
            offer, "assumed-stable", observation.getKey());
        it.remove();
      }
      var samples = memory.recent(shipment, sentAt);
      var recorded = new HashSet<String>();
      for (var entry : round.path("historical_portal")) {
        var offer = mapper.treeToValue(entry.path("offer"), Offer.class);
        if (!recorded.add(offer.airline() + ":" + offer.billedKg().stripTrailingZeros())) continue;
        var key = new MarketMemory.Key(offer.airline(), assumedBracket(offer.billedKg()));
        var sample = samples.get(key);
        var row = new LinkedHashMap<String, Object>();
        row.put("round", number);
        row.put("airline", offer.airline());
        row.put("lane", shipment.origin() + "-" + shipment.destination());
        row.put("billed_kg", offer.billedKg());
        row.put("assumed_bracket", key.bracket());
        row.put("actual_eur", offer.totalEur());
        if (sample != null) {
          var estimate = sample.estimate(offer.billedKg());
          row.put("estimated_eur", estimate);
          row.put("absolute_percentage_error", estimate.subtract(offer.totalEur()).abs()
              .multiply(BigDecimal.valueOf(100)).divide(offer.totalEur(), MathContext.DECIMAL64));
          row.put("transit_days_match", sample.days() == offer.days());
        }
        rows.add(row);
        history.add(Map.entry(shipment, Map.entry(Instant.parse(entry.path("available_at").asText()), offer)));
      }
    }
    mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("market-price-shadow.json").toFile(), rows);
    Files.delete(db);
  }
}
