package ai.aeiou.forwarder.api.port;

import ai.aeiou.forwarder.api.ApiException;
import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.event.SessionChanged;
import ai.aeiou.forwarder.router.MailType;
import ai.aeiou.forwarder.session.SessionService;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Session actions on top of the shared contracts. Sending and re-routing are delegated to the RFQ module and the
 * router through {@link DraftSender} / {@link MailRerouter}; those calls do I/O, so they run outside any DB
 * transaction and the {@code human.*} event is written once they succeeded.
 */
@Service
public class DefaultSessionActions implements SessionActions {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventLog events;
    private final SessionService sessions;
    private final ObjectProvider<DraftSender> draftSender;
    private final ObjectProvider<MailRerouter> rerouter;
    private final ApplicationEventPublisher publisher;

    public DefaultSessionActions(JdbcClient jdbc, TransactionTemplate tx, EventLog events, SessionService sessions,
                                 ObjectProvider<DraftSender> draftSender, ObjectProvider<MailRerouter> rerouter,
                                 ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.sessions = sessions;
        this.draftSender = draftSender;
        this.rerouter = rerouter;
        this.publisher = publisher;
    }

    @Override
    public void approve(long sessionId) {
        requireDraft(sessionId);
        required(draftSender, "approve needs QuoteSender.sendDraft").sendDraft(sessionId, null);
        log(sessionId, "human.approve", "Draft approved and sent", Map.of());
    }

    @Override
    public void editAndSend(long sessionId, String body) {
        requireDraft(sessionId);
        required(draftSender, "edit_and_send needs QuoteSender.sendDraft").sendDraft(sessionId, body);
        log(sessionId, "human.edit_send", "Edited draft sent", Map.of("body", body));
    }

    @Override
    public void reroute(long sessionId, MailType type) {
        long mailId = jdbc.sql("""
                        SELECT id FROM mail WHERE session_id = :id AND direction = 'in'
                        ORDER BY sent_at DESC, id DESC LIMIT 1""")
                .param("id", sessionId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> ApiException.conflict("no_inbound_mail", "Session has no inbound mail to re-route"));
        required(rerouter, "reroute needs Router.reroute").reroute(mailId, type);
        events.append(sessionId, mailId, "human.reroute", Actor.human, "Re-routed latest mail as " + type,
                Map.of("mailId", mailId, "type", type.name()));
    }

    @Override
    public void takeOver(long sessionId) {
        setAutopilot(sessionId, false, "human.take_over", "Human took over, autopilot off");
    }

    @Override
    public void release(long sessionId) {
        setAutopilot(sessionId, true, "human.release", "Released back to autopilot");
    }

    @Override
    public void resolve(long sessionId, String note) {
        tx.executeWithoutResult(s -> {
            sessions.clearNeedsHuman(sessionId);
            log(sessionId, "human.resolve", note == null || note.isBlank() ? "Resolved" : "Resolved: " + note,
                    note == null ? Map.of() : Map.of("note", note));
        });
        publisher.publishEvent(new SessionChanged(sessionId));
    }

    private void setAutopilot(long sessionId, boolean on, String kind, String summary) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE session SET autopilot = :on, updated_at = now() WHERE id = :id")
                    .param("on", on)
                    .param("id", sessionId)
                    .update();
            log(sessionId, kind, summary, Map.of("autopilot", on));
        });
        publisher.publishEvent(new SessionChanged(sessionId));
    }

    private void requireDraft(long sessionId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM draft WHERE session_id = :id)")
                .param("id", sessionId)
                .query(Boolean.class)
                .single();
        if (!exists) throw ApiException.conflict("no_draft", "Session " + sessionId + " has no pending draft");
    }

    private void log(long sessionId, String kind, String summary, Map<String, Object> payload) {
        events.append(sessionId, null, kind, Actor.human, summary, payload);
    }

    private static <T> T required(ObjectProvider<T> provider, String what) {
        T bean = provider.getIfAvailable();
        if (bean == null) throw ApiException.notImplemented("Not wired yet: " + what);
        return bean;
    }
}
