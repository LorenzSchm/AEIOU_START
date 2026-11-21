package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MarketReplayTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
  private static final Instant START = Instant.parse("2026-11-21T15:00:00Z");
  @TempDir Path temp;
  private Ledger ledger;
  private List<JsonNode> rounds;

  @BeforeEach void setup() throws Exception {
    ledger = new Ledger(temp.resolve("market.sqlite").toString(), mapper);
    rounds = new ArrayList<>();
    mapper.readTree(getClass().getResource("/six-wave-rfqs.json")).forEach(rounds::add);
    rounds.add(mapper.readTree(getClass().getResource("/round-nine-rfq.json")));
  }

  // Expose only names and a bracket on discovery. Each recorded price is revealed on its
  // own rate read. The fixtures lack portal metadata: use one stable version and no unrecorded buy-ups.
  private class Portal extends FiveU {
    final List<String> reads = new ArrayList<>();
    final Map<String, Offer> recorded = new LinkedHashMap<>();
    String version = "fixture-v1";
    Shipment shipment;
    Instant now;

    Portal() { super("http://localhost", "fixture", ledger); }

    void load(int index) throws Exception {
      reads.clear();
      recorded.clear();
      var row = rounds.get(index);
      for (var o : mapper.treeToValue(row.get("offers"), Offer[].class)) recorded.put(o.airline(), o);
      shipment = mapper.treeToValue(row.get("shipment"), Shipment.class);
      now = START.plusSeconds(360L * (index == 6 ? 8 : index));
    }

    @Override Map<String, Object> get(String uri, Object... args) {
      if (uri.endsWith("/ports"))
        return Map.of("lanes", List.of(Map.of("origin", shipment.origin(), "destination",
            shipment.destination(), "airlines", recorded.keySet().stream()
                .map(a -> Map.of("code", a, "name", a)).toList())));
      if (uri.contains("/brackets")) return Map.of("breaks_kg", List.of(0, 500));
      String name = (String) args[2];
      reads.add(name);
      var o = recorded.get(name);
      return Map.of("airline", name, "currency", "EUR", "total", o.totalEur(),
          "chargeable_weight_kg", o.billedKg(), "transit_days", (int) o.days(),
          "valid_until", now.plusSeconds(900).toString(), "rate_id", o.rateId(),
          "dataset_version", version);
    }
  }

  @Test void chronologicalPortalReplay() throws Exception {
    int reads = 0, available = 0, firstTwo = 0, baselineFirstTwo = 0;
    for (int i = 0; i < rounds.size(); i++) {
      // Rebuild the adapter between every round; learned observations must survive restart.
      var portal = new Portal();
      portal.load(i);
      var result = portal.rates(portal.shipment, portal.now);
      var order = Offer.order(portal.shipment.preference());
      var winner = Collections.min(portal.recorded.values(), order);
      assertTrue(result.complete());
      assertEquals(winner.rateId(), Collections.min(result.offers(), order).rateId());
      if (portal.reads.stream().limit(2).anyMatch(winner.airline()::equals)) firstTwo++;
      if (portal.recorded.keySet().stream().limit(2).anyMatch(winner.airline()::equals)) baselineFirstTwo++;
      reads += portal.reads.size();
      available += portal.recorded.size();
      if (i == 3) assertEquals(List.of("Xenon Cargo Airlines", "Aurelia Air Cargo"), portal.reads);
    }
    assertEquals(available - 2, reads);
    assertEquals(baselineFirstTwo + 1, firstTwo);
    System.out.printf("Portal replay: %d rounds, %d/%d live reads, best in first two %d vs %d.%n",
        rounds.size(), reads, available, firstTwo, baselineFirstTwo);
  }

  @Test void changedWinnerReopensTheLaneBeforeTheQuote() throws Exception {
    var portal = new Portal();
    portal.load(0);
    portal.rates(portal.shipment, portal.now);
    portal.load(3);
    var old = portal.recorded.get("Xenon Cargo Airlines");
    portal.recorded.put(old.airline(), new Offer(old.airline(), old.days(), new BigDecimal("2200"),
        old.billedKg(), old.source(), "changed-rate", old.validUntil()));
    var fresh = portal.rates(portal.shipment, portal.now);
    assertTrue(fresh.reset());
    assertEquals(4, portal.reads.size());
    assertEquals("Aurelia Air Cargo", Collections.min(fresh.offers(), Offer.order("cheapest")).airline());
  }

  @Test void aVersionChangeForcesFreshReadsEvenWithIdenticalPrices() throws Exception {
    var portal = new Portal();
    portal.load(0);
    portal.rates(portal.shipment, portal.now);
    portal.load(3);
    portal.version = "fixture-v2";
    assertTrue(portal.rates(portal.shipment, portal.now).reset());
    assertEquals(4, portal.reads.size());
  }

  @Test void staleSamplesCannotShortenTheSearch() throws Exception {
    var portal = new Portal();
    portal.load(0);
    portal.rates(portal.shipment, portal.now);
    portal.load(3);
    portal.now = START.plusSeconds(3601);
    portal.rates(portal.shipment, portal.now);
    assertEquals(List.copyOf(portal.recorded.keySet()), portal.reads);
  }

  @Test void partialLookupResumesOnTheSameVersionAndDiscardsCacheOnChange() throws Exception {
    var portal = new Portal();
    portal.load(0);
    var confirmed = portal.rates(portal.shipment, portal.now);
    var partial = List.of(confirmed.offers().getFirst());
    portal.reads.clear();
    var rest = portal.rates(portal.shipment, portal.now.plusSeconds(35), partial, confirmed.version());
    assertEquals(3, rest.offers().size());
    assertFalse(portal.reads.contains(partial.getFirst().airline()));
    portal.version = "new-dataset";
    var invalid = portal.rates(portal.shipment, portal.now.plusSeconds(70), partial, confirmed.version());
    assertTrue(invalid.reset());
    assertFalse(invalid.complete());
    assertTrue(invalid.offers().isEmpty());
    assertEquals(4, portal.rates(portal.shipment, portal.now.plusSeconds(71)).offers().size());
  }
}
