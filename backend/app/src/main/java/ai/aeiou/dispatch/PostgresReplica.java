package ai.aeiou.dispatch;

import static ai.aeiou.dispatch.PostgresMirror.*;

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
 * the API reads. Each pull reads one consistent Postgres snapshot (a read-only REPEATABLE READ
 * transaction) and applies only what changed in one short SQLite transaction. It only reads
 * Postgres. It runs on its own thread because a read-only process schedules no jobs.
 *
 * <p>A table the writer tracks (see {@link PostgresMirror}) is pulled incrementally: rows whose
 * {@code sync_xid} is at least the oldest transaction still running at the previous pull, and the
 * keys deleted since then. A table without tracking is compared by a server-side hash of its rows
 * and re-read in full only when the hash changes. The first pull, a column or type change, a
 * TRUNCATE, pruned tombstones or a row count that does not match afterwards all fall back to a
 * full read of that table.
 */
@Component
class PostgresReplica {
  private static final Logger log = LoggerFactory.getLogger(PostgresReplica.class);
  private final JdbcTemplate sqlite;
  private final TransactionTemplate tx;
  private final Postgres postgres;
  private final ScheduledExecutorService pulls;
  private Connection pg;
  private volatile String error;
  private volatile java.time.Instant lastSync;
  private volatile Stats lastStats;
  // What the local copy reflects: the column signature and, for untracked tables, the row hash.
  private final Map<String, String> signatures = new HashMap<>();
  private final Map<String, String> hashes = new HashMap<>();
  // Every transaction older than this was visible to the last applied pull. Null before the first.
  private String since;

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

  /** One pull: per table how it was read, how many rows and characters came over, and timings. */
  record Stats(Map<String, String> modes, Map<String, Integer> rows, long chars, long postgresMs, long sqliteMs) {
    int totalRows() {
      return rows.values().stream().mapToInt(Integer::intValue).sum();
    }
  }

  private enum Mode { SKIP, FULL, DELTA }

  /** A local table and how its columns are read from Postgres. */
  private static final class Spec {
    final String name;
    final List<String> columns = new ArrayList<>();
    final List<String> select = new ArrayList<>();
    final Map<String, String> remote;
    final List<String> keys;
    final boolean rowid;
    final boolean tracked;
    final String signature;
    Mode mode = Mode.SKIP;
    String hash;
    long remoteCount = -1;
    List<Object[]> rows = List.of();
    List<Object[]> deleted = List.of();

    Spec(String name, List<String> local, List<String> keys, Map<String, String> remote, boolean tracked) {
      this.name = name;
      this.remote = remote;
      this.keys = keys;
      for (String c : local) {
        String type = remote.get(c);
        if (type == null) continue;
        columns.add(c);
        select.add(text(quote(c), type));
      }
      this.rowid = remote.containsKey(ROWID);
      // Incremental pulls need the key columns to apply changes and deletes by key.
      this.tracked = tracked && remote.containsKey(VERSION) && !keys.isEmpty() && columns.containsAll(keys);
      var sig = new StringBuilder(this.tracked ? "tracked" : "hashed").append(rowid ? "+rowid" : "");
      for (String c : columns) sig.append('|').append(c).append(':').append(remote.get(c));
      this.signature = sig.toString();
    }

    String selectRows() {
      return "SELECT " + String.join(",", select) + (rowid ? "," + ROWID + "::text" : "") + " FROM " + quote(name);
    }

