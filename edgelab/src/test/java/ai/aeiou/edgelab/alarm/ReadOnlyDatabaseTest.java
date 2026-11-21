package ai.aeiou.edgelab.alarm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves against the real shared DB that the alarm's session cannot write.
 * Skipped by default; run with EDGELAB_DB_TEST=1 (DATABASE_URL from the env or the repo .env):
 * <pre>EDGELAB_DB_TEST=1 ./gradlew test --tests '*ReadOnlyDatabaseTest'</pre>
 * The write probe is a zero-row UPDATE inside a transaction that is rolled back, so even a
 * misconfigured session could not change anything.
 */
@EnabledIfEnvironmentVariable(named = "EDGELAB_DB_TEST", matches = "1")
class ReadOnlyDatabaseTest {

    @Test
    void theSessionIsReadOnlyAndAWriteIsRejected() throws Exception {
        try (SnapshotSource source = new SnapshotSource(DatabaseConfig.load(Path.of("")))) {
            Connection c = source.connection();
            assertTrue(c.isReadOnly());
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW transaction_read_only")) {
                r.next();
                assertEquals("on", r.getString(1));
            }

            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                SQLException e = assertThrows(SQLException.class,
                        () -> s.executeUpdate("UPDATE board_result SET received = received WHERE false"));
                assertEquals("25006", e.getSQLState(), e.getMessage()); // read_only_sql_transaction
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }

            Snapshot snap = source.read();
            assertFalse(snap.board().isEmpty());
            assertFalse(snap.mail().isEmpty());
        }
    }
}
