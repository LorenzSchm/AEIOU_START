package ai.aeiou.dispatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

/**
 * A cheap signal that the inbox changed, without building it.
 *
 * <p>The watch keeps one read-only SQLite connection of its own to the ledger's file, separate from
 * whatever data source the ledger uses. Two levels:
 *
 * <ol>
 *   <li>{@code PRAGMA data_version} on that connection changes whenever any other connection commits
 *       to the file. Between commits a check costs one tiny query.
 *   <li>When it changed, a content fingerprint of the tables {@link Ledger#inboxPage} reads (in the
 *       row order it reads them) tells whether the inbox itself changed. Writers also commit for
 *       unrelated tables (cursor, retries), and a read-only desk's replica rewrites every table every
 *       few seconds with mostly identical rows; neither changes the fingerprint.
 * </ol>
 *
 * <p>When the file can't be watched (an in-memory database, an unreadable path) the fingerprint is
 * empty and callers rebuild every time, as before.
 */
final class LedgerWatch implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(LedgerWatch.class);
  /** Every table {@link Ledger#inboxPage} joins or loads. */
  static final List<String> INBOX_TABLES =
      List.of("mail", "wave_case", "booking_attempt", "board_result", "deliveries", "tender");

  private final String file;
  private Connection connection;
  private long dataVersion = Long.MIN_VALUE;
  private OptionalLong fingerprint = OptionalLong.empty();
  private long fingerprints;

  LedgerWatch(String file) {
    this.file = file == null || file.isBlank() ? null : file;
  }

  /** The watch for the database file the ledger's own connections open. */
  static LedgerWatch of(Ledger ledger) {
    String file = null;
    try {
      for (var row : ledger.jdbc().queryForList("PRAGMA database_list"))
        if ("main".equals(row.get("name"))) file = (String) row.get("file");
    } catch (RuntimeException failed) {
      log.warn("Inbox change detection unavailable: {}", failed.getMessage());
    }
    return new LedgerWatch(file);
  }

  /**
   * The inbox tables' content fingerprint, recomputed only after a commit. Empty when the file can't
   * be watched, which callers treat as "maybe changed".
   */
  synchronized OptionalLong fingerprint() {
    if (file == null) return OptionalLong.empty();
    try {
      if (connection == null) connection = open();
      long version;
      try (var st = connection.createStatement(); var rs = st.executeQuery("PRAGMA data_version")) {
        version = rs.next() ? rs.getLong(1) : Long.MIN_VALUE;
      }
      if (version == dataVersion && fingerprint.isPresent()) return fingerprint;
      fingerprint = OptionalLong.of(content());
      dataVersion = version;
      fingerprints++;
      return fingerprint;
    } catch (SQLException | RuntimeException failed) {
      log.debug("Inbox change check failed: {}", failed.getMessage());
      close();
      return OptionalLong.empty();
    }
  }

  /** How many times the content was hashed: a commit happened before the check. */
  synchronized long fingerprints() {
    return fingerprints;
  }

  private Connection open() throws SQLException {
    var config = new SQLiteConfig();
    config.setReadOnly(true);
    config.setBusyTimeout(10_000);
    return config.createConnection("jdbc:sqlite:" + file);
  }

  /** SHA-256 over every row of the inbox tables in rowid order, read in one transaction. */
  private long content() throws SQLException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    boolean auto = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try (var st = connection.createStatement()) {
      for (String table : INBOX_TABLES) {
        digest.update(("\u0003" + table).getBytes(StandardCharsets.UTF_8));
        try (var rs = st.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY rowid")) {
          int columns = rs.getMetaData().getColumnCount();
          for (int c = 1; c <= columns; c++)
            digest.update(("\u0001" + rs.getMetaData().getColumnName(c)).getBytes(StandardCharsets.UTF_8));
          while (rs.next()) {
            digest.update((byte) 2);
            for (int c = 1; c <= columns; c++) {
              String value = rs.getString(c);
              if (value == null) digest.update((byte) 0);
              else {
                digest.update((byte) 1);
                digest.update(value.getBytes(StandardCharsets.UTF_8));
              }
              digest.update((byte) 4);
            }
          }
        } catch (SQLException missing) {
          // A table the ledger hasn't created yet (or a replica that hasn't synced it) is part of the state.
          if (!String.valueOf(missing.getMessage()).contains("no such table")) throw missing;
          digest.update((byte) 5);
        }
      }
    } finally {
      connection.rollback();
      connection.setAutoCommit(auto);
    }
    byte[] h = digest.digest();
    long out = 0;
    for (int i = 0; i < 8; i++) out = (out << 8) | (h[i] & 0xff);
    return out;
  }

  @Override
  public synchronized void close() {
    if (connection != null) {
      try {
        connection.close();
      } catch (SQLException ignored) {
        // Nothing to recover.
      }
    }
    connection = null;
    dataVersion = Long.MIN_VALUE;
    fingerprint = OptionalLong.empty();
  }
}
