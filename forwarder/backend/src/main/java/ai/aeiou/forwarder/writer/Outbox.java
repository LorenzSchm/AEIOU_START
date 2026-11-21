package ai.aeiou.forwarder.writer;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.session.SessionService.SessionView;
import ai.aeiou.forwarder.settings.SettingsService;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The last step before a written mail leaves: it is sent, or, when autopilot is off (globally, or a human took the
 * session over), stored as the session's pending {@code draft}. A needs-human flag never holds a mail back.
 */
@Component
public class Outbox {

    public enum Delivery { SENT, DRAFTED }

    private final MailGateway mailGateway;
    private final SettingsService settings;
    private final EventLog events;
    private final JdbcClient jdbc;

    public Outbox(MailGateway mailGateway, SettingsService settings, EventLog events, JdbcClient jdbc) {
        this.mailGateway = mailGateway;
        this.settings = settings;
        this.events = events;
        this.jdbc = jdbc;
    }

    public boolean mayAutoSend(SessionView session) {
        // needs-human is an attention marker only; it never holds a mail back
        return session.autopilot() && settings.getBool("autopilot", false);
    }

    /** Sends when autopilot allows it, otherwise drafts. */
    public Delivery deliver(SessionView session, OutgoingMail mail, String purpose) {
        if (mayAutoSend(session)) {
            mailGateway.send(session.id(), mail);
            return Delivery.SENT;
        }
        draft(session.id(), mail, purpose, "autopilot off or a human took the session over");
        return Delivery.DRAFTED;
    }

    /** Stores (or replaces) the session's pending draft for a human to approve, edit or drop. */
    public void draft(long sessionId, OutgoingMail mail, String purpose, String reason) {
        jdbc.sql("""
                        INSERT INTO draft (session_id, purpose, to_addr, subject, body, in_reply_to_ext, meta)
                        VALUES (:sessionId, :purpose, :to, :subject, :body, :inReplyTo, CAST(:meta AS jsonb))
                        ON CONFLICT (session_id) DO UPDATE SET purpose = EXCLUDED.purpose, to_addr = EXCLUDED.to_addr,
                            subject = EXCLUDED.subject, body = EXCLUDED.body, in_reply_to_ext = EXCLUDED.in_reply_to_ext,
                            meta = EXCLUDED.meta, created_at = now()
                        """)
                .param("sessionId", sessionId)
                .param("purpose", purpose)
                .param("to", mail.to())
                .param("subject", mail.subject())
                .param("body", mail.body())
                .param("inReplyTo", mail.inReplyToExt())
                .param("meta", "{\"reason\":" + jsonString(reason) + "}")
                .update();
        events.append(sessionId, "draft.created", Actor.system, "Draft " + purpose + " to " + mail.to() + " waits for a human",
                Map.of("purpose", purpose, "to", mail.to(), "reason", reason));
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
