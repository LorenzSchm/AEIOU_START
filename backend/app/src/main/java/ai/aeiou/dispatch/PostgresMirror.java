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
 */
@Component
class PostgresMirror {
  private static final Logger log = LoggerFactory.getLogger(PostgresMirror.class);
  private static final String SEP = "\u001f";
  static final String ROWID = "sqlite_rowid";
  private final JdbcTemplate sqlite;
  private final Postgres postgres;
  // Last row pushed per table and primary key, so each tick only sends inserts and changes.
  private final Map<String, Map<String, String>> pushed = new HashMap<>();
  private final Map<String, Map<String, String>> remoteTypes = new HashMap<>();
  private final Set<String> rejected = new HashSet<>();
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
      }
      for (String table :
          sqlite.queryForList(
              "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
              String.class)) mirror(table);
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
