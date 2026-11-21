package ai.aeiou.forwarder.api.query;

import ai.aeiou.forwarder.api.dto.Attachment;
import ai.aeiou.forwarder.api.dto.Enums.MailDirection;
import ai.aeiou.forwarder.api.dto.MailDetail;
import ai.aeiou.forwarder.api.dto.MailSummary;
import ai.aeiou.forwarder.api.dto.Page;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Mail browser. Lists never load {@code body} or {@code raw}; the snippet is cut in SQL. */
@Repository
public class MailQueries {

    private static final String SUMMARY_COLUMNS = """
            SELECT m.id, m.external_id, m.direction, m.from_addr, m.to_addr, m.subject, left(m.body, 160) AS snippet,
                   m.session_id, s.public_ref AS session_ref, m.route_type, m.route_confidence, m.sent_at,
                   (SELECT count(*) FROM attachment a WHERE a.mail_id = m.id) AS attachment_count
            """;

    private static final String FROM = " FROM mail m LEFT JOIN session s ON s.id = m.session_id";

    private static final RowMapper<MailSummary> SUMMARY = (rs, n) -> new MailSummary(
            rs.getLong("id"),
            rs.getLong("external_id"),
            MailDirection.valueOf(rs.getString("direction")),
            rs.getString("from_addr"),
            rs.getString("to_addr"),
            rs.getString("subject"),
            rs.getString("snippet"),
            Rows.longOrNull(rs, "session_id"),
            rs.getString("session_ref"),
            rs.getString("route_type"),
            Rows.doubleOrNull(rs, "route_confidence"),
            Rows.time(rs, "sent_at"),
            rs.getInt("attachment_count"));

    private static final RowMapper<MailDetail> DETAIL = (rs, n) -> new MailDetail(
            rs.getLong("id"),
            rs.getLong("external_id"),
            MailDirection.valueOf(rs.getString("direction")),
            rs.getString("from_addr"),
            rs.getString("to_addr"),
            rs.getString("subject"),
            rs.getString("snippet"),
            Rows.longOrNull(rs, "session_id"),
            rs.getString("session_ref"),
            rs.getString("route_type"),
            Rows.doubleOrNull(rs, "route_confidence"),
            Rows.time(rs, "sent_at"),
            rs.getInt("attachment_count"),
            rs.getString("body"),
            Rows.longOrNull(rs, "in_reply_to_ext"),
            Rows.longs(rs, "references_ext"),
            List.of());

    private static final String DETAIL_SELECT = SUMMARY_COLUMNS + ", m.body, m.in_reply_to_ext, m.references_ext" + FROM;

    private final JdbcClient jdbc;

    public MailQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first, keyset on {@code (sent_at, id)}; {@code q} uses the {@code search} tsvector (GIN index). */
    public Page<MailSummary> list(MailDirection direction, String q, int limit, Cursor cursor) {
        Where where = new Where();
        if (direction != null) where.and("m.direction = :direction").param("direction", direction.name());
        if (q != null && !q.isBlank()) where.and("m.search @@ plainto_tsquery('simple', :q)").param("q", q);
        where.before("m.sent_at", "m.id", cursor);
        List<MailSummary> rows = jdbc.sql(SUMMARY_COLUMNS + FROM + where.sql()
                        + " ORDER BY m.sent_at DESC, m.id DESC LIMIT :limit")
                .params(where.params())
                .param("limit", limit + 1)
                .query(SUMMARY)
                .list();
        return Page.of(rows, limit, m -> Cursor.of(m.sentAt(), m.id()));
    }

    public Optional<MailDetail> detail(long id) {
        return jdbc.sql(DETAIL_SELECT + " WHERE m.id = :id")
                .param("id", id)
                .query(DETAIL)
                .optional()
                .map(m -> m.withAttachments(attachments(List.of(m.id())).getOrDefault(m.id(), List.of())));
    }

    /** All mails of a session, oldest first, with attachments loaded in one extra query. */
    public List<MailDetail> forSession(long sessionId) {
        List<MailDetail> mails = jdbc.sql(DETAIL_SELECT + " WHERE m.session_id = :id ORDER BY m.sent_at, m.id")
                .param("id", sessionId)
                .query(DETAIL)
                .list();
        if (mails.isEmpty()) return mails;
        Map<Long, List<Attachment>> byMail = attachments(mails.stream().map(MailDetail::id).toList());
        return mails.stream().map(m -> m.withAttachments(byMail.getOrDefault(m.id(), List.of()))).toList();
    }

    private Map<Long, List<Attachment>> attachments(List<Long> mailIds) {
        record Row(long mailId, Attachment attachment) {}
        return jdbc.sql("""
                        SELECT id, mail_id, filename, content_type, size FROM attachment
                        WHERE mail_id IN (:ids) ORDER BY id""")
                .param("ids", mailIds)
                .query((rs, n) -> new Row(rs.getLong("mail_id"), new Attachment(rs.getLong("id"),
                        rs.getString("filename"), rs.getString("content_type"), Rows.longOrNull(rs, "size"))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(Row::mailId,
                        Collectors.mapping(Row::attachment, Collectors.toList())));
    }

    public Optional<AttachmentContent> attachmentContent(long id) {
        return jdbc.sql("SELECT filename, content_type, content FROM attachment WHERE id = :id AND content IS NOT NULL")
                .param("id", id)
                .query((rs, n) -> new AttachmentContent(rs.getString("filename"), rs.getString("content_type"),
                        rs.getBytes("content")))
                .optional();
    }

    public record AttachmentContent(String filename, String contentType, byte[] content) {}
}
