package ai.aeiou.forwarder.mail;

import ai.aeiou.forwarder.fiveu.FiveUMessage;
import ai.aeiou.forwarder.mail.StoredMail.AttachmentRef;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** SQL for the {@code mail}, {@code attachment} and {@code poller_state} tables. */
@Repository
public class MailRepository {

    static final String POLLER_KEY = "mailbox";

    private static final String SELECT_MAIL = """
            SELECT id, external_id, direction, from_addr, to_addr, subject, body, in_reply_to_ext, references_ext,
                   thread_root_ext, session_id, sent_at
            FROM mail""";

    /** A mail row to insert. {@code raw} is JSON text or null. */
    record NewMail(long externalId, String direction, String from, String to, String subject, String body,
                   Long inReplyToExt, List<Long> referencesExt, long threadRootExt, Long sessionId,
                   OffsetDateTime sentAt, String raw) {}

    /** An attachment to insert; {@code content} may be null if the download failed. */
    record NewAttachment(Long externalId, String filename, String contentType, Long size, byte[] content) {}

    private final JdbcClient jdbc;

    public MailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- poller state

    public long sinceId() {
        return jdbc.sql("SELECT since_id FROM poller_state WHERE key = ?").param(POLLER_KEY)
                .query(Long.class).optional().orElse(0L);
    }

    /** Moves the cursor forward only; a stale or repeated value is ignored. */
    public void advanceSinceId(long sinceId) {
        jdbc.sql("""
                        INSERT INTO poller_state (key, since_id, updated_at) VALUES (:key, :since, now())
                        ON CONFLICT (key) DO UPDATE
                        SET since_id = GREATEST(poller_state.since_id, EXCLUDED.since_id), updated_at = now()""")
                .param("key", POLLER_KEY).param("since", sinceId).update();
    }

    // ---------------------------------------------------------------- writes

    /**
     * Stores an incoming 5U mail with its attachments and advances the poll cursor, in one transaction.
     * Returns empty if the mail was already stored (idempotent on {@code external_id}).
     */
    @Transactional
    public Optional<StoredMail> storeIncoming(FiveUMessage msg, String rawJson, List<NewAttachment> attachments) {
        NewMail row = new NewMail(msg.id(), "in", msg.from(), msg.to(), msg.subject(), msg.body(), msg.inReplyTo(),
                msg.references(), msg.threadRootId(), null, msg.sentAt(), rawJson);
        Optional<Long> id = insert(row);
        id.ifPresent(mailId -> attachments.forEach(a -> insertAttachment(mailId, a)));
        advanceSinceId(msg.id());
        return id.flatMap(this::findById);
    }

    /** Inserts a mail; empty if {@code external_id} already exists. Bumps the session's mail counter. */
    @Transactional
    public Optional<Long> insert(NewMail m) {
        Optional<Long> id = jdbc.sql("""
                        INSERT INTO mail (external_id, direction, from_addr, to_addr, subject, body, in_reply_to_ext,
                                          references_ext, thread_root_ext, session_id, sent_at, raw)
                        VALUES (:ext, :dir, :from, :to, :subject, :body, :inReplyTo, :refs, :root, :session, :sentAt,
                                CAST(:raw AS jsonb))
                        ON CONFLICT (external_id) DO NOTHING
                        RETURNING id""")
                .param("ext", m.externalId())
                .param("dir", m.direction())
                .param("from", m.from())
                .param("to", m.to())
                .param("subject", m.subject() == null ? "" : m.subject())
                .param("body", m.body() == null ? "" : m.body())
                .param("inReplyTo", m.inReplyToExt())
                .param("refs", m.referencesExt().toArray(Long[]::new))
                .param("root", m.threadRootExt())
                .param("session", m.sessionId())
                .param("sentAt", m.sentAt())
                .param("raw", m.raw())
                .query(Long.class)
                .optional();
        if (id.isPresent() && m.sessionId() != null) incrementMailCount(m.sessionId());
        return id;
    }

    public void insertAttachment(long mailId, NewAttachment a) {
        jdbc.sql("""
                        INSERT INTO attachment (mail_id, external_id, filename, content_type, size, content)
                        VALUES (:mail, :ext, :name, :type, :size, :content)""")
                .param("mail", mailId)
                .param("ext", a.externalId())
                .param("name", a.filename())
                .param("type", a.contentType())
                .param("size", a.size() != null ? a.size() : a.content() == null ? null : (long) a.content().length)
                .param("content", a.content())
                .update();
    }

