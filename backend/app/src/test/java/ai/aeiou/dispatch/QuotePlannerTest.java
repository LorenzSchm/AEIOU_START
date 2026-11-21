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
}
