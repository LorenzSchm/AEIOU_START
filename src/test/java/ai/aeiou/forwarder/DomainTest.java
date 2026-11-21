package ai.aeiou.forwarder;

import static ai.aeiou.forwarder.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.forwarder.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DomainTest {
  @Test
  void pickupRequiresAnUnambiguousAddress() {
    assertFalse(
        ShipmentFacts.extract(
                "Please arrange collection from our warehouse.\n\n"
                    + "Cheers,\n"
                    + "Example, 90402 Nuremberg\n"
                    + "-----Original Message-----\n"
                    + "Pickup postcode 93047")
            .has("pickupPostcode"));
    assertTrue(
        ShipmentFacts.extract("Pickup in 51447 Bergisch Gladbach or 51103 Köln.")
            .path("pickupAmbiguous")
            .asBoolean());
    assertFalse(
        ShipmentFacts.extract("Reference 51447. Gross weight 51447 kg.").has("pickupPostcode"));
    assertEquals(
        "51447",
        str(
            ShipmentFacts.extract("Trucking from our site in 51447 Bergisch Gladbach."),
            "pickupPostcode"));
  }

  @Test
  void approvalRequiresAnUnconditionalInstruction() {
    for (String reject :
        java.util.List.of(
            "Please book it if the price falls.",
            "Please book it.\n\nBest regards,\nSara\nPS: Please hold until Monday.",
            "Can you book it?",
            "Please hold.\n\n> Please book it.",
            "Please book it subject to confirmation."))
      assertFalse(ai.aeiou.forwarder.domain.BookingApproval.accepted(reject), reject);
    for (String signoff :
        java.util.List.of(
            "Thanks a lot and have a lovely day!",
            "Cheers,",
            "Viele Gruesse / Many greetings",
            "Sara\nExample Ltd"))
      assertTrue(
          ai.aeiou.forwarder.domain.BookingApproval.accepted(
              "Thank you for your offer. That works for us, please go ahead and book it.\n\n"
                  + signoff));
  }

  @Test
  void piecesAndCorrectionsDoNotDoubleCountTotals() {
    var facts =
        ShipmentFacts.extract(
            "Bitte günstigste Option MUC nach LAX. 2 Stück je 120 × 80 × 100 cm, 150 kg gesamt.\n"
                + "> 900 kg");
    assertEquals("150", str(facts, "grossKg"));
    assertEquals("1.92", str(facts, "volumeCbm"));
    assertEquals("CHEAPEST", str(facts, "objective"));
    assertEquals("de", str(facts, "language"));
    facts = ShipmentFacts.extract("MUC to LAX 2 pieces each 120 x 80 x 100 cm, 150 kg each");
    assertEquals("300", str(facts, "grossKg"));
    facts = ShipmentFacts.extract("10 kg per piece or 20 kg, 1 cbm");
    assertTrue(facts.has("grossAlternatives"));
    assertFalse(facts.has("grossKg"));
    assertEquals("4.5359237", str(ShipmentFacts.extract("10 lb gross"), "grossKg"));
    assertEquals(
        "1234.50",
        new java.math.BigDecimal(str(ShipmentFacts.extract("1.234,50 kg gesamt"), "grossKg"))
            .setScale(2)
            .toPlainString());
  }

  @Test
  void shadowWindowAvoidsHindsightAndCensorBias() {
    Instant at = Instant.parse("2026-11-21T15:00:00Z");
    List<BidTiming.Observation> rows = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      Instant start = at.minusSeconds(1200 + i);
      rows.add(new BidTiming.Observation("A", start, at, start.plusSeconds(95), true));
    }
    rows.add(new BidTiming.Observation("A", at.minusSeconds(30), at, null, false));
    rows.add(
        new BidTiming.Observation(
            "A", at.plusSeconds(6), at.plusSeconds(500), at.plusSeconds(105), true));
    var p = BidTiming.estimate(rows, "A", at, 90, 15, 200);
    assertEquals(12, p.samples());
    assertTrue(p.waitForBid());
    assertEquals(1.0, p.probability());
    assertFalse(BidTiming.estimate(rows, "A", at, 90, 15, 20).waitForBid());
  }

  @Test
  void bidBalanceAndCustomerObjectiveAreIndependent() {
    var now = Instant.EPOCH;
    var values =
        List.<com.fasterxml.jackson.databind.JsonNode>of(
            candidate("slow", "BID", "200", 6),
            candidate("fast", "BID", "400", 2),
            candidate("balanced", "BID", "240", 3),
            candidate("sheet", "TARIFF", "220", 5));
    assertEquals("sheet", str(Pricing.choose(values, "CHEAPEST", now, 1, "").orElseThrow(), "id"));
    assertEquals(
        "balanced", str(Pricing.choose(values, "FASTEST", now, 1, "").orElseThrow(), "id"));
    assertTrue(Pricing.choose(values, "CHEAPEST", now, 2, "").isEmpty());
  }

  @Test
  void classifiesCustomerHandoverAndPreservesPostcode() {
    var airport =
        ShipmentFacts.extract(
            "Fastest option from DUS to JFK. Ready on 2026-11-27, and we will deliver it to DUS"
                + " ourselves. 1 piece, 1540 kg, 5.90 cbm.");
    assertEquals("AIRPORT_TO_AIRPORT", str(airport, "scope"));
    assertEquals("2026-11-27", str(airport, "readyDate"));
    var pickup =
        ShipmentFacts.extract(
            "Fastest freight option to JFK. Ready for pickup at our site in 61018 (Bad Homburg),"
                + " Germany, on 2026-11-25. 6 pieces, 1233 kg, 10.78 cbm.");
    assertEquals("DOOR_TO_PORT", str(pickup, "scope"));
    assertEquals("JFK", str(pickup, "destination"));
    assertEquals("61018", str(pickup, "pickupPostcode"));
    assertEquals("2026-11-25", str(pickup, "readyDate"));
    assertEquals(6, pickup.path("pieces").asInt());
  }

  @Test
  void ranksPickupVariantsForTheSameAirlineBidInHours() {
    var fast =
        (com.fasterxml.jackson.databind.node.ObjectNode) candidate("express", "BID", "320", 3);
    var cheap =
        (com.fasterxml.jackson.databind.node.ObjectNode) candidate("regular", "BID", "220", 3);
    fast.put("airBidId", "same").put("transitHours", 60);
    cheap.put("airBidId", "same").put("transitHours", 67);
    var values = List.<com.fasterxml.jackson.databind.JsonNode>of(cheap, fast);
    assertEquals(
        "express",
        str(Pricing.choose(values, "FASTEST", Instant.EPOCH, 1, "").orElseThrow(), "id"));
    assertEquals(
        "regular",
        str(Pricing.choose(values, "CHEAPEST", Instant.EPOCH, 1, "").orElseThrow(), "id"));
  }

  @Test
  void consolidatesAllLotsWithGrossAndPerPieceUnits() {
    for (var fixture :
        List.of(
            List.of(
                "Lot 1: 1 piece, gross weight 618 kg, volume 0.74 cbm\n"
                    + "Lot 2: 1 piece, gross weight 380 kg, volume 1.65 cbm",
                "998",
                "2.39",
                "2"),
            List.of(
                "Lot 1: 9 pieces, 345 kg gross, 0.58 cbm\nLot 2: 2 pieces, 141 kg gross, 2.04 cbm",
                "486",
                "2.62",
                "11"),
            List.of(
                "Lot 1: 4 drums, together 730 kg gross (642 kg net), together 1.68 cbm\n"
                    + "Lot 2: 4 cartons, each 48 kg gross (41 kg net), each 110 x 110 x 52 cm\n"
                    + "Lot 3: 5 cases, each 206 kg gross (191 kg net), each 120 x 100 x 8 cm",
                "1952",
                "4.6768",
                "13"))) {
      var facts = ShipmentFacts.extract(fixture.getFirst());
      assertFalse(facts.get("lotManifestIncomplete").asBoolean());
      assertEquals(fixture.get(1), str(facts, "grossKg"));
      assertEquals(fixture.get(2), str(facts, "volumeCbm"));
      assertEquals(fixture.get(3), str(facts, "pieces"));
    }
    var partial =
        ShipmentFacts.extract("Lot 1: 9 pieces, 345 kg gross, 0.58 cbm\nLot 2: pending details");
    assertTrue(partial.get("lotManifestIncomplete").asBoolean());
    assertFalse(partial.has("grossKg"));
    assertFalse(partial.has("volumeCbm"));
  }

  private com.fasterxml.jackson.databind.JsonNode candidate(
      String id, String source, String total, int days) {
    return obj(
        "id",
        id,
        "provider",
        id,
        "source",
        source,
        "eligible",
        true,
        "comparisonTotal",
        obj("amount", total, "currency", "EUR"),
        "transitDays",
        days,
        "requestRevision",
        1);
  }
}
