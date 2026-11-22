package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The writer mirrors its SQLite into a throwaway local Postgres and a read-only desk pulls it into
 * its replica. After every pull the replica must hold exactly what a fresh full pull would, in the
 * writer's rowid order. Skipped when no Postgres binaries are installed.
 */
class PostgresReplicaTest {
  private static LocalPostgres server;
  @TempDir Path temp;
  private String url;
  private Ledger writer;
  private Ledger desk;
  private PostgresMirror mirror;
  private PostgresReplica replica;

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
    writer = new Ledger(temp.resolve("w/dispatch.sqlite3").toString(), new ObjectMapper());
    desk = new Ledger(temp.resolve("d/dispatch.sqlite3").toString(), new ObjectMapper(), true);
    var w = writer.jdbc();
    for (int i = 1; i <= 40; i++) {
      w.update("INSERT INTO mail(id,root,sender,subject,body,received,kind) VALUES(?,?,?,?,?,?,?)",
          i, i, "c" + i + "@example.com", "RFQ " + i, "body " + i, "2026-11-21T15:%02d:00Z".formatted(i), "request");
      w.update("INSERT INTO tender(request_id,email,leg,euros,days) VALUES(?,?,?,?,?)", i, "a@air.example", "air", "10" + i, 2);
      w.update("INSERT INTO tender(request_id,email,leg,euros,days) VALUES(?,?,?,?,?)", i, "t@truck.example", "truck", "5" + i, 1);
      w.update("INSERT INTO deliveries(action,state,payload) VALUES(?,?,?)", "quote:" + i, "sent", "{\"n\":" + i + "}");
    }
    w.update("INSERT INTO board_result VALUES(?,?)", "2026-11-21T15:01:00Z", "{\"verdict\":\"ok\"}");
    mirror = new PostgresMirror(w, url);
    replica = new PostgresReplica(desk.jdbc(), desk.transactions(), url);
    mirror.sync();
    assertNull(mirror.error());
  }

  @Test
  void firstPullIsFullAndAnUnchangedPullTransfersNothing() {
    pull();
    assertEquals("full", replica.lastStats().modes().get("mail"));
    assertEquals(40, replica.lastStats().rows().get("mail"));
    assertMatchesWriter();

    pull();
    assertEquals(0, replica.lastStats().totalRows());
    assertTrue(replica.lastStats().modes().values().stream().allMatch("skip"::equals), replica.lastStats().toString());
    assertMatchesWriter();
  }

  @Test
  void pullsOnlyTheChangedRows() {
    pull();
    writer.jdbc().update("UPDATE mail SET stage='quoted', proposal='{\"total\":120}' WHERE id=7");
    writer.jdbc().update("INSERT INTO mail(id,root,subject,received,kind) VALUES(41,41,'RFQ 41','2026-11-21T16:00:00Z','request')");
    mirror.sync();
    pull();
    assertEquals("delta", replica.lastStats().modes().get("mail"));
    assertEquals(2, replica.lastStats().rows().get("mail"));
    assertEquals(2, replica.lastStats().totalRows(), replica.lastStats().toString());
    assertEquals("skip", replica.lastStats().modes().get("tender"));
    assertEquals("quoted", desk.jdbc().queryForObject("SELECT stage FROM mail WHERE id=7", String.class));
    assertMatchesWriter();
  }

  @Test
  void writerRestartResendingSameRowsChangesNothing() {
    pull();
    var restarted = new PostgresMirror(writer.jdbc(), url);
    restarted.sync();
    assertNull(restarted.error());
    pull();
    assertEquals(0, replica.lastStats().totalRows(), replica.lastStats().toString());
    assertMatchesWriter();
  }

  @Test
  void deletesPropagate() {
    pull();
    writer.jdbc().update("DELETE FROM tender WHERE request_id=3 AND email='a@air.example'");
    writer.jdbc().update("DELETE FROM mail WHERE id IN (5,6)");
    writer.jdbc().update("DELETE FROM board_result");
    mirror.sync();
    pull();
    assertEquals("delta", replica.lastStats().modes().get("tender"));
    assertEquals(1, replica.lastStats().rows().get("tender"));
    assertEquals(2, replica.lastStats().rows().get("mail"));
    assertEquals(0, desk.jdbc().queryForObject("SELECT count(*) FROM board_result", Integer.class));
    assertMatchesWriter();
  }

  @Test
  void deletedTimestampKeysMatchTheReplicaText() throws Exception {
    // The shared schema stores board_result.received as timestamptz, unlike the mirror's text default.
    try (var c = pg(); var st = c.createStatement()) {
      st.execute("DROP TABLE board_result");
      st.execute("CREATE TABLE board_result (received timestamptz PRIMARY KEY, result jsonb NOT NULL, sqlite_rowid bigint)");
    }
    writer.jdbc().update("INSERT INTO board_result VALUES(?,?)", "2026-11-21T15:02:00Z", "{\"verdict\":\"late\"}");
    var fresh = new PostgresMirror(writer.jdbc(), url);
    fresh.sync();
    pull();
    assertEquals(2, desk.jdbc().queryForObject("SELECT count(*) FROM board_result", Integer.class));
    writer.jdbc().update("DELETE FROM board_result WHERE received='2026-11-21T15:01:00Z'");
    fresh.sync();
    pull();
    assertEquals("delta", replica.lastStats().modes().get("board_result"));
    assertEquals(List.of("2026-11-21T15:02:00.000000Z"),
        desk.jdbc().queryForList("SELECT received FROM board_result", String.class));
  }

  @Test
  void writerRowidOrderSurvivesReplacedRows() {
    pull();
    // INSERT OR REPLACE gives the row a new rowid on the writer, which moves it to the end.
    writer.jdbc().update("INSERT OR REPLACE INTO deliveries(action,state,payload) VALUES('quote:2','failed','{}')");
    mirror.sync();
    pull();
    assertEquals(1, replica.lastStats().rows().get("deliveries"));
    assertEquals("quote:2", desk.jdbc().queryForObject(
        "SELECT action FROM deliveries ORDER BY rowid DESC LIMIT 1", String.class));
    assertMatchesWriter();
  }

  @Test
  void newColumnsAndTablesPropagate() {
    pull();
    // Both sides run a newer schema: one more column and one more table.
    for (var db : List.of(writer.jdbc(), desk.jdbc())) {
      db.execute("ALTER TABLE mail ADD COLUMN urgency TEXT");
      db.execute("CREATE TABLE carrier_note(carrier TEXT PRIMARY KEY, note TEXT)");
    }
    writer.jdbc().update("UPDATE mail SET urgency='high' WHERE id <= 3");
    writer.jdbc().update("INSERT INTO carrier_note VALUES('a@air.example','fast')");
    mirror.sync();
    assertNull(mirror.error());
    pull();
    assertEquals("full", replica.lastStats().modes().get("mail"));
    assertEquals("full", replica.lastStats().modes().get("carrier_note"));
    assertEquals(3, desk.jdbc().queryForObject("SELECT count(*) FROM mail WHERE urgency='high'", Integer.class));
    assertMatchesWriter();

    writer.jdbc().update("UPDATE carrier_note SET note='slow'");
    mirror.sync();
    pull();
    assertEquals("delta", replica.lastStats().modes().get("carrier_note"));
    assertEquals(1, replica.lastStats().totalRows());
    assertMatchesWriter();
  }

  @Test
  void fallsBackToHashedFullPullsWithoutTracking() throws Exception {
    // An older writer: same tables, no version column, no triggers, no tombstone log.
    try (var c = pg(); var st = c.createStatement()) {
      for (String t : List.of("mail", "tender", "deliveries", "board_result", "cursor", "wave_case")) {
        st.execute("DROP TRIGGER IF EXISTS aeiou_sync_row ON " + t);
        st.execute("DROP TRIGGER IF EXISTS aeiou_sync_gone ON " + t);
        st.execute("DROP TRIGGER IF EXISTS aeiou_sync_truncate ON " + t);
        st.execute("ALTER TABLE " + t + " DROP COLUMN IF EXISTS sync_xid");
      }
      st.execute("DROP TABLE sync_tombstone");
    }
    pull();
    assertEquals("full", replica.lastStats().modes().get("mail"));
    assertMatchesWriter();
    pull();
    assertEquals("skip", replica.lastStats().modes().get("mail"));
    assertEquals(0, replica.lastStats().totalRows());

    try (var c = pg(); var st = c.createStatement()) {
      st.execute("UPDATE mail SET stage='quoted' WHERE id=9");
      st.execute("DELETE FROM tender WHERE request_id=9");
    }
    pull();
    assertEquals("full", replica.lastStats().modes().get("mail"));
    assertEquals("full", replica.lastStats().modes().get("tender"));
    assertEquals("skip", replica.lastStats().modes().get("deliveries"));
    assertEquals("quoted", desk.jdbc().queryForObject("SELECT stage FROM mail WHERE id=9", String.class));
    assertEquals(0, desk.jdbc().queryForObject("SELECT count(*) FROM tender WHERE request_id=9", Integer.class));
    assertMatchesFullPull();
  }

  @Test
  void trackingInstalledLaterStartsWithAFullPull() throws Exception {
    try (var c = pg(); var st = c.createStatement()) {
      st.execute("DROP TRIGGER aeiou_sync_row ON mail");
    }
    pull();
    assertEquals("full", replica.lastStats().modes().get("mail"));
    pull();
    assertEquals("skip", replica.lastStats().modes().get("mail"));
    // A newer writer connects and restores the trigger: the replica re-reads the table once.
    new PostgresMirror(writer.jdbc(), url).sync();
    pull();
    assertEquals("full", replica.lastStats().modes().get("mail"));
    writer.jdbc().update("UPDATE mail SET stage='quoted' WHERE id=1");
    mirror.sync();
    pull();
    assertEquals("delta", replica.lastStats().modes().get("mail"));
    assertMatchesWriter();
  }

  @Test
  void truncateAndPrunedTombstonesForceAFullPull() throws Exception {
    pull();
    try (var c = pg(); var st = c.createStatement()) {
      st.execute("TRUNCATE tender");
    }
    pull();
    assertEquals("full", replica.lastStats().modes().get("tender"));
    assertEquals(0, desk.jdbc().queryForObject("SELECT count(*) FROM tender", Integer.class));
    pull();
    assertEquals("skip", replica.lastStats().modes().get("tender"));

    // The writer pruned tombstones newer than this replica's watermark: deletes may be lost.
    try (var c = pg(); var st = c.createStatement()) {
      st.execute("DELETE FROM mail WHERE id=4");
      st.execute("INSERT INTO sync_meta VALUES ('tombstones_pruned_through', pg_current_xact_id()::text)");
      st.execute("DELETE FROM sync_tombstone");
    }
    pull();
    assertEquals("full", replica.lastStats().modes().get("mail"));
    assertEquals(0, desk.jdbc().queryForObject("SELECT count(*) FROM mail WHERE id=4", Integer.class));
    assertMatchesFullPull();
  }

  @Test
  void rePullsWhileOlderTransactionsRunAreIdempotent() throws Exception {
    pull();
    try (var open = pg()) {
      // A transaction that holds an id keeps the watermark back, so the same rows come again.
      open.setAutoCommit(false);
      try (var st = open.createStatement()) {
        st.execute("SELECT pg_current_xact_id()");
      }
      writer.jdbc().update("UPDATE tender SET euros='99' WHERE request_id=2");
      mirror.sync();
      pull();
      assertEquals(2, replica.lastStats().rows().get("tender"));
      pull();
      assertEquals(2, replica.lastStats().rows().get("tender"));
      assertMatchesWriter();
      // Writes committed by that older transaction are not missed when it finally commits.
      try (var st = open.createStatement()) {
        st.execute("UPDATE mail SET subject='late' WHERE id=11");
      }
      pull();
      assertNotEquals("late", desk.jdbc().queryForObject("SELECT subject FROM mail WHERE id=11", String.class));
      open.commit();
    }
    pull();
    assertEquals("late", desk.jdbc().queryForObject("SELECT subject FROM mail WHERE id=11", String.class));
    pull();
    assertEquals(0, replica.lastStats().totalRows());
    assertMatchesFullPull();
  }

  @Test
  void aRowCountMismatchHealsWithAFullPull() throws Exception {
    pull();
    // A row the replica lost locally, for example by a local write.
    desk.jdbc().update("DELETE FROM mail WHERE id=1");
    writer.jdbc().update("UPDATE mail SET stage='quoted' WHERE id=2");
    mirror.sync();
    pull();
    pull();
    assertEquals("full", replica.lastStats().modes().get("mail"));
    assertMatchesWriter();
  }

  private void pull() {
    replica.pull();
    assertNull(replica.error());
  }

  private Connection pg() throws SQLException {
    return new Postgres(url).open();
  }

  private void assertMatchesWriter() {
    for (String table : List.of("mail", "tender", "deliveries", "board_result", "cursor"))
      assertEquals(dump(writer.jdbc(), table), dump(desk.jdbc(), table), table);
  }

  /** The incremental replica equals what a brand-new desk reads in one full pull. */
  private void assertMatchesFullPull() throws Exception {
    var other = new Ledger(temp.resolve("f/dispatch.sqlite3").toString(), new ObjectMapper(), true);
    var full = new PostgresReplica(other.jdbc(), other.transactions(), url);
    full.pull();
    assertNull(full.error());
    for (String table : List.of("mail", "tender", "deliveries", "board_result", "cursor", "wave_case"))
      assertEquals(dump(other.jdbc(), table), dump(desk.jdbc(), table), table);
  }

  private static List<String> dump(JdbcTemplate db, String table) {
    var rows = new ArrayList<String>();
    for (var row : db.queryForList("SELECT * FROM \"" + table + "\" ORDER BY rowid")) {
      var values = new TreeMap<String, Object>(row);
      values.replaceAll((k, v) -> v == null ? null : v.toString());
      rows.add(values.toString());
    }
    return rows;
  }
}
