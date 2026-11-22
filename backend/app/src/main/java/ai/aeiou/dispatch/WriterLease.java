package ai.aeiou.dispatch;

import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Decides whether this process may talk to the live 5U API and write data. A read-only process
 * never may. With a Postgres database, writers compete for a session advisory lock so only one
 * worker ingests at a time.
 *
 * <p>The lease fails safe. A writer may write only while it has confirmed, within the last
 * {@link #GRACE}, that its own session still holds the lock. When Postgres cannot be reached the
 * worker pauses instead of carrying on, because another writer may already hold the lock. The
 * lock session asks the server to detect a dead client within about 30 seconds, and a writer
 * that reconnects while its own earlier session still holds the lock ends that session (and only
 * its own) and takes the lock back, so a network blip never locks the only writer out.
 *
 * <p>A writer also refuses the lock while its local SQLite is behind Postgres (fewer messages or
 * deliveries). Postgres mirrors the writer's SQLite, so a legitimate writer is never behind it. An
 * empty or stale database must be restored before it may write: otherwise it would re-send work
 * that was already done and overwrite the newer mirror.
 */
@Component
class WriterLease {
  private static final Logger log = LoggerFactory.getLogger(WriterLease.class);
  // Arbitrary constant shared by every AEIOU backend that points at the same database.
  static final long LOCK = 0x4145494F55L;
  /** How long a confirmed lock stays usable. Renewal runs every 2 s with a 2 s query timeout. */
  static final Duration GRACE = Duration.ofSeconds(6);
  private static final String HELD = "SELECT 1 FROM pg_locks WHERE locktype='advisory' AND granted"
      + " AND pid=pg_backend_pid() AND ((classid::bigint<<32)|objid::bigint)=" + LOCK;
  private static final String HOLDER = "SELECT l.pid, a.application_name FROM pg_locks l"
      + " JOIN pg_stat_activity a USING (pid) WHERE l.locktype='advisory' AND l.granted"
      + " AND ((l.classid::bigint<<32)|l.objid::bigint)=" + LOCK;

  private final boolean readOnly;
  private final Postgres postgres;
  private final Supplier<JdbcTemplate> local;
  private final Clock clock;
  private final String tag = "aeiou-writer-" + UUID.randomUUID();
  private final ScheduledExecutorService renewal;
  private Connection lock;
  private volatile boolean allowed;
  private volatile long confirmedAt;
  private volatile long generation;
  private String behind;
  private boolean idle;
  private String failure;

  WriterLease(boolean readOnly, String url) {
    this(readOnly, url, () -> null, Clock.systemUTC(), true);
  }

  @Autowired
  WriterLease(@Value("${dispatch.read-only}") boolean readOnly, @Value("${postgres.url}") String url,
      Ledger ledger) {
    this(readOnly, url, ledger::jdbc, Clock.systemUTC(), true);
  }

  WriterLease(boolean readOnly, String url, Supplier<JdbcTemplate> local, Clock clock, boolean renew) {
    this.readOnly = readOnly;
    this.postgres = readOnly ? null : Postgres.of(url);
    this.local = local;
    this.clock = clock;
    if (postgres == null) {
      renewal = null;
      return;
    }
    renew();
    if (!renew) {
      renewal = null;
      return;
    }
    if (allowed) log.info("Writer lease held. This worker ingests live data.");
    renewal = Executors.newSingleThreadScheduledExecutor(r -> {
      var thread = new Thread(r, "writer-lease");
      thread.setDaemon(true);
      return thread;
    });
    renewal.scheduleWithFixedDelay(this::renew, 2, 2, TimeUnit.SECONDS);
  }

  boolean readOnly() {
    return readOnly;
  }

  boolean mayWrite() {
    if (readOnly) return false;
    if (postgres == null) return true;
    return allowed && clock.millis() - confirmedAt < GRACE.toMillis();
  }

  /** Changes each time this process (re)acquires the lock, so the mirror can push everything again. */
  long generation() {
    return generation;
  }

  synchronized void renew() {
    try {
      if (lock == null || !lock.isValid(2)) {
        drop();
        lock = open();
      }
      if (allowed && stillHeld()) {
        confirmedAt = clock.millis();
        return;
      }
      if (allowed) {
        // The session no longer holds the lock (it was ended or reset): start over.
        lost("Writer lease lost. This worker pauses until it holds the lease again.");
        drop();
        lock = open();
      }
      acquire();
    } catch (SQLException ex) {
      // Without a confirmed lock another writer may already be live: pause rather than risk it.
      if (allowed) lost("Writer lease unconfirmed (" + ex.getMessage() + "). This worker pauses.");
      else if (!String.valueOf(ex.getMessage()).equals(failure))
        log.warn("Cannot take the writer lease: {}", ex.getMessage());
      failure = String.valueOf(ex.getMessage());
      drop();
      return;
    }
    failure = null;
  }

  private void acquire() throws SQLException {
    String reason = behindPostgres();
    if (reason != null) {
      if (!reason.equals(behind))
        log.error("Not taking the writer lease: {}. Restore the current dispatch database first.", reason);
      behind = reason;
      return;
    }
    behind = null;
    if (!tryLock()) {
      endOwnStaleSession();
      if (!tryLock()) {
        if (!idle) log.warn("Another worker holds the writer lease. This worker stays idle.");
        idle = true;
        allowed = false;
        return;
      }
    }
    boolean announce = idle || generation > 0;
    idle = false;
    confirmedAt = clock.millis();
    generation++;
    allowed = true;
    if (announce) log.warn("Writer lease acquired. This worker ingests live data.");
  }

  private boolean tryLock() throws SQLException {
    try (var st = lock.createStatement()) {
      st.setQueryTimeout(2);
      try (var rs = st.executeQuery("SELECT pg_try_advisory_lock(" + LOCK + ")")) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private boolean stillHeld() throws SQLException {
    try (var st = lock.createStatement()) {
      st.setQueryTimeout(2);
      try (var rs = st.executeQuery(HELD)) {
        return rs.next();
      }
    }
  }

  /** Ends this process's own earlier lock session that the server has not noticed is gone. */
  private void endOwnStaleSession() throws SQLException {
    try (var st = lock.createStatement()) {
      st.setQueryTimeout(2);
      try (var rs = st.executeQuery(HOLDER)) {
        while (rs.next()) {
          if (!tag.equals(rs.getString(2))) continue;
          log.warn("Ending this worker's stale lock session {} to take the writer lease back.", rs.getInt(1));
          try (var end = lock.prepareStatement("SELECT pg_terminate_backend(?)")) {
            end.setInt(1, rs.getInt(1));
            end.execute();
          }
          // Termination is asynchronous; give the server a moment to release the lock.
          try {
            Thread.sleep(200);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
  }

  /** Why the local SQLite is behind the Postgres mirror, or null when it is not. */
  private String behindPostgres() throws SQLException {
    var sqlite = local.get();
    if (sqlite == null) return null;
    long localMail = number(sqlite.queryForObject("SELECT coalesce(max(id),0) FROM mail", Long.class));
    // Only sent deliveries count: the worker deletes a claimed delivery when 5U asks it to retry.
    long localSent = number(sqlite.queryForObject("SELECT count(*) FROM deliveries WHERE state='sent'", Long.class));
    try (var st = lock.createStatement()) {
      st.setQueryTimeout(5);
      long remoteMail = 0;
      long remoteSent = 0;
      boolean hasMail;
      boolean hasDeliveries;
      try (var rs = st.executeQuery("SELECT to_regclass('mail') IS NOT NULL, to_regclass('deliveries') IS NOT NULL")) {
        rs.next();
        hasMail = rs.getBoolean(1);
        hasDeliveries = rs.getBoolean(2);
      }
      if (hasMail) remoteMail = single(st, "SELECT coalesce(max(id::bigint),0) FROM mail");
      if (hasDeliveries) remoteSent = single(st, "SELECT count(*) FROM deliveries WHERE state='sent'");
      if (localMail < remoteMail)
        return "local messages end at id " + localMail + " but Postgres has up to " + remoteMail;
      if (localSent < remoteSent)
        return "local database has " + localSent + " sent deliveries but Postgres has " + remoteSent;
      return null;
    }
  }

  private static long single(Statement st, String sql) throws SQLException {
    try (var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static long number(Long value) {
    return value == null ? 0 : value;
  }

  private Connection open() throws SQLException {
    var connection = postgres.open();
    try (var st = connection.createStatement()) {
      st.setQueryTimeout(2);
      st.execute("SET application_name = '" + tag + "'");
      // Let the server notice a vanished writer in about 30 s, so its lock frees up quickly.
      st.execute("SET tcp_keepalives_idle = 15");
      st.execute("SET tcp_keepalives_interval = 5");
      st.execute("SET tcp_keepalives_count = 3");
    } catch (SQLException ex) {
      Postgres.close(connection);
      throw ex;
    }
    return connection;
  }

  private void lost(String message) {
    log.warn(message);
    allowed = false;
  }

  private void drop() {
    allowed = false;
    Postgres.close(lock);
    lock = null;
  }

  /** The application name of this process's lock session, for tests and diagnostics. */
  String tag() {
    return tag;
  }

  @PreDestroy
  synchronized void release() {
    if (renewal != null) renewal.shutdownNow();
    drop();
  }
}
