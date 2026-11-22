package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Times representative ledger work over a copy of a real database. Runs only when BENCH_DB names a
 * SQLite file; the file is copied first, so the benchmark never writes to it.
 */
class LedgerBenchmarkTest {
  @TempDir Path directory;

  @Test
  void timesInboxAndWorkerLoopQueries() throws Exception {
    String source = System.getenv("BENCH_DB");
    Assumptions.assumeTrue(source != null && !source.isBlank(), "set BENCH_DB to run the benchmark");
    var copy = directory.resolve("bench.sqlite3");
    Files.copy(Path.of(source), copy);
    var ledger = new Ledger(copy.toString(), new ObjectMapper(), false);
    var db = ledger.jdbc();
    long request = db.queryForObject("SELECT COALESCE(MAX(id),0) FROM mail", Long.class);

    for (int i = 0; i < 3; i++) assertFalse(ledger.inbox().isEmpty());
    var inbox = new double[10];
    for (int i = 0; i < inbox.length; i++) {
      long start = System.nanoTime();
      ledger.inbox();
      inbox[i] = (System.nanoTime() - start) / 1e6;
    }

    // One worker tick does many tiny reads and a few writes, some in a transaction.
    int ops = 0;
    long start = System.nanoTime();
    for (int i = 0; i < 200; i++) {
      db.queryForObject("SELECT value FROM cursor WHERE id=1", Integer.class);
      ledger.delivery("bench:" + (i % 10));
      ledger.tenders(request);
      ledger.mail(request);
      db.update("INSERT INTO retry(action,after_epoch) VALUES(?,?) ON CONFLICT(action) DO UPDATE"
          + " SET after_epoch=excluded.after_epoch", "bench:" + (i % 10), i);
      ledger.transactions().executeWithoutResult(t -> {
        db.update("UPDATE retry SET after_epoch=after_epoch+1 WHERE action=?", "bench:0");
        db.queryForObject("SELECT after_epoch FROM retry WHERE action=?", Long.class, "bench:0");
      });
      ops += 7;
    }
    double loop = (System.nanoTime() - start) / 1e6;

    Arrays.sort(inbox);
    System.out.printf("BENCH inbox() rows=%d median=%.1fms min=%.1fms max=%.1fms%n",
        ledger.inbox().size(), inbox[inbox.length / 2], inbox[0], inbox[inbox.length - 1]);
    System.out.printf("BENCH worker-loop ops=%d total=%.1fms per-op=%.3fms%n", ops, loop, loop / ops);
  }
}
