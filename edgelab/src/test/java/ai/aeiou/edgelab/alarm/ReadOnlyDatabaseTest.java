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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves against the real shared DB that the alarm's session is read-only, without ever sending
 * a write or DDL statement: it only asks the server (SHOW) and the driver (isReadOnly) and then
 * reads one snapshot. Skipped by default; run with EDGELAB_DB_TEST=1 (DATABASE_URL from the env
 * or the repo .env):
 * <pre>EDGELAB_DB_TEST=1 ./gradlew test --tests '*ReadOnlyDatabaseTest'</pre>
 */
@EnabledIfEnvironmentVariable(named = "EDGELAB_DB_TEST", matches = "1")
class ReadOnlyDatabaseTest {

    @Test
    void theSessionIsReadOnlyOnServerAndDriver() throws Exception {
        try (SnapshotSource source = new SnapshotSource(DatabaseConfig.load(Path.of("")))) {
            Connection c = source.connection();
            assertTrue(c.isReadOnly(), "driver connection is not marked read-only");
            assertEquals("on", show(c, "default_transaction_read_only"));
            assertEquals("on", show(c, "transaction_read_only"));

            Snapshot snap = source.read();
            assertFalse(snap.board().isEmpty());
            assertFalse(snap.mail().isEmpty());
        }
    }

    private static String show(Connection c, String setting) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW " + setting)) {
            assertTrue(r.next(), "SHOW " + setting + " returned no row");
            return r.getString(1);
        }
    }
}
