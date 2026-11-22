package ai.aeiou.dispatch;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;

/**
 * A small connection pool over one SQLite file, so queries reuse open connections instead of
 * opening the file for every statement. In WAL mode readers run concurrently with the single
 * writer; a second writer waits up to {@code busyMillis} for the lock, as before. The busy timeout
 * is set on every pooled connection because it is a per-connection setting; WAL is stored in the
 * file itself. Callers must share the returned data source between their JdbcTemplate and their
 * transaction manager so a transaction's statements run on its bound connection.
 */
final class SqlitePool {
  private SqlitePool() {}

  static HikariDataSource open(Path file, int busyMillis, int size, String name) {
    var config = new HikariConfig();
    config.setPoolName(name);
    config.setDriverClassName("org.sqlite.JDBC");
    // IMMEDIATE takes the write lock when a transaction begins, waiting busy_timeout for it. A
    // deferred transaction that reads first and writes later fails at once with SQLITE_BUSY_SNAPSHOT
    // when another connection committed in between; the busy timeout cannot help it.
    config.setJdbcUrl("jdbc:sqlite:" + file + "?busy_timeout=" + busyMillis + "&transaction_mode=IMMEDIATE");
    config.setConnectionInitSql("PRAGMA busy_timeout=" + busyMillis);
    config.setMaximumPoolSize(size);
    config.setMinimumIdle(1);
    // A borrower can wait behind a writer that itself waits busy_timeout for the file lock.
    config.setConnectionTimeout(Math.max(30_000, 3L * busyMillis));
    return new HikariDataSource(config);
  }
}
