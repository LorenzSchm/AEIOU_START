package ai.aeiou.dispatch;

import java.sql.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mirrors every SQLite table to Postgres. SQLite stays the worker's source of truth. Each tick
 * upserts new and changed rows by primary key and deletes rows that left SQLite since the last
 * push. Missing tables and columns are created. A failed sync never throws into the wave. The
 * scheduler only runs this on the writer that holds the {@link WriterLease}.
 *
 * <p>For incremental replica pulls it also installs change tracking on each mirrored table: a
 * {@code sync_xid} column that a trigger stamps with the writing transaction's id on every real
 * insert or update, a {@code sync_tombstone} log of deleted keys, and a marker row on TRUNCATE.
 * Because triggers do the stamping, writes from older writers and scripts are tracked too. Readers
 * that do not know these columns and tables ignore them.
 */
@Component
class PostgresMirror {
  private static final Logger log = LoggerFactory.getLogger(PostgresMirror.class);
  private static final String SEP = "\u001f";
  static final String ROWID = "sqlite_rowid";
  static final String VERSION = "sync_xid";
  static final String TOMBSTONES = "sync_tombstone";
  static final String META = "sync_meta";
  static final String PRUNED = "tombstones_pruned_through";
  static final List<String> TRIGGERS = List.of("aeiou_sync_row", "aeiou_sync_gone", "aeiou_sync_truncate");
  private static final long PRUNE_MS = 3_600_000;
  private final JdbcTemplate sqlite;
  private final Postgres postgres;
  // Last row pushed per table and primary key, so each tick only sends inserts and changes.
  private final Map<String, Map<String, String>> pushed = new HashMap<>();
  private final Map<String, Map<String, String>> remoteTypes = new HashMap<>();
  private final Set<String> rejected = new HashSet<>();
  // Tables whose change tracking was checked on the current connection.
  private final Set<String> tracked = new HashSet<>();
  private boolean trackingReady;
  private boolean trackingWarned;
  private long prunedAt;
  private Connection pg;
  private String error;

  @Autowired
  PostgresMirror(Ledger ledger, @Value("${postgres.url}") String url) {
    this(ledger.jdbc(), url);
  }

  PostgresMirror(JdbcTemplate sqlite, String url) {
    this.sqlite = sqlite;
    this.postgres = Postgres.of(url);
  }

  @Scheduled(fixedDelayString = "${postgres.sync-ms}")
  synchronized void sync() {
    if (postgres == null) return;
    try {
      if (pg == null || !pg.isValid(3)) {
        Postgres.close(pg);
        pg = postgres.open();
        tracked.clear();
        remoteTypes.clear();
        trackingReady = installTracking();
      }
      for (String table :
          sqlite.queryForList(
              "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
              String.class)) mirror(table);
      if (trackingReady && System.currentTimeMillis() - prunedAt > PRUNE_MS) pruneTombstones();
      error = null;
    } catch (SQLException | RuntimeException ex) {
      if (error == null) log.warn("Postgres mirror failed: {}", ex.getMessage());
      error = "Postgres mirror failed (" + ex.getClass().getSimpleName() + ")";
      Postgres.close(pg);
      pg = null;
    }
  }

