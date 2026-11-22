package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboxPagingTest {
  @TempDir Path directory;

  @Test
  void pagesCoverTheInboxOnceNewestFirstAndEndWithAShortPage() throws Exception {
    var ledger = new Ledger(directory.resolve("paging.sqlite3").toString(), new ObjectMapper());
    var catalog = new DispatchApplication().catalog(null);
    var now = Instant.parse("2026-11-21T15:00:00Z");
    var mails = new ArrayList<Map<String, Object>>();
    for (long id = 1; id <= 7; id++)
      mails.add(Map.of("id", id, "from", "buyer" + id + "@example.invalid", "subject", "Hello " + id,
          "body", "Just a note.", "sent_at", now.toString()));
    ledger.ingest(Map.of("messages", mails, "next_since_id", 7), catalog, now);

    var all = ledger.inbox().stream().map(m -> Ledger.id(m.get("id"))).toList();
    assertEquals(List.of(7L, 6L, 5L, 4L, 3L, 2L, 1L), all);

    var seen = new ArrayList<Long>();
    Long before = null;
    int pages = 0;
    while (true) {
      var page = ledger.inboxPage(before, 3);
      pages++;
      page.forEach(m -> seen.add(Ledger.id(m.get("id"))));
      if (page.size() < 3) break;
      before = Ledger.id(page.getLast().get("id"));
    }
    assertEquals(all, seen, "pages follow inbox order and never repeat or skip a message");
    assertEquals(3, pages);
    assertEquals(ledger.inbox().getFirst(), ledger.inboxPage(null, 1).getFirst(),
        "a paged message is built exactly like the full inbox's");
  }

  @Test
  void theDiffNamesOnlyNewChangedAndRemovedMessages() {
    var before = new LinkedHashMap<Long, String>(Map.of(1L, "{\"stage\":\"new\"}", 2L, "{\"stage\":\"quoted\"}",
        3L, "{\"stage\":\"booked\"}"));
    var after = new LinkedHashMap<Long, String>(Map.of(1L, "{\"stage\":\"priced\"}", 2L, "{\"stage\":\"quoted\"}",
        4L, "{\"stage\":\"new\"}"));
    var changes = InboxDiff.between(before, after);
    assertEquals(List.of(1L, 4L), changes.upserted().stream().sorted().toList());
    assertEquals(List.of(3L), changes.removed());
    assertTrue(InboxDiff.between(after, after).isEmpty());
  }
}
