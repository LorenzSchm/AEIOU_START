package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two writers against a throwaway Postgres: only one may write, a writer that cannot confirm its
 * lock pauses, a writer locked out by its own stale session takes the lock back without touching
 * another writer's session, and a writer whose SQLite is behind the mirror never takes the lock.
 */
class WriterLeaseTest {
  private static LocalPostgres server;
  @TempDir Path temp;
  private String url;
  private final MutableClock clock = new MutableClock();

  @BeforeAll
  static void startServer() throws Exception {
    server = LocalPostgres.start();
  }

  @AfterAll
  static void stopServer() throws Exception {
    if (server != null) server.close();
  }

  @BeforeEach
  void setUp() throws Exception {
    Assumptions.assumeTrue(server != null, "no local Postgres binaries");
    url = server.newDatabase();
  }

  @Test
  void onlyOneWriterHoldsTheLeaseAndTheOtherTakesOverOnRelease() throws Exception {
    var a = lease("a");
    var b = lease("b");
    assertTrue(a.mayWrite());
    assertFalse(b.mayWrite());
    b.renew();
    assertFalse(b.mayWrite());
    a.release();
    b.renew();
    assertTrue(b.mayWrite());
    assertFalse(a.mayWrite());
  }

  @Test
  void anUnconfirmedLeasePausesTheWriter() throws Exception {
    var a = lease("a");
    assertTrue(a.mayWrite());
    clock.advance(WriterLease.GRACE.plusSeconds(1));
    assertFalse(a.mayWrite(), "a lease not confirmed within the grace period must not write");
    a.renew();
    assertTrue(a.mayWrite());
  }

  @Test
  void aWriterWhoseSessionWasEndedPausesAndTheOtherWriterTakesOver() throws Exception {
    var a = lease("a");
    var b = lease("b");
    terminate(a.tag());
    b.renew();
    assertTrue(b.mayWrite());
    a.renew();
    assertFalse(a.mayWrite(), "the writer that lost its session must stay idle while another holds the lock");
  }

  @Test
  void aWriterLockedOutByItsOwnStaleSessionTakesTheLockBack() throws Exception {
    var a = lease("a");
    assertTrue(a.mayWrite());
    // A session the server still keeps for this writer, though the writer has given up on it.
    try (var stale = session(a.tag())) {
      a.release();
      take(stale);
      a.renew();
      assertTrue(a.mayWrite(), "the writer must end its own stale session and take the lock back");
    }
  }

  @Test
  void anotherWritersSessionIsNeverEnded() throws Exception {
    try (var other = session("aeiou-writer-someone-else")) {
      take(other);
      var a = lease("a");
      a.renew();
      assertFalse(a.mayWrite());
      assertTrue(other.isValid(2), "the other writer's lock session must survive");
    }
  }

  @Test
  void aWriterBehindThePostgresMirrorNeverTakesTheLock() throws Exception {
    try (var c = new Postgres(url).open(); var st = c.createStatement()) {
      st.execute("CREATE TABLE mail (id bigint PRIMARY KEY)");
      st.execute("CREATE TABLE deliveries (action text PRIMARY KEY, state text)");
      st.execute("INSERT INTO mail VALUES (1), (2), (3)");
      st.execute("INSERT INTO deliveries VALUES ('quote:1','sent'), ('quote:2','sent')");
    }
    var stale = lease("stale");
    assertFalse(stale.mayWrite(), "an empty database must not become the writer");

    var current = ledger("current");
    for (int i = 1; i <= 3; i++)
      current.jdbc().update("INSERT INTO mail(id,root,sender,subject,body,received,kind) VALUES(?,?,?,?,?,?,?)",
          i, i, "c@example.com", "RFQ", "body", "2026-11-21T15:00:00Z", "request");
    current.jdbc().update("INSERT INTO deliveries(action,state,payload) VALUES('quote:1','sent','{}')");
    current.jdbc().update("INSERT INTO deliveries(action,state,payload) VALUES('quote:2','sent','{}')");
    current.jdbc().update("INSERT INTO deliveries(action,state,payload) VALUES('quote:3','pending','{}')");
    var writer = new WriterLease(false, url, current::jdbc, clock, false);
    assertTrue(writer.mayWrite(), "a database level with the mirror may write");
  }

  @Test
  void theMirrorDropsRemoteRowsTheNewWriterDoesNotHave() throws Exception {
    var writer = ledger("w");
    writer.jdbc().update("INSERT INTO deliveries(action,state,payload) VALUES('quote:1','sent','{}')");
    new PostgresMirror(writer.jdbc(), url).sync();
    try (var c = new Postgres(url).open(); var st = c.createStatement()) {
      st.execute("INSERT INTO deliveries(action,state,payload,sqlite_rowid) VALUES ('quote:9','pending','{}',9)");
    }
    var lease = new WriterLease(false, url, writer::jdbc, clock, false);
    var mirror = new PostgresMirror(writer.jdbc(), url, lease);
    mirror.sync();
    assertNull(mirror.error());
    try (var c = new Postgres(url).open(); var st = c.createStatement();
        var rs = st.executeQuery("SELECT string_agg(action, ',' ORDER BY action) FROM deliveries")) {
      rs.next();
      assertEquals("quote:1", rs.getString(1));
    }
  }

  private WriterLease lease(String name) throws Exception {
    var ledger = ledger(name);
    return new WriterLease(false, url, ledger::jdbc, clock, false);
  }

  private Ledger ledger(String name) throws Exception {
    return new Ledger(temp.resolve(name + "/dispatch.sqlite3").toString(), new ObjectMapper());
  }

  private Connection session(String applicationName) throws SQLException {
    var c = new Postgres(url).open();
    try (var st = c.createStatement()) {
      st.execute("SET application_name = '" + applicationName + "'");
    }
    return c;
  }

  private static void take(Connection c) throws SQLException {
    try (var st = c.createStatement(); var rs = st.executeQuery("SELECT pg_try_advisory_lock(" + WriterLease.LOCK + ")")) {
      rs.next();
      assertTrue(rs.getBoolean(1));
    }
  }

  private void terminate(String applicationName) throws SQLException {
    try (var c = new Postgres(url).open();
        var st = c.prepareStatement("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name=?")) {
      st.setString(1, applicationName);
      st.execute();
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicLong millis = new AtomicLong(1_795_000_000_000L);

    void advance(Duration d) {
      millis.addAndGet(d.toMillis());
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }
  }
}
