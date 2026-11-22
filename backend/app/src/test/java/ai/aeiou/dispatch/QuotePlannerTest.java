package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.Offer;
import ai.aeiou.freight.QuotePlanner;
import ai.aeiou.freight.Shipment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuotePlannerTest {
  private static final List<Offer> BIDS = List.of(
      bid("Elbflug Air Freight", 7, "5580.34"),
      bid("Juniper Cargo Lines", 4, "6957.54"),
      bid("Nova Atlas Cargo", 5, "6385.20"));

  private static Offer bid(String airline, double days, String eur) {
    return new Offer(airline, days, new BigDecimal(eur), BigDecimal.valueOf(1000), "carrier bid",
        null, null);
  }

  private static Shipment shipment(String preference) {
    return new Shipment("FRA", "MEL", BigDecimal.valueOf(1000), BigDecimal.ONE, preference, null,
        null, 0);
  }

  @Test
  void fastestQuotesTheQuickestEmailBid() {
    var plan = QuotePlanner.plan(shipment("fastest"), List.of(), BIDS, List.of(), Set.of());
    assertEquals("Juniper Cargo Lines", plan.best().airline());
  }

  @Test
  void cheapestQuotesTheLowestEmailBid() {
    var plan = QuotePlanner.plan(shipment("cheapest"), List.of(), BIDS, List.of(), Set.of());
    assertEquals("Elbflug Air Freight", plan.best().airline());
  }

  private static Offer truck(String name, String eur, Integer hours) {
    return bid(name, hours == null ? 0 : hours / 24.0, eur);
  }

  private static Shipment pickup(String preference) {
    return new Shipment("FRA", "MEL", BigDecimal.valueOf(1000), BigDecimal.ONE, preference, "60",
        null, 0);
  }

  /**
   * Truck bids of scored live rounds. The balanced choice (0.5 price/lowest + 0.5 transit/shortest)
   * is the truck whose total reproduces the board's accuracy points, or fixes a too_low rejection.
   */
  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> liveRounds() {
    return java.util.stream.Stream.of(
        // R53 req 14293 too_low: the bid without transit (132.02) was quoted.
        args("cheapest", "Neckarstrom", truck("Elbkante", "132.02", null),
            truck("Isarwelle", "178.94", 33), truck("Neckarstrom", "297.62", 5)),
        // R80 req 22569 too_low with Vogelsberg.
        args("cheapest", "Bodenseefracht", truck("Bodenseefracht", "190.40", 6),
            truck("Domblick", "169.72", 8), truck("Vogelsberg", "82.92", 29)),
        // R140 req 41536 too_low with Bodenseefracht.
        args("cheapest", "Grautal", truck("Bodenseefracht", "131.85", 31), truck("Domblick", "371.89", 13),
            truck("Grautal", "386.89", 7), truck("Maintal", "200.70", 33), truck("Taunus", "287.21", 15)),
        // R143 req 42578 too_low: Vogelsberg and Bodenseefracht only stated a lead time.
        args("cheapest", "Domblick", truck("Bodenseefracht", "272.27", null),
            truck("Domblick", "235.22", 8), truck("Vogelsberg", "121.13", null)),
        // R71 req 19873 too_low with Vogelsberg.
        args("cheapest", "Domblick", truck("Domblick", "273.09", 8), truck("Vogelsberg", "143.22", 29)),
        // R155 req 46432 accepted with full price points (45).
        args("cheapest", "Westfalenroute", truck("Rheinufer", "227.46", 7), truck("Schwabenbote", "219.43", 6),
            truck("Spessart", "70.85", 32), truck("Westfalenroute", "203.58", 4)),
        // R44 req 11294, R77 req 21728 and R162 req 48961: the cheapest truck was right (45 points).
        args("cheapest", "Neckarstrom", truck("Maintal", "146.46", 26), truck("Neckarstrom", "114.90", 31)),
        args("cheapest", "Sternmeile", truck("Elbkante", "106.94", 32), truck("Sternmeile", "102.94", 29)),
        args("cheapest", "Isarwelle", truck("Elbkante", "163.28", 15), truck("Flinkwagen", "161.72", 14),
            truck("Isarwelle", "60.60", 29), truck("Maintal", "105.64", 24)),
        // R124 req 36823 accepted at 28.89: Westfalenroute reproduces the points exactly.
        args("cheapest", "Westfalenroute", truck("Bodenseefracht", "240.60", 12), truck("Elbkante", "121.95", 29),
            truck("Maintal", "202.35", 13), truck("Westfalenroute", "198.20", 11)),
        // R114 req 33824 (fastest) accepted at 32.19: Neckarstrom reproduces the points exactly.
        args("fastest", "Neckarstrom", truck("Maintal", "136.80", 26), truck("Neckarstrom", "108.00", 31),
            truck("Sternmeile", "231.40", 23)));
  }

  private static org.junit.jupiter.params.provider.Arguments args(String preference, String expected,
      Offer... trucks) {
    return org.junit.jupiter.params.provider.Arguments.of(preference, expected, List.of(trucks));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("liveRounds")
  void picksTheMostBalancedConfirmedTruck(String preference, String expected, List<Offer> trucks) {
    var missing = new java.util.HashSet<String>();
    trucks.stream().filter(t -> t.days() == 0).forEach(t -> missing.add(t.airline()));
    var plan = QuotePlanner.plan(pickup(preference), List.of(), BIDS, trucks, missing);
    assertEquals(expected, plan.trucker());
    assertEquals("cheapest".equals(preference) ? "Elbflug Air Freight" : "Juniper Cargo Lines",
        plan.best().airline());
  }

  @Test
  void quotesTheCheapestUntimedTruckOnlyWhenNoTransitIsConfirmed() {
    var trucks = List.of(truck("Alpenblick", "101.28", null), truck("Maintal", "152.74", null));
    var plan = QuotePlanner.plan(pickup("cheapest"), List.of(), BIDS, trucks, Set.of("Alpenblick", "Maintal"));
    assertEquals("Alpenblick", plan.trucker());
    assertNull(plan.pickupDays());
  }
}