    /** Links a mail to its session (only once) and bumps the session's mail counter. */
    @Transactional
    public void assignSession(long mailId, long sessionId) {
        int changed = jdbc.sql("UPDATE mail SET session_id = ? WHERE id = ? AND session_id IS NULL")
                .params(sessionId, mailId).update();
        if (changed > 0) incrementMailCount(sessionId);
    }

    public void setRoute(long mailId, String routeType, double confidence) {
        jdbc.sql("UPDATE mail SET route_type = ?, route_confidence = ? WHERE id = ?")
                .params(routeType, confidence, mailId).update();
    }

    /** True for an airline or trucker on the carrier list. */
    public boolean isCarrierAddress(String address) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM carrier WHERE lower(email) = lower(:a))")
                .param("a", address.trim()).query(Boolean.class).single();
    }

    public long nextDryRunId() {
        return jdbc.sql("SELECT nextval('dry_run_mail_seq')").query(Long.class).single();
    }

    private void incrementMailCount(long sessionId) {
        jdbc.sql("UPDATE session SET mail_count = mail_count + 1, updated_at = now() WHERE id = ?")
                .param(sessionId).update();
    }

    // ---------------------------------------------------------------- reads

    public boolean existsByExternalId(long externalId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM mail WHERE external_id = ?)").param(externalId)
                .query(Boolean.class).single();
    }

    public Optional<StoredMail> findById(long id) {
        return jdbc.sql(SELECT_MAIL + " WHERE id = ?").param(id).query(MailRepository::map).optional()
                .map(this::withAttachments);
    }

    public Optional<StoredMail> findByExternalId(long externalId) {
        return jdbc.sql(SELECT_MAIL + " WHERE external_id = ?").param(externalId).query(MailRepository::map).optional()
                .map(this::withAttachments);
    }

    /** The latest {@code limit} mails of a session, oldest first, excluding {@code excludeMailId}. */
    public List<StoredMail> recentInSession(long sessionId, long excludeMailId, int limit) {
        List<StoredMail> newestFirst = jdbc.sql(SELECT_MAIL
                        + " WHERE session_id = ? AND id <> ? ORDER BY sent_at DESC, id DESC LIMIT ?")
                .params(sessionId, excludeMailId, limit)
                .query(MailRepository::map)
                .list();
        return newestFirst.reversed().stream().map(this::withAttachments).toList();
    }

    public Optional<String> routeType(long mailId) {
        return jdbc.sql("SELECT route_type FROM mail WHERE id = ?").param(mailId).query(String.class).optional();
    }

    public byte[] attachmentContent(long attachmentId) {
        return jdbc.sql("SELECT content FROM attachment WHERE id = ?").param(attachmentId)
                .query((rs, i) -> rs.getBytes(1)).optional().orElse(null);
    }

    private List<AttachmentRef> attachmentsOf(long mailId) {
        return jdbc.sql("SELECT id, external_id, filename, content_type, size FROM attachment WHERE mail_id = ? ORDER BY id")
                .param(mailId)
                .query((rs, i) -> new AttachmentRef(rs.getLong("id"), rs.getObject("external_id", Long.class),
                        rs.getString("filename"), rs.getString("content_type"), rs.getObject("size", Long.class)))
                .list();
    }

    /** Attachments are loaded in a second query, never from inside a row mapper. */
    private StoredMail withAttachments(StoredMail m) {
        return new StoredMail(m.id(), m.externalId(), m.direction(), m.fromAddr(), m.toAddr(), m.subject(), m.body(),
                m.inReplyToExt(), m.referencesExt(), m.threadRootExt(), m.sessionId(), m.sentAt(), attachmentsOf(m.id()));
    }

    private static StoredMail map(ResultSet rs, int row) throws SQLException {
        return new StoredMail(
                rs.getLong("id"),
                rs.getLong("external_id"),
                rs.getString("direction"),
                rs.getString("from_addr"),
                rs.getString("to_addr"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getObject("in_reply_to_ext", Long.class),
                longs(rs.getArray("references_ext")),
                rs.getObject("thread_root_ext", Long.class),
                rs.getObject("session_id", Long.class),
                rs.getObject("sent_at", OffsetDateTime.class),
                List.of());
    }

    private static List<Long> longs(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] values = (Object[]) array.getArray();
        List<Long> out = new ArrayList<>(values.length);
        Arrays.stream(values).forEach(v -> out.add(((Number) v).longValue()));
        return List.copyOf(out);
    }
}
