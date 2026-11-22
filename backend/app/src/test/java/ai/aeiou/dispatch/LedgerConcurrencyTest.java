package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** The ledger's pooled SQLite connections under the worker's mix of threads. */
class LedgerConcurrencyTest {
  @TempDir Path directory;
  private Ledger ledger;

  @BeforeEach
  void open() throws Exception {
    ledger = new Ledger(directory.resolve("concurrency.sqlite3").toString(), new ObjectMapper());
    ledger.jdbc().execute("CREATE TABLE pair(id INTEGER PRIMARY KEY, a INTEGER NOT NULL, b INTEGER NOT NULL)");
    ledger.jdbc().update("INSERT INTO pair VALUES(1,0,0)");
    ledger.jdbc().execute("CREATE TABLE event(id INTEGER PRIMARY KEY AUTOINCREMENT, writer INTEGER, n INTEGER)");
  }

  @AfterEach
  void close() {
    ledger.close();
  }

  @Test
  void everyPooledConnectionKeepsTheBusyTimeoutAndWal() throws Exception {
    var db = ledger.jdbc();
    int threads = 12;
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(threads);
    var results = new ArrayList<Future<List<Object>>>();
    // Holding connections in parallel transactions forces the pool to open all of them.
    for (int i = 0; i < threads; i++)
      results.add(pool.submit(() -> {
        start.await();
        return ledger.transactions().execute(t -> {
          sleep(50);
          return List.<Object>of(
              db.queryForObject("PRAGMA busy_timeout", Integer.class),
              db.queryForObject("PRAGMA journal_mode", String.class));
        });
      }));
    start.countDown();
    for (var r : results) assertEquals(List.of(10_000, "wal"), r.get(30, TimeUnit.SECONDS));
    pool.shutdown();
  }

  @Test
  void concurrentWritersLoseNothingAndReadersNeverSeeHalfATransaction() throws Exception {
    var db = ledger.jdbc();
    int writers = 6, readers = 6, rounds = 150;
    var failures = new ConcurrentLinkedQueue<Throwable>();
    var done = new CountDownLatch(writers);
    var torn = new AtomicInteger();
    var reads = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(writers + readers);
    for (int w = 0; w < writers; w++) {
      int writer = w;
      pool.execute(() -> {
        try {
          for (int n = 0; n < rounds; n++) {
            int round = n;
            // Read, compute in Java, then write: a lost update would show as a smaller total.
            ledger.transactions().executeWithoutResult(t -> {
              int a = db.queryForObject("SELECT a FROM pair WHERE id=1", Integer.class);
              db.update("UPDATE pair SET a=? WHERE id=1", a + 1);
              db.update("INSERT INTO event(writer,n) VALUES(?,?)", writer, round);
              db.update("UPDATE pair SET b=b+1 WHERE id=1");
            });
            if (n % 10 == 0)
              db.update("INSERT INTO retry(action,after_epoch) VALUES(?,?) ON CONFLICT(action)"
                  + " DO UPDATE SET after_epoch=excluded.after_epoch", "w" + writer, n);
          }
        } catch (Throwable ex) {
          failures.add(ex);
        } finally {
          done.countDown();
        }
      });
    }
    for (int r = 0; r < readers; r++)
      pool.execute(() -> {
        try {
          while (done.getCount() > 0) {
            var row = db.queryForMap("SELECT a,b FROM pair WHERE id=1");
            if (!Objects.equals(row.get("a"), row.get("b"))) torn.incrementAndGet();
            ledger.inbox();
            reads.incrementAndGet();
          }
        } catch (Throwable ex) {
          failures.add(ex);
        }
      });
    pool.shutdown();
    assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));
    assertEquals(List.of(), failures.stream().map(Throwable::toString).toList(), "no SQLITE_BUSY or other errors");
    int total = writers * rounds;
    assertEquals(Map.of("a", total, "b", total), db.queryForMap("SELECT a,b FROM pair WHERE id=1"));
    assertEquals(total, db.queryForObject("SELECT COUNT(*) FROM event", Integer.class));
    assertEquals(0, torn.get(), "a reader saw a transaction half applied");
    assertTrue(reads.get() > 0);
  }

  @Test
  void aLongReplicaStyleRewriteDoesNotBlockReadersOrShowAnEmptyTable() throws Exception {
    var db = ledger.jdbc();
    for (int i = 1; i <= 200; i++) db.update("INSERT INTO event(writer,n) VALUES(0,?)", i);
    var inTransaction = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var replica = Executors.newSingleThreadExecutor();
    // Like PostgresReplica: delete and refill every row in one transaction from its own thread.
    var rewrite = replica.submit(() -> ledger.transactions().executeWithoutResult(t -> {
      db.update("DELETE FROM event");
      for (int i = 1; i <= 300; i++) db.update("INSERT INTO event(writer,n) VALUES(1,?)", i);
      inTransaction.countDown();
      await(release);
    }));
    assertTrue(inTransaction.await(10, TimeUnit.SECONDS));
    var readers = Executors.newFixedThreadPool(4);
    var counts = new ArrayList<Future<Integer>>();
    long started = System.nanoTime();
    for (int i = 0; i < 8; i++)
      counts.add(readers.submit(() -> db.queryForObject("SELECT COUNT(*) FROM event", Integer.class)));
    for (var c : counts) assertEquals(200, c.get(5, TimeUnit.SECONDS), "readers see the last committed state");
    assertTrue((System.nanoTime() - started) / 1e9 < 2, "readers do not wait for the writer");
    release.countDown();
    rewrite.get(10, TimeUnit.SECONDS);
    assertEquals(300, db.queryForObject("SELECT COUNT(*) FROM event", Integer.class));
    replica.shutdown();
    readers.shutdown();
  }

  @Test
  void aFailedTransactionRollsBackAndItsConnectionIsReusableAfterwards() {
    var db = ledger.jdbc();
    for (int i = 0; i < 20; i++) {
      assertThrows(IllegalStateException.class, () -> ledger.transactions().executeWithoutResult(t -> {
        db.update("UPDATE pair SET a=a+1 WHERE id=1");
        throw new IllegalStateException("rollback");
      }));
    }
    assertEquals(0, db.queryForObject("SELECT a FROM pair WHERE id=1", Integer.class));
    // Autocommit is restored on every returned connection: plain updates are durable at once.
    db.update("UPDATE pair SET b=7 WHERE id=1");
    assertEquals(7, db.queryForObject("SELECT b FROM pair WHERE id=1", Integer.class));
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