    String order() {
      // Inserting in the writer's rowid order keeps the same insertion order in the replica.
      return rowid ? " ORDER BY " + ROWID : "";
    }
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
      long started = System.nanoTime();
      List<Spec> specs;
      String watermark;
      long[] chars = {0};
      try {
        String[] meta = new String[4];
        // The first statement takes the snapshot every later read in this transaction sees.
        try (var st = pg.createStatement();
            var rs = st.executeQuery(
                "SELECT pg_snapshot_xmin(pg_current_snapshot())::text,"
                    + " to_regclass('" + TOMBSTONES + "') IS NOT NULL, to_regclass('" + META + "') IS NOT NULL,"
                    + " (SELECT string_agg(c.relname, ',') FROM (SELECT t.tgrelid FROM pg_trigger t"
                    + "   WHERE t.tgname IN ('" + String.join("','", TRIGGERS) + "') AND t.tgenabled <> 'D'"
                    + "   GROUP BY t.tgrelid HAVING count(DISTINCT t.tgname) = " + TRIGGERS.size() + ") g"
                    + "   JOIN pg_class c ON c.oid = g.tgrelid WHERE c.relnamespace = current_schema()::regnamespace)")) {
          rs.next();
          for (int i = 0; i < meta.length; i++) meta[i] = rs.getString(i + 1);
        }
        watermark = meta[0];
        boolean tombstones = "t".equals(meta[1]);
        var tracked = new HashSet<String>(meta[3] == null ? List.of() : List.of(meta[3].split(",")));
        String pruned = null;
        if ("t".equals(meta[2]))
          try (var st = pg.prepareStatement("SELECT value FROM " + META + " WHERE key=?")) {
            st.setString(1, PRUNED);
            try (var rs = st.executeQuery()) {
              if (rs.next()) pruned = rs.getString(1);
            }
          }
        var remote = new HashMap<String, Map<String, String>>();
        try (var st = pg.createStatement();
            var rs = st.executeQuery(
                "SELECT table_name, column_name, data_type FROM information_schema.columns"
                    + " WHERE table_schema=current_schema()")) {
          while (rs.next()) remote.computeIfAbsent(rs.getString(1), t -> new HashMap<>()).put(rs.getString(2), rs.getString(3));
        }
        specs = new ArrayList<>();
        for (String table :
            sqlite.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                String.class)) {
          var columns = remote.get(table);
          if (columns == null) continue;
          var local = sqlite.queryForList("SELECT name, pk FROM pragma_table_info(?) ORDER BY cid", table);
          var keys = local.stream()
              .filter(c -> ((Number) c.get("pk")).intValue() > 0)
              .sorted(Comparator.comparingInt(c -> ((Number) c.get("pk")).intValue()))
              .map(c -> c.get("name").toString())
              .toList();
          specs.add(new Spec(table, local.stream().map(c -> c.get("name").toString()).toList(), keys, columns,
              tombstones && tracked.contains(table)));
        }
        // Deletes since the last pull may have been pruned: tracked tables then start over.
        boolean fresh = since != null && (pruned == null || compare(since, pruned) > 0);
        probe(specs, fresh, tombstones);
        for (var s : specs) {
          if (s.mode == Mode.FULL) s.rows = read(s.selectRows() + s.order(), s, chars);
          else if (s.mode == Mode.DELTA) {
            s.rows = read(s.selectRows() + " WHERE " + VERSION + " >= ?::xid8" + s.order(), s, chars, since);
            s.deleted = readDeleted(s, chars);
          }
        }
      } finally {
        pg.rollback();
      }
      long fetched = System.nanoTime();
      var changed = specs.stream().filter(s -> s.mode != Mode.SKIP).toList();
      var mismatched = new ArrayList<String>();
      if (!changed.isEmpty())
        try {
          tx.executeWithoutResult(status -> {
            for (var s : changed) {
              apply(s);
              if (s.mode == Mode.DELTA && count(s.name) != s.remoteCount) mismatched.add(s.name);
            }
          });
        } catch (RuntimeException ex) {
          // The local copy no longer matches what was recorded: start over with full pulls.
          signatures.clear();
          hashes.clear();
          since = null;
          throw ex;
        }
      long applied = System.nanoTime();
      for (var s : specs) {
        signatures.put(s.name, s.signature);
        if (s.tracked) hashes.remove(s.name);
        else if (s.hash != null) hashes.put(s.name, s.hash);
      }
      signatures.keySet().retainAll(specs.stream().map(s -> s.name).toList());
      // A count that does not match means a change escaped tracking: re-read that table in full.
      for (String table : mismatched) {
        log.warn("Postgres replica row count differs for {}; next pull reads it in full", table);
        signatures.remove(table);
      }
      since = watermark;
      var modes = new LinkedHashMap<String, String>();
      var rows = new LinkedHashMap<String, Integer>();
      for (var s : specs) {
        modes.put(s.name, s.mode.name().toLowerCase(Locale.ROOT));
        rows.put(s.name, s.rows.size() + s.deleted.size());
      }
      lastStats = new Stats(modes, rows, chars[0], (fetched - started) / 1_000_000, (applied - fetched) / 1_000_000);
      error = null;
      lastSync = java.time.Instant.now();
    } catch (SQLException | RuntimeException ex) {
      if (error == null) log.warn("Postgres replica pull failed: {}", ex.getMessage());
      error = "Postgres replica pull failed (" + ex.getClass().getSimpleName() + ")";
      Postgres.close(pg);
      pg = null;
    }
  }

  /**
   * Decides per table, in one round trip, whether it changed: tracked tables report their row count
   * and whether any row is newer than the watermark, the tombstone log reports deletes and resets
   * per table, and untracked tables report a hash of their rows.
   */
  private void probe(List<Spec> specs, boolean fresh, boolean tombstones) throws SQLException {
    var parts = new ArrayList<String>();
    var params = new ArrayList<String>();
    var byName = new HashMap<String, Spec>();
    for (var s : specs) {
      byName.put(s.name, s);
      boolean known = s.signature.equals(signatures.get(s.name));
      if (s.tracked) {
        if (!fresh || !known) {
          s.mode = Mode.FULL;
          continue;
        }
        parts.add("SELECT 'rows', ?, count(*)::text, coalesce(bool_or(" + VERSION + " >= ?::xid8), false) FROM " + quote(s.name));
        params.add(s.name);
        params.add(since);
      } else {
        String row = String.join(" || ',' || ", s.select.stream().map(e -> "quote_nullable(" + e + ")").toList());
        if (s.rowid) row = "quote_nullable(" + ROWID + "::text) || ',' || " + row;
        if (s.select.isEmpty()) row = "''";
        parts.add("SELECT 'hash', ?, md5(coalesce(string_agg(" + row + ", E'\\n' ORDER BY " + (s.rowid ? ROWID + ", " : "")
            + row + "), '')), false FROM " + quote(s.name));
        params.add(s.name);
      }
    }
    if (tombstones && fresh && specs.stream().anyMatch(s -> s.tracked && s.mode != Mode.FULL)) {
      parts.add("SELECT CASE WHEN bool_or(pk IS NULL) THEN 'reset' ELSE 'tomb' END, tbl, NULL, true FROM " + TOMBSTONES
          + " WHERE xid >= ?::xid8 GROUP BY tbl");
      params.add(since);
    }
    if (parts.isEmpty()) return;
    try (var st = pg.prepareStatement(String.join(" UNION ALL ", parts))) {
      for (int i = 0; i < params.size(); i++) st.setString(i + 1, params.get(i));
      try (var rs = st.executeQuery()) {
        while (rs.next()) {
          var s = byName.get(rs.getString(2));
          if (s == null) continue;
          switch (rs.getString(1)) {
            case "rows" -> {
              s.remoteCount = Long.parseLong(rs.getString(3));
              if (rs.getBoolean(4) && s.mode == Mode.SKIP) s.mode = Mode.DELTA;
            }
            case "tomb" -> {
              if (s.tracked && s.mode == Mode.SKIP) s.mode = Mode.DELTA;
            }
            case "reset" -> {
              if (s.tracked) s.mode = Mode.FULL;
            }
            case "hash" -> {
              s.hash = rs.getString(3);
              if (!s.hash.equals(hashes.get(s.name)) || !s.signature.equals(signatures.get(s.name))) s.mode = Mode.FULL;
            }
            default -> {}
          }
        }
      }
    }
  }

  private List<Object[]> read(String sql, Spec s, long[] chars, String... params) throws SQLException {
    var rows = new ArrayList<Object[]>();
    int width = s.columns.size() + (s.rowid ? 1 : 0);
    try (var st = pg.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) st.setString(i + 1, params[i]);
      try (var rs = st.executeQuery()) {
        while (rs.next()) {
          var row = new Object[width];
          for (int i = 0; i < width; i++) {
            String v = rs.getString(i + 1);
            row[i] = v;
            if (v != null) chars[0] += v.length();
          }
          rows.add(row);
        }
      }
    }
    return rows;
  }

  /** Keys deleted since the watermark, rendered like the columns themselves. */
  private List<Object[]> readDeleted(Spec s, long[] chars) throws SQLException {
    var select = new ArrayList<String>();
    for (String k : s.keys) {
      String type = s.remote.get(k);
      String raw = "(pk->>'" + k.replace("'", "''") + "')";
      select.add("USER-DEFINED".equals(type) || "ARRAY".equals(type) ? raw : text(raw + "::" + type, type));
    }
    var rows = new ArrayList<Object[]>();
    try (var st = pg.prepareStatement("SELECT " + String.join(",", select) + " FROM " + TOMBSTONES
        + " WHERE tbl=? AND xid >= ?::xid8 AND pk IS NOT NULL ORDER BY id")) {
      st.setString(1, s.name);
      st.setString(2, since);
      try (var rs = st.executeQuery()) {
        while (rs.next()) {
          var key = new Object[s.keys.size()];
          for (int i = 0; i < key.length; i++) {
            key[i] = rs.getString(i + 1);
            if (key[i] != null) chars[0] += key[i].toString().length();
          }
          rows.add(key);
        }
      }
    }
    return rows;
  }

  private void apply(Spec s) {
    String table = quote(s.name);
    if (s.mode == Mode.FULL) sqlite.update("DELETE FROM " + table);
    else {
      String byKey = "DELETE FROM " + table + " WHERE "
          + String.join(" AND ", s.keys.stream().map(k -> quote(k) + " IS ?").toList());
      // Deletes first: a key deleted and written again is in the snapshot's rows and comes back.
      if (!s.deleted.isEmpty()) sqlite.batchUpdate(byKey, s.deleted);
      var index = s.keys.stream().map(s.columns::indexOf).toList();
      if (!s.rows.isEmpty())
        sqlite.batchUpdate(byKey, s.rows.stream().map(r -> index.stream().map(i -> r[i]).toArray()).toList());
    }
    if (s.rows.isEmpty()) return;
    String columns = String.join(",", s.columns.stream().map(PostgresReplica::quote).toList());
    String values = String.join(",", Collections.nCopies(s.columns.size(), "?"));
    if (!s.rowid) {
      sqlite.batchUpdate("INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")", s.rows);
      return;
    }
    // Keep the writer's rowid, so ORDER BY rowid matches the writer. A rowid already taken by a
    // row this snapshot does not replace keeps that row; the newcomer gets a fresh rowid instead.
    int[] done = sqlite.batchUpdate(
        "INSERT OR IGNORE INTO " + table + " (" + columns + ",rowid) VALUES (" + values + ",?)", s.rows);
    var left = new ArrayList<Object[]>();
    for (int i = 0; i < done.length; i++)
      if (done[i] == 0) left.add(Arrays.copyOf(s.rows.get(i), s.columns.size()));
    if (!left.isEmpty())
      sqlite.batchUpdate("INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")", left);
  }

  private long count(String table) {
    return sqlite.queryForObject("SELECT count(*) FROM " + quote(table), Long.class);
  }

  // The worker stores instants as ISO-8601 UTC text, the form its SQL and parsers expect.
  private static String text(String expr, String type) {
    return type.startsWith("timestamp")
        ? "to_char(" + expr + " AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"
        : expr + "::text";
  }

  private static int compare(String a, String b) {
    return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
  }

  private static String quote(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  /** When the last pull from Postgres finished, or null before the first one. */
  java.time.Instant lastSync() {
    return lastSync;
  }

  /** What the last successful pull read and wrote, or null before the first one. */
  Stats lastStats() {
    return lastStats;
  }

  String error() {
    return error;
  }

  @PreDestroy
  void stop() {
    if (pulls != null) pulls.shutdownNow();
  }
}
