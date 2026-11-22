package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeskSummaryTest {
  private static final Instant NOW = Instant.parse("2026-11-21T16:00:00Z");

  /** Three requests: booked, quoted with alternatives, and one that expired without a quote. */
  private static final String INBOX = """
      [
        {"id": 30, "kind": "request", "thread_id": 30, "sender": "nordlicht-audio@hackloop.mail",
         "stage": "booked", "received": "2026-11-21T15:40:00Z", "delivery": "sent",
         "fields": {"destination": "JFK"},
         "outgoing": [{"kind": "quote", "state": "sent", "time": "2026-11-21T15:40:30Z"}],
         "proposal": {"offer": {"total_eur": "1200.00", "source": "portal"}, "pickup_eur": 100,
                      "alternatives": [{"total_eur": 1200}, {"total_eur": "1350.5"}, {"total_eur": null}]},
         "booking": {"booking_reference": "BK-1", "price": "1150.00"},
         "board_result": {"verdict": "accepted", "round_no": 2, "points": {"accuracy": 45, "cost": 39.9, "time": 15, "tracking": 10}}},
        {"id": 31, "kind": "follow-up", "thread_id": 30, "received": "2026-11-21T15:42:00Z", "body": "Please book it."},
        {"id": 20, "kind": "request", "thread_id": 20, "sender": "nordlicht-audio@hackloop.mail",
         "stage": "quoted", "received": "2026-11-21T15:30:00Z",
         "fields": {"destination": "JFK"},
         "outgoing": [{"kind": "quote", "state": "sent", "time": "2026-11-21T15:32:00Z"}],
         "proposal": {"offer": {"total_eur": 900, "source": "email bid"}},
         "board_result": {"verdict": "rejected", "reason": "too_high", "round_no": 1, "points": {}}},
        {"id": 10, "kind": "request", "thread_id": 10, "sender": "auer-maschinenbau@hackloop.mail",
         "stage": "new", "received": "2026-11-21T15:00:00Z", "fields": {"destination": "SGN"}, "outgoing": []}
      ]
      """;

  @Test
  void overviewCountsOutcomesQuoteTimesAndSavings() throws Exception {
    var s = DeskSummary.of(new ObjectMapper().readTree(INBOX), NOW);
    @SuppressWarnings("unchecked") var overview = (Map<String, Object>) s.get("overview");
    assertEquals(3, overview.get("requests"));
    assertEquals(2, overview.get("quoted"));
    assertEquals(1, overview.get("booked"));
    assertEquals(1, overview.get("expired"), "no quote 60 minutes after arrival");
    assertEquals(75L, overview.get("medianQuoteSeconds"), "median of 30 s and 120 s");
    assertEquals(1.0, overview.get("onTimeRate"));
    // JavaScript's Number(null) is 0, so a null alternative counts as the cheapest: 1200 - 0.
    assertEquals(1200L, overview.get("savedVsNext"));
    assertEquals(List.of(Map.of("code", "JFK", "n", 2), Map.of("code", "SGN", "n", 1)), overview.get("byDestination"));
  }

  @Test
  void shipmentsUseTheBookedAirPricePlusPickupAndGroupLossReasons() throws Exception {
    var s = DeskSummary.of(new ObjectMapper().readTree(INBOX), NOW);
    @SuppressWarnings("unchecked") var shipments = (Map<String, Object>) s.get("shipments");
    @SuppressWarnings("unchecked") var kpis = (Map<String, Object>) shipments.get("kpis");
    assertEquals(1250.0, kpis.get("bookedValue"), "booked air price 1150 plus quoted pickup 100");
    assertEquals(2100.0, kpis.get("quotedValue"));
    assertEquals(1.0 / 3, (double) kpis.get("conversion"), 1e-12);
    @SuppressWarnings("unchecked") var reasons = (List<Map<String, Object>>) shipments.get("reasons");
    assertEquals(List.of("Expired before quote", "Price too high"), reasons.stream().map(r -> r.get("label")).toList());
    @SuppressWarnings("unchecked") var customers = (List<Map<String, Object>>) shipments.get("customers");
    var nordlicht = customers.getFirst();
    assertEquals("Nordlicht Audio", nordlicht.get("name"));
    assertEquals(2, nordlicht.get("requests"));
    assertEquals(0.5, nordlicht.get("winRate"));
    assertEquals("JFK", nordlicht.get("topDestination"));
  }

  @Test
  void scoreAveragesCriteriaOverAcceptedRoundsOnly() throws Exception {
    var s = DeskSummary.of(new ObjectMapper().readTree(INBOX), NOW);
    @SuppressWarnings("unchecked") var score = (Map<String, Object>) s.get("score");
    assertEquals(109.9, (double) score.get("total"), 1e-9);
    assertEquals(1, score.get("accepted"));
    assertEquals(1, score.get("rejected"));
    @SuppressWarnings("unchecked") var rounds = (List<Map<String, Object>>) score.get("rounds");
    assertEquals(List.of(20L, 30L), rounds.stream().map(r -> r.get("id")).toList(), "ordered by round number");
    @SuppressWarnings("unchecked") var criteria = (List<Map<String, Object>>) score.get("criteria");
    assertEquals(45.0, criteria.getFirst().get("avg"));
    assertEquals(1L, criteria.getFirst().get("full"));
    @SuppressWarnings("unchecked") var losses = (List<Map<String, Object>>) score.get("losses");
    assertEquals("Price too high", losses.getFirst().get("label"));
  }

  /** Request 58757 as it went live: shipment 1 booked on its own leg, shipment 2 not, mail.booking null. */
  static final String SEPARATE = """
      [
        {"id": 58757, "kind": "request", "thread_id": 58757, "sender": "jonas@hackloop.mail",
         "stage": "sent", "received": "2026-11-21T15:40:00Z", "delivery": "sent", "booking": null, "proposal": null,
         "fields": {"origin": "MUC", "destination": "BKK", "weight_kg": 1190},
         "outgoing": [{"kind": "quote", "state": "sent", "time": "2026-11-21T15:41:00Z"}],
         "shipment_booking_state": "partly booked",
         "shipment_bookings": [
           {"number": 1, "origin": "MUC", "destination": "BKK", "booking_reference": "BK-W85WV9Z9",
            "team_reference": "AEIOU-58757-1", "airline": "Polaris Air Logistics", "airline_code": "PL",
            "price": "3260.60", "currency": "EUR", "status": "booked", "pickup_eur": 324.30, "total_eur": 3584.90},
           {"number": 2, "origin": "MUC", "destination": "SIN", "booking_reference": null, "status": null,
            "pickup_eur": 154.36, "total_eur": 881.61}
         ]},
        {"id": 58758, "kind": "request", "thread_id": 58758, "sender": "jonas@hackloop.mail",
         "stage": "sent", "received": "2026-11-21T15:45:00Z", "delivery": "sent",
         "fields": {"origin": "MUC", "destination": "BKK"},
         "shipment_booking_state": "booked",
         "shipment_bookings": [
           {"number": 1, "booking_reference": "BK-A", "team_reference": "AEIOU-58758-1", "airline": "Polaris Air Logistics",
            "price": "100", "currency": "EUR", "status": "booked", "pickup_eur": 10},
           {"number": 2, "booking_reference": "BK-B", "team_reference": "AEIOU-58758-2", "airline": "Kestrel Airways",
            "price": "200", "currency": "EUR", "status": "booked", "pickup_eur": null}
         ]}
      ]
      """;

  @Test
  void separatelyBookedShipmentsCountAsBookedWithTheShareNoted() throws Exception {
    var inbox = new ObjectMapper().readTree(SEPARATE);
    long now = NOW.toEpochMilli();
    assertEquals("booked", DeskSummary.outcomeOf(inbox.get(0), now), "one of two shipments booked");
    assertEquals("booked", DeskSummary.outcomeOf(inbox.get(1), now));
    @SuppressWarnings("unchecked") var overview = (Map<String, Object>) DeskSummary.of(inbox, NOW).get("overview");
    assertEquals(2, overview.get("booked"));

    var deals = DeskSummary.deals(inbox, now);
    var partly = deals.get(0);
    assertTrue(partly.booked());
    assertEquals(new DeskSummary.Result("won", "booked", "Booked 1/2", "ok"), partly.result());
    assertEquals("BK-W85WV9Z9", partly.booking().path("booking_reference").asText());
    assertEquals("AEIOU-58757-1", partly.booking().path("team_reference").asText());
    assertEquals("booked 1/2", partly.booking().path("status").asText());
    assertEquals("Polaris Air Logistics", partly.carrier());
    assertEquals(3584.90, partly.bookedAllIn(), 1e-9, "booked air price plus that shipment's pickup");

    var full = deals.get(1);
    assertEquals("Booked", full.result().label());
    assertEquals("BK-A · BK-B", full.booking().path("booking_reference").asText());
    assertEquals("booked", full.booking().path("status").asText());
    assertEquals("Polaris Air Logistics · Kestrel Airways", full.carrier());
    assertEquals(310.0, full.bookedAllIn(), 1e-9);

    @SuppressWarnings("unchecked") var shipments = (Map<String, Object>) DeskSummary.shipments(inbox, now);
    @SuppressWarnings("unchecked") var funnel = (List<Map<String, Object>>) shipments.get("funnel");
    assertEquals(2, funnel.getLast().get("n"));
  }
}
