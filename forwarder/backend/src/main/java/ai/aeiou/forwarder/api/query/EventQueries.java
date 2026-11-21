package ai.aeiou.forwarder.api.query;

import ai.aeiou.forwarder.api.dto.EventItem;
import ai.aeiou.forwarder.api.dto.EventPayload;
import ai.aeiou.forwarder.api.dto.Page;
import ai.aeiou.forwarder.event.Actor;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Event lists never touch {@code payload}; {@code hasPayload} only reads the null bitmap, no detoasting. */
@Repository
public class EventQueries {

    private static final String ITEM_FROM = """
            SELECT e.id, e.session_id, e.mail_id, e.kind, e.actor, e.summary, e.created_at,
                   e.payload IS NOT NULL AS has_payload
            FROM event e
            """;

    private static final RowMapper<EventItem> ITEM = (rs, n) -> new EventItem(
            rs.getLong("id"),
            Rows.longOrNull(rs, "session_id"),
            Rows.longOrNull(rs, "mail_id"),
            rs.getString("kind"),
            Actor.valueOf(rs.getString("actor")),
            rs.getString("summary"),
            Rows.time(rs, "created_at"),
            rs.getBoolean("has_payload"));

    private final JdbcClient jdbc;
    private final JsonColumns json;

    public EventQueries(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Session timeline, oldest first (index {@code event_session_idx}). */
    public Page<EventItem> forSession(long sessionId, int limit, Cursor cursor) {
        Where where = new Where().and("e.session_id = :sessionId").param("sessionId", sessionId)
                .idAfter("e.id", cursor);
        List<EventItem> rows = jdbc.sql(ITEM_FROM + where.sql() + " ORDER BY e.id LIMIT :limit")
                .params(where.params())
                .param("limit", limit + 1)
                .query(ITEM)
                .list();
        return Page.of(rows, limit, e -> Cursor.of(e.id()));
    }

    /** Global feed, newest first. Ids grow with time, so the primary key (or {@code event_kind_id_idx}) orders it. */
    public Page<EventItem> feed(String kind, int limit, Cursor cursor) {
        Where where = new Where();
        if (kind != null && !kind.isBlank()) where.and("e.kind = :kind").param("kind", kind);
        where.idBefore("e.id", cursor);
        List<EventItem> rows = jdbc.sql(ITEM_FROM + where.sql() + " ORDER BY e.id DESC LIMIT :limit")
                .params(where.params())
                .param("limit", limit + 1)
                .query(ITEM)
                .list();
        return Page.of(rows, limit, e -> Cursor.of(e.id()));
    }

    public Optional<EventPayload> payload(long id) {
        return jdbc.sql("SELECT id, payload::text AS payload FROM event WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new EventPayload(rs.getLong("id"), json.value(rs.getString("payload"))))
                .optional();
    }
}
