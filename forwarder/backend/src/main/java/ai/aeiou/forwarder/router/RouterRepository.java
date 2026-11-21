package ai.aeiou.forwarder.router;

import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.router.Stage1Classifier.Carrier;
import ai.aeiou.forwarder.router.Stage1Classifier.Facts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Lookups the router needs to place a mail: carriers, bid requests, threads, bookings. */
@Repository
class RouterRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    RouterRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Collects the stage-1 facts of a stored incoming mail. */
    Facts facts(StoredMail mail) {
        List<Long> threadIds = threadIds(mail);
        Optional<Carrier> carrier = carrier(mail.fromAddr());
        Long bidSession = carrier.isPresent() ? bidRequestSession(threadIds).orElse(null) : null;
        Optional<ThreadSession> thread = threadSession(mail, threadIds);
        Long bookingSession = Stage1Classifier.bookingReference(mail.subject()).flatMap(this::bookingSession).orElse(null);
        return new Facts(mail.fromAddr(), mail.subject(), !mail.attachments().isEmpty(), carrier.orElse(null),
                bidSession, thread.map(ThreadSession::sessionId).orElse(null),
                thread.map(ThreadSession::type).orElse(null), bookingSession);
    }

    record ThreadSession(long sessionId, String type) {}

    Optional<Carrier> carrier(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return jdbc.sql("SELECT id, name, kind FROM carrier WHERE lower(email) = lower(?)")
                .param(email.trim())
                .query((rs, i) -> new Carrier(rs.getLong("id"), rs.getString("name"), rs.getString("kind")))
                .optional();
    }

    /** The session of the bid request whose outgoing mail is the parent (or an ancestor) of this mail. */
    Optional<Long> bidRequestSession(List<Long> threadIds) {
        if (threadIds.isEmpty()) return Optional.empty();
        return jdbc.sql("""
                        SELECT br.session_id FROM bid_request br JOIN mail m ON m.id = br.out_mail_id
                        WHERE m.external_id IN (:ids) ORDER BY br.id DESC LIMIT 1""")
                .param("ids", threadIds)
                .query(Long.class)
                .optional();
    }

    /** The session of an earlier mail in the same thread (by references, in_reply_to or thread root). */
    Optional<ThreadSession> threadSession(StoredMail mail, List<Long> threadIds) {
        if (threadIds.isEmpty()) return Optional.empty();
        return jdbc.sql("""
                        SELECT s.id, s.type FROM mail m JOIN session s ON s.id = m.session_id
                        WHERE m.id <> :self AND (m.external_id IN (:ids) OR m.thread_root_ext = :root)
                        ORDER BY m.sent_at, m.id LIMIT 1""")
                .param("self", mail.id())
                .param("ids", threadIds)
                .param("root", mail.threadRootExt())
                .query((rs, i) -> new ThreadSession(rs.getLong("id"), rs.getString("type")))
                .optional();
    }

    Optional<Long> bookingSession(String bookingReference) {
        return jdbc.sql("SELECT session_id FROM booking WHERE booking_reference = ? ORDER BY id DESC LIMIT 1")
                .param(bookingReference)
                .query(Long.class)
                .optional();
    }

    /** The extraction stored with the latest route decision of a mail (used when a human re-routes). */
    Optional<Extraction> lastExtraction(long mailId) {
        return jdbc.sql("""
                        SELECT payload::text FROM event
                        WHERE mail_id = ? AND kind = 'route.decision' ORDER BY id DESC LIMIT 1""")
                .param(mailId)
                .query(String.class)
                .optional()
                .map(json::readTree)
                .map(p -> p.path("extraction"))
                .filter(JsonNode::isObject)
                .map(node -> json.treeToValue(node, Extraction.class));
    }

    /** Parent first, then the rest of the thread, without the mail itself. */
    private static List<Long> threadIds(StoredMail mail) {
        List<Long> ids = new ArrayList<>();
        if (mail.inReplyToExt() != null) ids.add(mail.inReplyToExt());
        mail.referencesExt().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        return ids;
    }
}
