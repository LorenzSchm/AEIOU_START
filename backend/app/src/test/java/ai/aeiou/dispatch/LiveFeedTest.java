package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The live feed's cheap change detection, shared inbox, thread lookup and compact desk fields. */
class LiveFeedTest {
  @TempDir Path directory;
  private static final Instant NOW = Instant.parse("2026-11-21T15:00:00Z");

  private Ledger ledger(int mails) throws Exception {
    var ledger = new Ledger(directory.resolve("feed.sqlite3").toString(), new ObjectMapper());
    var list = new ArrayList<Map<String, Object>>();
    for (long id = 1; id <= mails; id++)
      list.add(Map.of("id", id, "from", "buyer" + id + "@example.invalid", "subject", "Hello " + id,
          "body", "Just a note.", "sent_at", NOW.toString()));
    ledger.ingest(Map.of("messages", list, "next_since_id", mails), new DispatchApplication().catalog(null), NOW);
    return ledger;
  }

  @Test
  void theFingerprintMovesOnlyWhenInboxTablesChange() throws Exception {
    var ledger = ledger(3);
    try (var watch = LedgerWatch.of(ledger)) {
      var first = watch.fingerprint();
      assertTrue(first.isPresent(), "a file database can be watched");
      assertEquals(first, watch.fingerprint());
      assertEquals(1, watch.fingerprints(), "no commit, no rehash");

      ledger.jdbc().update("UPDATE cursor SET value=value+1");
      assertEquals(first, watch.fingerprint(), "a commit to an unrelated table keeps the fingerprint");
      assertEquals(2, watch.fingerprints(), "but the commit is noticed");

      ledger.jdbc().update("UPDATE mail SET stage='review' WHERE id=2");
      var changed = watch.fingerprint();
      assertNotEquals(first, changed);

      // Rewriting identical rows, as the read-only replica does every few seconds, is not a change.
      ledger.transactions().executeWithoutResult(t -> {
        var rows = ledger.jdbc().queryForList("SELECT * FROM wave_case ORDER BY rowid");
        ledger.jdbc().update("DELETE FROM wave_case");
        for (var row : rows)
          ledger.jdbc().update("INSERT INTO wave_case(id,reason,offers,queried,opened,operator,customer_reply,portal_version)"
              + " VALUES(?,?,?,?,?,?,?,?)", row.get("id"), row.get("reason"), row.get("offers"), row.get("queried"),
              row.get("opened"), row.get("operator"), row.get("customer_reply"), row.get("portal_version"));
      });
      assertEquals(changed, watch.fingerprint());

      ledger.jdbc().update("INSERT INTO deliveries(action,state) VALUES('reply:1','sent')");
      assertNotEquals(changed, watch.fingerprint(), "an outgoing mail changes the inbox");
    }
  }

  @Test
  void anUnwatchableDatabaseRebuildsEveryTime() {
    var watch = new LedgerWatch("");
    assertTrue(watch.fingerprint().isEmpty());
  }

  @Test
  void theCacheBuildsOncePerChangeAndMatchesTheLedger() throws Exception {
    var ledger = ledger(4);
    var cache = new InboxCache(ledger, new ObjectMapper());
    try {
      var a = cache.current();
      assertSame(a, cache.current());
      assertEquals(1, cache.builds());
      assertEquals(ledger.inbox(), a.messages());

      ledger.jdbc().update("UPDATE mail SET stage='declined' WHERE id=3");
      var b = cache.current();
      assertNotSame(a, b);
      assertEquals(2, cache.builds());
      assertEquals(ledger.inbox(), b.messages());
      assertEquals(new ObjectMapper().valueToTree(b.messages()), b.tree());
      assertThrows(UnsupportedOperationException.class, () -> b.messages().clear(), "a shared snapshot is read-only");
    } finally {
      cache.stop();
    }
  }

  @Test
  void aThreadHoldsTheMessageItsRootAndItsReplies() {
    List<Map<String, Object>> inbox = List.of(
        Map.of("id", 12L, "thread_id", 10L, "root", 10L),
        Map.of("id", 11L, "thread_id", 11L),
        Map.of("id", 10L, "thread_id", 10L),
        Map.of("id", 9L, "thread_id", 10L, "root", 10L));
    var ids = DispatchController.threadOf(inbox, inbox.get(3)).stream().map(m -> m.get("id")).toList();
    assertEquals(List.of(12L, 10L, 9L), ids, "inbox order, the root included");
    assertEquals(List.of(11L), DispatchController.threadOf(inbox, inbox.get(1)).stream().map(m -> m.get("id")).toList());
  }

