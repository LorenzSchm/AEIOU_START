package ai.aeiou.forwarder.session;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.AfterCommitPublisher;
import ai.aeiou.forwarder.event.EventLog;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SessionService} on the {@code session} table. Every change publishes {@code SessionChanged}
 * after commit (directly or through the event it writes), so the UI list stays live.
 */
@Service
public class JdbcSessionService implements SessionService {

    private static final int MAX_REF_ATTEMPTS = 10;

    private static final String SELECT = """
            SELECT id, public_ref, type, status, customer_addr, subject, mode, needs_human, autopilot,
                   deadline_at, quoted_at, quoted_total, quoted_currency, booked, created_at
            FROM session""";

    private final JdbcClient jdbc;
    private final EventLog events;
    private final AfterCommitPublisher publisher;

    public JdbcSessionService(JdbcClient jdbc, EventLog events, AfterCommitPublisher publisher) {
        this.jdbc = jdbc;
        this.events = events;
        this.publisher = publisher;
    }

    @Override
    @Transactional
    public long create(String type, String customerAddr, String subject, OffsetDateTime deadlineAt) {
        for (int attempt = 0; attempt < MAX_REF_ATTEMPTS; attempt++) {
            String ref = PublicRefGenerator.next();
            Optional<Long> id = jdbc.sql("""
                            INSERT INTO session (public_ref, type, status, customer_addr, subject, deadline_at)
                            VALUES (:ref, :type, :status, :customer, :subject, :deadline)
                            ON CONFLICT (public_ref) DO NOTHING
                            RETURNING id""")
                    .param("ref", ref)
                    .param("type", type)
                    .param("status", SessionStatus.NEW.name())
                    .param("customer", customerAddr)
                    .param("subject", subject)
                    .param("deadline", deadlineAt)
                    .query(Long.class)
                    .optional();
            if (id.isPresent()) {
                events.append(id.get(), "session.created", Actor.system, "Session " + ref + " opened (" + type + ")",
                        Map.of("publicRef", ref, "type", type));
                return id.get();
            }
        }
        throw new IllegalStateException("Could not allocate a unique public_ref");
    }

    @Override
    public Optional<SessionView> find(long sessionId) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(sessionId).query(JdbcSessionService::map).optional();
    }

    @Override
    public Optional<SessionView> findByPublicRef(String publicRef) {
        return jdbc.sql(SELECT + " WHERE public_ref = ?").param(publicRef.trim().toUpperCase())
                .query(JdbcSessionService::map).optional();
    }

    @Override
    @Transactional
    public void setStatus(long sessionId, SessionStatus status) {
        Optional<String> previous = jdbc.sql("SELECT status FROM session WHERE id = ? FOR UPDATE")
                .param(sessionId).query(String.class).optional();
        if (previous.isEmpty() || previous.get().equals(status.name())) return;
        jdbc.sql("UPDATE session SET status = ?, updated_at = now() WHERE id = ?")
                .params(status.name(), sessionId).update();
        events.append(sessionId, "session.status", Actor.system, "Status " + previous.get() + " → " + status,
                Map.of("from", previous.get(), "to", status.name()));
    }

    @Override
    @Transactional
    public void setMode(long sessionId, String mode) {
        if (jdbc.sql("UPDATE session SET mode = ?, updated_at = now() WHERE id = ?").params(mode, sessionId).update() > 0) {
            publisher.sessionChanged(sessionId);
        }
    }

    @Override
    @Transactional
    public void setType(long sessionId, String type, OffsetDateTime deadlineAt) {
        jdbc.sql("""
                        UPDATE session SET type = :type, deadline_at = COALESCE(:deadline, deadline_at), updated_at = now()
                        WHERE id = :id""")
                .param("type", type).param("deadline", deadlineAt).param("id", sessionId)
                .update();
        publisher.sessionChanged(sessionId);
    }

    @Override
    @Transactional
    public void flagNeedsHuman(long sessionId, String reason) {
        jdbc.sql("UPDATE session SET needs_human = true, needs_human_reason = ?, updated_at = now() WHERE id = ?")
                .params(reason, sessionId).update();
        events.append(sessionId, "human.needed", Actor.system, "Needs a human: " + reason, Map.of("reason", reason));
    }

    @Override
    @Transactional
    public void clearNeedsHuman(long sessionId) {
        int changed = jdbc.sql("""
                        UPDATE session SET needs_human = false, needs_human_reason = NULL, updated_at = now()
                        WHERE id = ? AND needs_human""")
                .param(sessionId).update();
        if (changed > 0) events.append(sessionId, "human.resolved", Actor.human, "Marked as resolved", null);
    }

    @Override
    @Transactional
    public boolean claimQuote(long sessionId, BigDecimal total, String currency, BigDecimal totalEur) {
        int claimed = jdbc.sql("""
                        UPDATE session SET quoted_at = now(), quoted_total = :total, quoted_currency = :currency,
                               quoted_total_eur = :totalEur, updated_at = now()
                        WHERE id = :id AND quoted_at IS NULL""")
                .param("total", total).param("currency", currency).param("totalEur", totalEur).param("id", sessionId)
                .update();
        if (claimed == 1) publisher.sessionChanged(sessionId);
        return claimed == 1;
    }

    @Override
    @Transactional
    public void releaseQuote(long sessionId) {
        jdbc.sql("""
                        UPDATE session SET quoted_at = NULL, quoted_total = NULL, quoted_currency = NULL,
                               quoted_total_eur = NULL, updated_at = now()
                        WHERE id = ?""")
                .param(sessionId).update();
        events.append(sessionId, "quote.released", Actor.system, "Quote claim released (sending failed)", null);
    }

    @Override
    @Transactional
    public void markBooked(long sessionId) {
        if (jdbc.sql("UPDATE session SET booked = true, updated_at = now() WHERE id = ?").param(sessionId).update() > 0) {
            publisher.sessionChanged(sessionId);
        }
    }

    private static SessionView map(ResultSet rs, int row) throws SQLException {
        return new SessionView(
                rs.getLong("id"),
                rs.getString("public_ref"),
                rs.getString("type"),
                SessionStatus.valueOf(rs.getString("status")),
                rs.getString("customer_addr"),
                rs.getString("subject"),
                rs.getString("mode"),
                rs.getBoolean("needs_human"),
                rs.getBoolean("autopilot"),
                rs.getObject("deadline_at", OffsetDateTime.class),
                rs.getObject("quoted_at", OffsetDateTime.class),
                rs.getBigDecimal("quoted_total"),
                rs.getString("quoted_currency"),
                rs.getBoolean("booked"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
