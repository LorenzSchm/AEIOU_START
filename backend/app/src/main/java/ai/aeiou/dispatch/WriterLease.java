package ai.aeiou.dispatch;

import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether this process may talk to the live 5U API and write data. A read-only process
 * never may. With a Postgres database, writers also compete for a session advisory lock so only
 * one worker ingests at a time. The lock is renewed on its own thread, so a slow database never
 * delays the wave. A writer that loses its connection keeps working and retakes the lock. It stops
 * only once Postgres reports that another writer holds it.
 */
@Component
class WriterLease {
  private static final Logger log = LoggerFactory.getLogger(WriterLease.class);
  // Arbitrary constant shared by every AEIOU backend that points at the same database.
  private static final long LOCK = 0x4145494F55L;
  private final boolean readOnly;
  private final Postgres postgres;
  private final ScheduledExecutorService renewal;
  private Connection lock;
  private volatile boolean allowed = true;

  WriterLease(@Value("${dispatch.read-only}") boolean readOnly, @Value("${postgres.url}") String url) {
    this.readOnly = readOnly;
    this.postgres = readOnly ? null : Postgres.of(url);
    if (postgres == null) {
      renewal = null;
      return;
    }
    renew();
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
    return !readOnly && allowed;
  }

  private synchronized void renew() {
    try {
      if (lock != null && lock.isValid(2)) {
        if (allowed) return;
      } else {
        Postgres.close(lock);
        lock = postgres.open();
      }
      try (var st = lock.createStatement();
          var rs = st.executeQuery("SELECT pg_try_advisory_lock(" + LOCK + ")")) {
        rs.next();
        boolean held = rs.getBoolean(1);
        if (held != allowed)
          log.warn(held ? "Writer lease acquired. This worker ingests live data."
              : "Another worker holds the writer lease. This worker stays idle.");
        allowed = held;
      }
    } catch (SQLException ex) {
      // Keep the last decision while Postgres is unreachable so the wave never stalls on it.
      Postgres.close(lock);
      lock = null;
    }
  }

  @PreDestroy
  synchronized void release() {
    if (renewal != null) renewal.shutdownNow();
    Postgres.close(lock);
  }
}
