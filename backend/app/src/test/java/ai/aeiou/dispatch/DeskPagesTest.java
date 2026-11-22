package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeskPagesTest {
  private static final String INBOX = """
      [
        {"id": 9, "kind": "request", "thread_id": 9, "subject": "RFQ FRA to JFK", "sender": "a@x.mail", "stage": "review"},
        {"id": 8, "kind": "follow-up", "thread_id": 5, "root": 5, "subject": "Re: RFQ", "sender": "b@x.mail"},
        {"id": 7, "kind": "request", "thread_id": 7, "subject": "RFQ MUC to SIN", "sender": "c@x.mail", "stage": "review", "delivery": "sent"},
        {"id": 6, "kind": "carrier", "thread_id": 6, "subject": "Rates", "sender": "d@x.mail"},
        {"id": 5, "kind": "request", "thread_id": 5, "subject": null, "sender": "b@x.mail", "stage": "quoted", "delivery": "sent",
         "fields": {"origin": "HAM", "destination": "JFK"}}
      ]
      """;

  @Test
  void queueScopesMatchTheDeskAndPagesWalkTheWholeList() throws Exception {
    var inbox = new ObjectMapper().readTree(INBOX);
    var attention = DeskPages.queue(inbox, "attention", null, null, 50);
    assertEquals(List.of(9L), ids(attention), "in review and not sent; #7 was already sent");
    assertEquals(Map.of("attention", 1, "requests", 3), attention.get("counts"));
    assertEquals(List.of(9L, 7L, 5L), ids(DeskPages.queue(inbox, "requests", null, null, 50)));
    assertEquals(List.of(5L), ids(DeskPages.queue(inbox, "requests", "ham", null, 50)), "search covers the lane");
    // JavaScript turned a null subject into "null", so the desk's search matched it too.
    assertEquals(List.of(5L), ids(DeskPages.queue(inbox, "all", "null", null, 50)));

    var walked = new ArrayList<Long>();
    Long before = null;
    do {
      var page = DeskPages.queue(inbox, "all", null, before, 2);
      walked.addAll(ids(page));
      before = (Long) page.get("next_before");
    } while (before != null);
    assertEquals(List.of(9L, 8L, 7L, 6L, 5L), walked);
    @SuppressWarnings("unchecked") var threads = (Map<String, List<?>>) DeskPages.queue(inbox, "requests", null, null, 50).get("threads");
    assertEquals(2, threads.get("5").size(), "a row carries its whole thread");
  }

  @Test
  void textSortsLikeTheBrowserBlanksLastInBothDirections() {
    assertTrue(DeskSummary.localeCompare("On hold · documents", "one of two") < 0, "space before letters");
    assertTrue(DeskSummary.localeCompare("No customer reply", "No PDF offer") < 0, "case-insensitive first");
    assertTrue(DeskSummary.localeCompare("a", "A") < 0, "lowercase first on a tie");
    assertEquals(0, DeskSummary.localeCompare("Pricing", "Pricing"));

    var deal = (java.util.function.Function<Double, DeskSummary.Deal>) w -> new DeskSummary.Deal(1, "", "", null, null, w,
        null, false, null, null, null, null, null, null, false, null, null, null, null,
        new DeskSummary.Result("open", "pricing", "Pricing", "accent"));
    var rows = new ArrayList<>(List.of(deal.apply(null), deal.apply(2.0), deal.apply(1.0)));
    rows.sort(DeskPages.order(DeskSummary.Deal::weightKg, 1));
    assertEquals(java.util.Arrays.asList(1.0, 2.0, null), rows.stream().map(DeskSummary.Deal::weightKg).toList());
    rows.sort(DeskPages.order(DeskSummary.Deal::weightKg, -1));
    assertEquals(java.util.Arrays.asList(2.0, 1.0, null), rows.stream().map(DeskSummary.Deal::weightKg).toList());
  }

  @Test
  void bookingsTableListsSeparatelyBookedShipmentsAndFindsThemByReference() throws Exception {
    var deals = DeskSummary.deals(new ObjectMapper().readTree(DeskSummaryTest.SEPARATE),
        java.time.Instant.parse("2026-11-21T16:00:00Z").toEpochMilli());
    var page = DeskPages.shipments(deals, "bookings", null, null, null, null, -1, 50, 0);
    assertEquals(2, page.get("total"));
    var found = DeskPages.shipments(deals, "bookings", null, null, "bk-w85wv9z9", null, -1, 50, 0);
    assertEquals(1, found.get("total"));
    @SuppressWarnings("unchecked") var row = ((List<Map<String, Object>>) found.get("rows")).getFirst();
    assertEquals(58757L, row.get("id"));
    assertEquals("Booked 1/2", ((DeskSummary.Result) row.get("result")).label());
    assertEquals(0, DeskPages.shipments(deals, "pipeline", null, null, null, null, -1, 50, 0).get("total"),
        "a partly booked request is won, not open");
  }

  @SuppressWarnings("unchecked")
  private static List<Long> ids(Map<String, Object> page) {
    return ((List<com.fasterxml.jackson.databind.JsonNode>) page.get("messages")).stream().map(m -> m.path("id").asLong()).toList();
  }
}