  private void mirror(String table) throws SQLException {
    var info = sqlite.queryForList("SELECT name, type, pk FROM pragma_table_info(?) ORDER BY cid", table);
    // The rowid keeps SQLite insertion order, which the inbox uses to list deliveries.
    info.add(Map.of("name", ROWID, "type", "INTEGER", "pk", 0));
    var columns = info.stream().map(c -> c.get("name").toString()).toList();
    var keys =
        info.stream()
            .filter(c -> ((Number) c.get("pk")).intValue() > 0)
            .sorted(Comparator.comparingInt(c -> ((Number) c.get("pk")).intValue()))
            .map(c -> c.get("name").toString())
            .toList();
    if (keys.isEmpty()) return;
    var types = remoteTypes.get(table);
    if (types == null || !types.keySet().containsAll(columns)) types = ensureTable(table, info, keys);
    if (trackingReady && tracked.add(table)) track(table, keys);

    var last = pushed.computeIfAbsent(table, t -> new HashMap<>());
    var current = new HashMap<String, String>();
    var changed = new ArrayList<Map<String, Object>>();
    for (var row : sqlite.queryForList("SELECT *, rowid AS " + ROWID + " FROM \"" + table + "\"")) {
      String key = keys.stream().map(k -> String.valueOf(row.get(k))).reduce((a, b) -> a + SEP + b).orElseThrow();
      String value = row.toString();
      current.put(key, value);
      if (!value.equals(last.get(key))) changed.add(row);
    }
    var gone = last.keySet().stream().filter(k -> !current.containsKey(k)).toList();
    if (changed.isEmpty() && gone.isEmpty()) return;

    String insert =
        "INSERT INTO " + quote(table) + " (" + String.join(",", columns.stream().map(this::quote).toList())
            + ") VALUES (" + String.join(",", columns.stream().map(c -> "CAST(? AS " + typeOf(table, c) + ")").toList())
            + ") ON CONFLICT (" + String.join(",", keys.stream().map(this::quote).toList()) + ") "
            + (columns.size() == keys.size()
                ? "DO NOTHING"
                : "DO UPDATE SET " + String.join(",", columns.stream().filter(c -> !keys.contains(c))
                    .map(c -> quote(c) + "=EXCLUDED." + quote(c)).toList()));
    String delete =
        "DELETE FROM " + quote(table) + " WHERE "
            + String.join(" AND ", keys.stream().map(k -> quote(k) + "=CAST(? AS " + typeOf(table, k) + ")").toList());

    pg.setAutoCommit(false);
    try (var up = pg.prepareStatement(insert); var del = pg.prepareStatement(delete)) {
      for (var row : changed) {
        bind(up, columns.stream().map(row::get).toList());
        up.addBatch();
      }
      for (String key : gone) {
        bind(del, Arrays.asList((Object[]) key.split(SEP, -1)));
        del.addBatch();
      }
      up.executeBatch();
      del.executeBatch();
      pg.commit();
    } catch (SQLException batch) {
      pg.rollback();
      // One bad value must not hold back the rest of the table: retry row by row.
      pg.setAutoCommit(true);
      for (var row : changed) {
        String key = keys.stream().map(k -> String.valueOf(row.get(k))).reduce((a, b) -> a + SEP + b).orElseThrow();
        try (var up = pg.prepareStatement(insert)) {
          bind(up, columns.stream().map(row::get).toList());
          up.executeUpdate();
        } catch (SQLException ex) {
          if (rejected.add(table + "|" + key))
            log.warn("Postgres mirror skipped {} {}: {}", table, key, ex.getMessage());
          current.remove(key);
        }
      }
      try (var del = pg.prepareStatement(delete)) {
        for (String key : gone) {
          bind(del, Arrays.asList((Object[]) key.split(SEP, -1)));
          del.executeUpdate();
        }
      }
    } finally {
      pg.setAutoCommit(true);
    }
    pushed.put(table, current);
  }

  private Map<String, String> ensureTable(String table, List<Map<String, Object>> info, List<String> keys)
      throws SQLException {
    var types = remoteColumns(table);
    try (var st = pg.createStatement()) {
      if (types.isEmpty()) {
        st.execute(
            "CREATE TABLE IF NOT EXISTS " + quote(table) + " ("
                + String.join(",", info.stream().map(c -> quote(c.get("name").toString()) + " " + pgType(c.get("type"))).toList())
                + ", PRIMARY KEY (" + String.join(",", keys.stream().map(this::quote).toList()) + "))");
      } else {
        for (var c : info)
          if (!types.containsKey(c.get("name").toString()))
            st.execute("ALTER TABLE " + quote(table) + " ADD COLUMN IF NOT EXISTS "
                + quote(c.get("name").toString()) + " " + pgType(c.get("type")));
      }
    }
    types = remoteColumns(table);
    remoteTypes.put(table, types);
    return types;
  }

  /** Creates the shared tracking functions and tables once per connection. Never fails the mirror. */
  private boolean installTracking() {
    try {
      pg.setAutoCommit(false);
      try (var st = pg.createStatement()) {
        st.execute("CREATE TABLE IF NOT EXISTS " + TOMBSTONES + " (id bigserial PRIMARY KEY, tbl text NOT NULL,"
            + " pk jsonb, xid xid8 NOT NULL DEFAULT pg_current_xact_id(), at timestamptz NOT NULL DEFAULT now())");
        st.execute("CREATE INDEX IF NOT EXISTS " + TOMBSTONES + "_xid ON " + TOMBSTONES + " (xid)");
        st.execute("CREATE TABLE IF NOT EXISTS " + META + " (key text PRIMARY KEY, value text)");
        // A no-op update keeps its version, so a restarted writer re-sending its rows changes nothing.
        st.execute("""
            CREATE OR REPLACE FUNCTION aeiou_sync_touch() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF TG_OP = 'UPDATE' AND to_jsonb(NEW) = to_jsonb(OLD) THEN RETURN NEW; END IF;
              NEW.%s := pg_current_xact_id();
              RETURN NEW;
            END $$""".formatted(VERSION));
        st.execute("""
            CREATE OR REPLACE FUNCTION aeiou_sync_forget() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              INSERT INTO %s (tbl, pk)
                SELECT TG_TABLE_NAME, jsonb_object_agg(k, to_jsonb(OLD) -> k) FROM unnest(TG_ARGV) AS k;
              RETURN OLD;
            END $$""".formatted(TOMBSTONES));
        st.execute("""
            CREATE OR REPLACE FUNCTION aeiou_sync_reset() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              INSERT INTO %s (tbl, pk) VALUES (TG_TABLE_NAME, NULL);
              RETURN NULL;
            END $$""".formatted(TOMBSTONES));
      }
      pg.commit();
      return true;
    } catch (SQLException ex) {
      untracked(ex);
      return false;
    } finally {
      autoCommit();
    }
  }

