package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.Offer;
import ai.aeiou.freight.Shipment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketMemoryTest {
  private static final Instant START = Instant.parse("2026-11-21T15:00:00Z");
  private static final List<String> AIRLINES = List.of("Alpha", "Bravo", "Charlie");
  @TempDir Path temp;

  private static Shipment shipment(String preference, int weight) {
    return new Shipment("CGN", "ORD", BigDecimal.valueOf(weight), BigDecimal.ONE,
        preference, null, null, 0);
  }

  @Test
  void persistedPredictionsReorderEveryLiveCheckAndStillFindAnUnexpectedWinner() throws Exception {
    var db = temp.resolve("market.sqlite").toString();
    var first = new Portal(new Ledger(db, new ObjectMapper()));
    var cheapest = shipment("cheapest", 700);
    assertTrue(first.rates(cheapest, START).complete());
    assertEquals(List.of("Alpha:700", "Bravo:700", "Charlie:700",
        "Alpha:1000", "Bravo:1000", "Charlie:1000"), first.reads);

    var second = new Portal(new Ledger(db, new ObjectMapper()));
    second.basePrices.put("Charlie", 1000);
    var live = second.rates(cheapest, START.plusSeconds(360));
    assertTrue(live.complete());
    assertEquals(List.of("Bravo:700", "Alpha:700", "Charlie:700",
        "Bravo:1000", "Alpha:1000", "Charlie:1000"), second.reads);
    var winner = Collections.min(live.offers(), Offer.order("cheapest"));
    assertEquals("Charlie", winner.airline());
    assertEquals(new BigDecimal("1000.00"), winner.totalEur());
  }

  @Test
  void fastestUsesTransitAndOldOrDifferentBracketHistoryKeepsPortalOrder() throws Exception {
    var ledger = new Ledger(temp.resolve("market.sqlite").toString(), new ObjectMapper());
    var first = new Portal(ledger);
    first.rates(shipment("cheapest", 700), START);

    var fastest = new Portal(ledger);
    fastest.rates(shipment("fastest", 800), START.plusSeconds(360));
    assertEquals(List.of("Charlie:800", "Alpha:800", "Bravo:800"), fastest.reads.subList(0, 3));

    var differentBracket = new Portal(ledger);
    differentBracket.rates(shipment("fastest", 450), START.plusSeconds(720));
    assertEquals(List.of("Alpha:450", "Bravo:450", "Charlie:450"),
        differentBracket.reads.subList(0, 3));

    var stale = new Portal(ledger);
    stale.rates(shipment("fastest", 800), START.plusSeconds(4500));
    assertEquals(List.of("Alpha:800", "Bravo:800", "Charlie:800"), stale.reads.subList(0, 3));
  }

  @Test
  void discardsVerifiedOffersWhenThePortalDatasetChanges() throws Exception {
    var ledger = new Ledger(temp.resolve("market.sqlite").toString(), new ObjectMapper());
    var portal = new Portal(ledger);
    var shipment = shipment("cheapest", 700);
    var first = portal.rates(shipment, START);
    var verified = List.of(first.offers().getFirst());
    portal.version = "v2";
    portal.reads.clear();

    var changed = portal.rates(shipment, START.plusSeconds(35), verified, first.version());
    assertTrue(changed.reset());
    assertFalse(changed.complete());
    assertTrue(changed.offers().isEmpty());
    assertNull(changed.version());
    assertEquals(1, portal.reads.size());
    assertFalse(portal.reads.contains(verified.getFirst().rateId()));

    var fresh = portal.rates(shipment, START.plusSeconds(36));
    assertTrue(fresh.complete());
    assertEquals("v2", fresh.version());
    assertEquals(6, fresh.offers().size());
  }

  private static final class Portal extends FiveU {
    final List<String> reads = new ArrayList<>();
    String version = "v1";
    final Map<String, Integer> basePrices = new HashMap<>(
        Map.of("Alpha", 1500, "Bravo", 1200, "Charlie", 1800));
    private static final Map<String, Integer> TRANSIT = Map.of("Alpha", 2, "Bravo", 4, "Charlie", 1);

    Portal(Ledger ledger) {
      super("http://127.0.0.1:1", "test", new MarketMemory(ledger));
    }

    @Override
    Map<String, Object> get(String uri, Object... variables) {
      if (uri.equals("/v1/portal/ports")) {
        return Map.of("lanes", List.of(Map.of("origin", "CGN", "destination", "ORD",
            "airlines", AIRLINES.stream().map(name -> Map.of("code", name)).toList())));
      }
      if (uri.startsWith("/v1/portal/brackets")) {
        return Map.of("breaks_kg", List.of(0, 500, 1000));
      }
      var airline = (String) variables[2];
      int weight = ((BigDecimal) variables[3]).intValueExact();
      var id = airline + ":" + weight;
      reads.add(id);
      int price = basePrices.get(airline) + (weight == 1000 ? 1000 : 0);
      return Map.of("airline", airline, "currency", "EUR", "total", price,
          "chargeable_weight_kg", weight, "transit_days", TRANSIT.get(airline),
          "valid_until", START.plusSeconds(5400).toString(), "rate_id", id,
          "dataset_version", version);
    }
  }
}
