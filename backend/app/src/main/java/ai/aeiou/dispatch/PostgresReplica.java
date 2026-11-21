package ai.aeiou.dispatch;

import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * On a read-only desk, copies the writer's Postgres data into the local replica SQLite file that
 * the API reads. Each pull reads one consistent Postgres snapshot and replaces every table in one
 * SQLite transaction. It only reads Postgres. It runs on its own thread because a read-only
 * process schedules no jobs.
 */
@Component
class PostgresReplica {
  private static final Logger log = LoggerFactory.getLogger(PostgresReplica.class);
  private final JdbcTemplate sqlite;
  private final TransactionTemplate tx;
  private final Postgres postgres;
  private final ScheduledExecutorService pulls;
  private Connection pg;
  private String error;

  @Autowired
  PostgresReplica(
      Ledger ledger,
      @Value("${dispatch.read-only}") boolean readOnly,
      @Value("${postgres.url}") String url,
      @Value("${postgres.sync-ms}") long syncMs) {
    this(ledger.jdbc(), ledger.transactions(), readOnly ? url : "");
    if (readOnly && postgres == null) log.warn("Read-only desk without DATABASE_URL has no data to show.");
    if (postgres == null) return;
    pulls.scheduleWithFixedDelay(this::pull, 0, syncMs, TimeUnit.MILLISECONDS);
  }

  PostgresReplica(JdbcTemplate sqlite, TransactionTemplate tx, String url) {
    this.sqlite = sqlite;
    this.tx = tx;
    this.postgres = Postgres.of(url);
    this.pulls = postgres == null ? null : Executors.newSingleThreadScheduledExecutor(r -> {
      var thread = new Thread(r, "postgres-replica");
      thread.setDaemon(true);
      return thread;
    });
  }

  synchronized void pull() {
    try {
      if (pg == null || !pg.isValid(3)) {
        Postgres.close(pg);
        pg = postgres.open();
        pg.setReadOnly(true);
        pg.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        pg.setAutoCommit(false);
      }
      var snapshot = new LinkedHashMap<String, Table>();
      try {
        for (String table :
            sqlite.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                String.class)) {
          var t = read(table);
          if (t != null) snapshot.put(table, t);
        }
      } finally {
        pg.rollback();
      }
      tx.executeWithoutResult(status -> {
        for (var e : snapshot.entrySet()) {
          var t = e.getValue();
          sqlite.update("DELETE FROM \"" + e.getKey() + "\"");
          if (t.rows.isEmpty()) continue;
          String insert =
              "INSERT INTO \"" + e.getKey() + "\" (" + String.join(",", t.columns.stream().map(c -> "\"" + c + "\"").toList())
                  + ") VALUES (" + String.join(",", Collections.nCopies(t.columns.size(), "?")) + ")";
          sqlite.batchUpdate(insert, t.rows);
        }
      });
      error = null;
    } catch (SQLException | RuntimeException ex) {
      if (error == null) log.warn("Postgres replica pull failed: {}", ex.getMessage());
      error = "Postgres replica pull failed (" + ex.getClass().getSimpleName() + ")";
      Postgres.close(pg);
      pg = null;
    }
  }

  private record Table(List<String> columns, List<Object[]> rows) {}

  private Table read(String table) throws SQLException {
    var local = sqlite.queryForList("SELECT name FROM pragma_table_info(?) ORDER BY cid", String.class, table);
    var columns = new ArrayList<String>();
    var select = new ArrayList<String>();
    var remote = new HashMap<String, String>();
    try (var st = pg.prepareStatement(
        "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=?")) {
      st.setString(1, table);
      try (var rs = st.executeQuery()) {
        while (rs.next()) remote.put(rs.getString(1), rs.getString(2));
      }
      if (remote.isEmpty()) return null;
      for (String c : local) {
        String type = remote.get(c);
        if (type == null) continue;
        String q = "\"" + c + "\"";
        columns.add(c);
        // The worker stores instants as ISO-8601 UTC text, the form its SQL and parsers expect.
        select.add(type.startsWith("timestamp")
            ? "to_char(" + q + " AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"
            : q + "::text");
      }
    }
    var rows = new ArrayList<Object[]>();
    // Inserting in the writer's rowid order keeps the same insertion order in the replica.
    String order = remote.containsKey(PostgresMirror.ROWID) ? " ORDER BY " + PostgresMirror.ROWID : "";
    try (var st = pg.createStatement();
        var rs = st.executeQuery("SELECT " + String.join(",", select) + " FROM \"" + table + "\"" + order)) {
      while (rs.next()) {
        var row = new Object[columns.size()];
        for (int i = 0; i < row.length; i++) row[i] = rs.getString(i + 1);
        rows.add(row);
      }
    }
    return new Table(columns, rows);
  }

  String error() {
    return error;
  }

  @PreDestroy
  void stop() {
    if (pulls != null) pulls.shutdownNow();
  }
}