  @Test
  void expiriesMarkTheFirstMillisecondAfterTheWindow() throws Exception {
    var messages = new ObjectMapper().readTree("""
        [{"id": 2, "kind": "request", "received": "2026-11-21T15:06:00Z"},
         {"id": 1, "kind": "request", "received": "2026-11-21T15:00:00Z"},
         {"id": 3, "kind": "carrier", "received": "2026-11-21T15:01:00Z"}]
        """);
    long[] at = DeskSummary.expiries(messages);
    long first = Instant.parse("2026-11-21T15:06:00.001Z").toEpochMilli();
    assertArrayEquals(new long[] {first, first + 360_000}, at);
    assertFalse(DeskSummary.expiresBetween(at, first - 10_000, first - 1));
    assertTrue(DeskSummary.expiresBetween(at, first - 1, first));
    assertFalse(DeskSummary.expiresBetween(at, first, first + 1_000), "an expiry already passed is not crossed again");
    assertTrue(DeskSummary.expiresBetween(at, first, first + 360_000));
    // The outcome indeed flips exactly there.
    var request = messages.get(1);
    assertEquals("in progress", DeskSummary.outcomeOf(request, first - 1));
    assertEquals("expired", DeskSummary.outcomeOf(request, first));
  }

  @Test
  void theDeskFieldsCountAttentionAndListRoutes() throws Exception {
    var messages = new ObjectMapper().readTree("""
        [{"id": 5, "kind": "request", "stage": "review", "received": "2026-11-21T15:00:00Z", "subject": "A",
          "review_reason": "Check the pickup", "fields": {"origin": "FRA", "destination": "JFK", "weight_kg": 10},
          "proposal": {"offer": {"airline": "Lufthansa", "total_eur": 900, "days": 2}}},
         {"id": 4, "kind": "request", "stage": "collecting", "delivery": "sent", "received": "2026-11-21T14:00:00Z",
          "subject": "B", "fields": {"origin": "MUC", "destination": ""}},
         {"id": 3, "kind": "carrier", "stage": "review", "fields": {"origin": "FRA", "destination": "JFK"}}]
        """);
    var desk = DeskSummary.desk(messages, NOW.toEpochMilli());
    assertEquals(3, desk.get("messages"));
    assertEquals(1, desk.get("attention"));
    @SuppressWarnings("unchecked") var items = (List<Map<String, Object>>) desk.get("attention_items");
    assertEquals(5L, items.getFirst().get("id"));
    assertEquals("Check the pickup", new ObjectMapper().valueToTree(items.getFirst()).path("review_reason").asText());
    @SuppressWarnings("unchecked") var routes = (List<Map<String, Object>>) desk.get("routes");
    assertEquals(1, routes.size(), "requests with both airports only");
    assertEquals("attention", routes.getFirst().get("state"));
    assertEquals("Lufthansa", new ObjectMapper().valueToTree(routes.getFirst()).path("airline").asText());
  }

  @Test
  void aSummaryPatchCarriesOnlyChangedEntries() {
    var json = new ObjectMapper();
    java.util.function.Function<Object, String> write = v -> {
      try {
        return json.writeValueAsString(v);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    };
    Map<String, Object> before = Map.of("computed_at", "t1", "overview", Map.of("requests", 1, "quoted", 0),
        "desk", Map.of("messages", 10, "routes", List.of(Map.of("id", 1))));
    Map<String, Object> after = Map.of("computed_at", "t2", "overview", Map.of("requests", 1, "quoted", 1),
        "desk", Map.of("messages", 11, "routes", List.of(Map.of("id", 1))));
    var patch = InboxFeed.patch(before, after, write);
    assertEquals(Map.of("computed_at", "t2", "overview", Map.of("quoted", 1), "desk", Map.of("messages", 11)), patch);
    assertEquals(Map.of("computed_at", "t2"), InboxFeed.patch(after, Map.of("computed_at", "t2", "overview", after.get("overview"),
        "desk", after.get("desk")), write));
  }

  @Test
  void aChangedRowListSendsTheOrderAndOnlyChangedRows() {
    var json = new ObjectMapper();
    java.util.function.Function<Object, String> write = v -> {
      try {
        return json.writeValueAsString(v);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    };
    var rows = new ArrayList<Map<String, Object>>();
    for (long id = 1; id <= 20; id++) rows.add(Map.of("id", id, "state", "active", "label", "Air export " + id));
    var next = new ArrayList<Map<String, Object>>(rows);
    next.set(4, Map.of("id", 5L, "state", "quoted", "label", "Air export 5"));
    next.addFirst(Map.of("id", 21L, "state", "active", "label", "Air export 21"));
    @SuppressWarnings("unchecked") var out = (Map<String, Object>) InboxFeed.rows(rows, next, write);
    assertEquals(next.stream().map(r -> r.get("id")).toList(), out.get("$ids"));
    assertEquals(List.of(next.get(0), next.get(5)), out.get("$rows"));
    var duplicate = List.of(Map.of("id", 1L), Map.of("id", 1L));
    assertSame(duplicate, InboxFeed.rows(rows, duplicate, write), "rows without unique ids go whole");
    assertEquals(7, InboxFeed.rows(3, 7, write));
  }
}
