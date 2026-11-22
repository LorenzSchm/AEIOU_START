package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PickupEstimateTest {
  final Catalog catalog = new DispatchApplication().catalog(null);
  final Instant at = Instant.parse("2026-11-22T09:00:00Z");

  PickupEstimate.Bid bid(String carrier, String kg, String eur, int hours) {
    return new PickupEstimate.Bid(carrier, new BigDecimal(kg), new BigDecimal(eur), hours / 24.0, at);
  }

  @Test
  void citiesCoverTheirOwnPostcodeZones() {
    assertEquals(Set.of("50", "51"), PickupEstimate.zones(catalog, "Cologne", "50"));
    assertEquals(Set.of("80", "81"), PickupEstimate.zones(catalog, "Munich", "80"));
    assertEquals(Set.of("86"), PickupEstimate.zones(catalog, "Augsburg", "86"));
  }

  @Test
  void interpolatesBetweenTheNearestWeightsAndRoundsUp() {
    var estimates = PickupEstimate.perCarrier(List.of(
        bid("Westfalenroute", "343.0020142703", "145.16", 11), bid("Westfalenroute", "785", "198.20", 11)),
        new BigDecimal("633"));
    assertEquals(1, estimates.size());
    assertEquals(new BigDecimal("180.00"), estimates.getFirst().eur());
    assertEquals(11.0 / 24, estimates.getFirst().days(), 1e-9);
  }

  @Test
  void neverExtrapolatesBelowWhatTheCarrierCharged() {
    var bids = List.of(bid("Kranich Kurier", "400", "160.00", 17), bid("Kranich Kurier", "700", "220.00", 17));
    // Heavier than any bid: the per-kg rate of the heaviest one. Lighter: the lightest bid's price.
    assertEquals(new BigDecimal("252.00"), PickupEstimate.perCarrier(bids, new BigDecimal("800")).getFirst().eur());
    assertEquals(new BigDecimal("160.00"), PickupEstimate.perCarrier(bids, new BigDecimal("300")).getFirst().eur());
    // Too far from every bid to say anything.
    assertTrue(PickupEstimate.perCarrier(bids, new BigDecimal("1500")).isEmpty());
    assertTrue(PickupEstimate.perCarrier(bids, new BigDecimal("150")).isEmpty());
  }

  @Test
  void picksTheBalancedTruck() {
    var shipment = new Shipment("CGN", "LIM", new BigDecimal("633"), new BigDecimal("2.73"), "fastest", "50", null, 0);
    var estimates = PickupEstimate.perCarrier(List.of(
        bid("Westfalenroute", "343", "145.16", 11), bid("Westfalenroute", "785", "198.20", 11),
        bid("Elbkante Logistik", "343", "91.01", 29), bid("Elbkante Logistik", "785", "121.95", 29),
        bid("Hansekogge Trucking", "246", "80.22", 25), bid("Hansekogge Trucking", "704", "112.28", 25)),
        shipment.chargeableKg());
    assertEquals("Westfalenroute", PickupEstimate.choose(estimates, shipment).orElseThrow().carrier());
  }
}
