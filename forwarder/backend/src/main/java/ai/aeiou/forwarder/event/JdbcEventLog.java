package ai.aeiou.forwarder.event;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link EventLog} on the {@code event} table. The insert and the session's denormalized counters
 * are written in one transaction; {@link EventAppended} and {@link SessionChanged} are published
 * after commit.
 */
@Service
public class JdbcEventLog implements EventLog {

    /** Keeps list views cheap: the session row only stores a short summary. */
    private static final int MAX_SESSION_SUMMARY = 300;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AfterCommitPublisher publisher;

    public JdbcEventLog(JdbcClient jdbc, JsonMapper json, AfterCommitPublisher publisher) {
        this.jdbc = jdbc;
        this.json = json;
        this.publisher = publisher;
    }

    @Override
    @Transactional
    public EventRecord append(Long sessionId, Long mailId, String kind, Actor actor, String summary, Object payload) {
        String payloadJson = payload == null ? null : json.writeValueAsString(payload);
        EventRecord record = jdbc.sql("""
                        INSERT INTO event (session_id, mail_id, kind, actor, summary, payload)
                        VALUES (:sessionId, :mailId, :kind, :actor, :summary, CAST(:payload AS jsonb))
                        RETURNING id, created_at""")
                .param("sessionId", sessionId)
                .param("mailId", mailId)
                .param("kind", kind)
                .param("actor", actor.name())
                .param("summary", summary)
                .param("payload", payloadJson)
                .query((rs, i) -> new EventRecord(rs.getLong("id"), sessionId, mailId, kind, actor, summary,
                        rs.getObject("created_at", OffsetDateTime.class)))
                .single();

        if (sessionId != null) {
            jdbc.sql("""
                            UPDATE session SET event_count = event_count + 1, last_event_at = :at,
                                   last_event_summary = :summary, updated_at = now()
                            WHERE id = :id""")
                    .param("at", record.createdAt())
                    .param("summary", truncate(summary))
                    .param("id", sessionId)
                    .update();
        }

        publisher.publish(new EventAppended(record.id(), sessionId, mailId, kind, actor.name(), summary,
                record.createdAt(), payload != null));
        if (sessionId != null) publisher.sessionChanged(sessionId);
        return record;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_SESSION_SUMMARY ? s : s.substring(0, MAX_SESSION_SUMMARY - 1) + "…";
    }
}
