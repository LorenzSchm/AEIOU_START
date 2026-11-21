package ai.aeiou.edgelab.alarm;

import ai.aeiou.edgelab.alarm.Snapshot.Attachment;
import ai.aeiou.edgelab.alarm.Snapshot.BoardRow;
import ai.aeiou.edgelab.alarm.Snapshot.MailRow;
import ai.aeiou.edgelab.alarm.Snapshot.WaveCaseRow;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads one Snapshot from the shared Postgres. Strictly read-only: the session starts with
 * default_transaction_read_only=on, the connection is marked read-only, and only SELECTs run.
 */
public final class SnapshotSource implements AutoCloseable {

    private final DatabaseConfig config;
    private Connection connection;

    public SnapshotSource(DatabaseConfig config) {
        this.config = config;
    }

    /** Opens (or reuses) a read-only connection and fails if the server does not confirm read-only. */
    public Connection connection() throws SQLException {
        if (connection != null && connection.isValid(5)) return connection;
        close();
        Properties p = new Properties();
        if (config.user() != null) p.setProperty("user", config.user());
        if (config.password() != null) p.setProperty("password", config.password());
        p.setProperty("connectTimeout", "10");
        p.setProperty("socketTimeout", "30");
        p.setProperty("ApplicationName", "edgelab-alarm-readonly");
        Connection c = DriverManager.getConnection(config.jdbcUrl(), p);
        try {
            c.setAutoCommit(true);
            c.setReadOnly(true);
            try (Statement s = c.createStatement()) {
                s.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                try (ResultSet r = s.executeQuery("SHOW default_transaction_read_only")) {
                    if (!r.next() || !"on".equalsIgnoreCase(r.getString(1))) {
                        throw new SQLException("session is not read-only; refusing to continue");
                    }
                }
            }
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        connection = c;
        return c;
    }

    public Snapshot read() throws SQLException {
        Connection c = connection();
        try (Statement s = c.createStatement()) {
            Instant now;
            try (ResultSet r = s.executeQuery("SELECT now()")) {
                r.next();
                now = r.getObject(1, OffsetDateTime.class).toInstant();
            }
            return new Snapshot(now, board(s), mail(s), waveCases(s));
        }
    }

    private static List<BoardRow> board(Statement s) throws SQLException {
        List<BoardRow> out = new ArrayList<>();
        try (ResultSet r = s.executeQuery("SELECT result::text FROM board_result ORDER BY received")) {
            while (r.next()) {
                JsonNode j = parse(r.getString(1));
                if (j == null || !j.hasNonNull("round_no")) continue;
                Map<String, Double> points = new LinkedHashMap<>();
                j.path("points").properties().forEach(e -> {
                    if (e.getValue().isNumber()) points.put(e.getKey(), e.getValue().asDouble());
                });
                out.add(new BoardRow(j.get("round_no").asInt(), instant(j, "sent_at"), instant(j, "answered_at"),
                        text(j, "verdict"), text(j, "reason"), points));
            }
        }
        return out;
    }

    private static List<MailRow> mail(Statement s) throws SQLException {
        List<MailRow> out = new ArrayList<>();
        try (ResultSet r = s.executeQuery("SELECT id, root, sender, subject, received, kind, stage, "
                + "attachments::text, proposal IS NOT NULL FROM mail ORDER BY received, id")) {
            while (r.next()) {
                List<Attachment> atts = new ArrayList<>();
                JsonNode a = parse(r.getString(8));
                if (a != null && a.isArray()) {
                    a.forEach(n -> atts.add(new Attachment(text(n, "filename"), text(n, "content_type"))));
                }
                long root = r.getLong(2);
                Long rootOrNull = r.wasNull() ? null : root;
                OffsetDateTime received = r.getObject(5, OffsetDateTime.class);
                out.add(new MailRow(r.getLong(1), rootOrNull, r.getString(3), r.getString(4),
                        received == null ? null : received.toInstant(), r.getString(6), r.getString(7), atts,
                        r.getBoolean(9)));
            }
        }
        return out;
    }

    private static List<WaveCaseRow> waveCases(Statement s) throws SQLException {
        List<WaveCaseRow> out = new ArrayList<>();
        try (ResultSet r = s.executeQuery("SELECT id, reason, opened FROM wave_case ORDER BY id")) {
            while (r.next()) {
                long opened = r.getLong(3);
                Long openedOrNull = r.wasNull() ? null : opened;
                out.add(new WaveCaseRow(r.getLong(1), r.getString(2), openedOrNull));
            }
        }
        return out;
    }

    private static JsonNode parse(String json) {
        if (json == null) return null;
        try {
            return Json.MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode j, String field) {
        JsonNode n = j.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Instant instant(JsonNode j, String field) {
        String t = text(j, field);
        if (t == null) return null;
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // closing anyway
            }
            connection = null;
        }
    }
}
