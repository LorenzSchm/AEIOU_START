package ai.aeiou.forwarder;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker = true)
class PostgresTest {
  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

  @Test
  void migrationAndCommitOrderedCursor() throws Exception {
    Flyway.configure()
        .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
        .load()
        .migrate();
    try (var first =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var second =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      first.setAutoCommit(false);
      first.createStatement().executeUpdate("update stream_clock set seq=seq+1 where id=1");
      try (var result =
          second.createStatement().executeQuery("select seq from stream_clock where id=1")) {
        assertTrue(result.next());
        assertEquals(0, result.getLong(1));
      }
      first.commit();
      try (var result =
          second.createStatement().executeQuery("select seq from stream_clock where id=1")) {
        assertTrue(result.next());
        assertEquals(1, result.getLong(1));
      }
    }
  }
}