  /** Adds the version column, its index and the three triggers to one table, if any is missing. */
  private void track(String table, List<String> keys) {
    try {
      var present = new HashSet<String>();
      try (var st = pg.prepareStatement(
          "SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
              + " WHERE c.relnamespace = current_schema()::regnamespace AND c.relname = ? AND NOT t.tgisinternal")) {
        st.setString(1, table);
        try (var rs = st.executeQuery()) {
          while (rs.next()) present.add(rs.getString(1));
        }
      }
      if (remoteTypes.get(table).containsKey(VERSION) && present.containsAll(TRIGGERS)) return;
      String args = String.join(",", keys.stream().map(k -> "'" + k.replace("'", "''") + "'").toList());
      pg.setAutoCommit(false);
      try (var st = pg.createStatement()) {
        st.execute("ALTER TABLE " + quote(table) + " ADD COLUMN IF NOT EXISTS " + VERSION + " xid8");
        st.execute("CREATE INDEX IF NOT EXISTS " + quote(table + "_" + VERSION) + " ON " + quote(table) + " (" + VERSION + ")");
        st.execute("CREATE OR REPLACE TRIGGER " + TRIGGERS.get(0) + " BEFORE INSERT OR UPDATE ON " + quote(table)
            + " FOR EACH ROW EXECUTE FUNCTION aeiou_sync_touch()");
        st.execute("CREATE OR REPLACE TRIGGER " + TRIGGERS.get(1) + " AFTER DELETE ON " + quote(table)
            + " FOR EACH ROW EXECUTE FUNCTION aeiou_sync_forget(" + args + ")");
        st.execute("CREATE OR REPLACE TRIGGER " + TRIGGERS.get(2) + " AFTER TRUNCATE ON " + quote(table)
            + " FOR EACH STATEMENT EXECUTE FUNCTION aeiou_sync_reset()");
      }
      pg.commit();
      remoteTypes.get(table).put(VERSION, "xid8");
    } catch (SQLException ex) {
      untracked(ex);
    } finally {
      autoCommit();
    }
  }

  /**
   * Drops tombstones older than a day and records the newest dropped transaction id, so a replica
   * that last pulled before it knows it may have missed a delete and pulls in full.
   */
  private void pruneTombstones() {
    prunedAt = System.currentTimeMillis();
    try (var st = pg.createStatement()) {
      st.executeUpdate("WITH gone AS (DELETE FROM " + TOMBSTONES + " WHERE at < now() - interval '1 day' RETURNING xid)"
          + " INSERT INTO " + META + " (key, value) SELECT '" + PRUNED + "', max(xid)::text FROM gone HAVING count(*) > 0"
          + " ON CONFLICT (key) DO UPDATE SET value = greatest(" + META + ".value::xid8, EXCLUDED.value::xid8)::text");
    } catch (SQLException ex) {
      untracked(ex);
    }
  }

  private void untracked(SQLException ex) {
    try {
      if (!pg.getAutoCommit()) pg.rollback();
    } catch (SQLException ignored) {
    }
    if (!trackingWarned) log.warn("Postgres change tracking unavailable, replicas pull in full: {}", ex.getMessage());
    trackingWarned = true;
  }

  private void autoCommit() {
    try {
      pg.setAutoCommit(true);
    } catch (SQLException ignored) {
    }
  }

  private Map<String, String> remoteColumns(String table) throws SQLException {
    var types = new HashMap<String, String>();
    try (var st = pg.prepareStatement(
        "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=?")) {
      st.setString(1, table);
      try (var rs = st.executeQuery()) {
        while (rs.next()) types.put(rs.getString(1), rs.getString(2));
      }
    }
    return types;
  }

  private String typeOf(String table, String column) {
    String type = remoteTypes.get(table).get(column);
    return "USER-DEFINED".equals(type) || "ARRAY".equals(type) ? "text" : type;
  }

  private static String pgType(Object sqliteType) {
    String t = String.valueOf(sqliteType).toUpperCase(Locale.ROOT);
    if (t.contains("INT")) return "bigint";
    if (t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB")) return "double precision";
    return "text";
  }

  private static void bind(PreparedStatement st, List<Object> values) throws SQLException {
    for (int i = 0; i < values.size(); i++) {
      Object v = values.get(i);
      if (v == null) st.setNull(i + 1, Types.VARCHAR);
      else st.setString(i + 1, v.toString());
    }
  }

  private String quote(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  String error() {
    return error;
  }
}
