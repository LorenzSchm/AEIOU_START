package ai.aeiou.forwarder.fiveu;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One mail as the 5U mailbox returns it (poll item, read-one and send response share this shape).
 *
 * <p>The OpenAPI spec leaves the item untyped, so {@link #from(JsonNode)} maps it defensively:
 * it accepts common alternative field names, address objects, string or numeric ids, and ISO or
 * epoch timestamps. The original JSON is kept in {@link #raw()} for the audit trail.
 *
 * @param references thread ids from the first mail of the thread down to the parent
 */
public record FiveUMessage(
        long id,
        String from,
        String to,
        String subject,
        String body,
        Long inReplyTo,
        List<Long> references,
        OffsetDateTime sentAt,
        List<Attachment> attachments,
        JsonNode raw) {

    public record Attachment(long id, String filename, String contentType, Long size) {}

    /** First mail of the thread: the first reference, else the parent, else this mail itself. */
    public long threadRootId() {
        if (!references.isEmpty()) return references.getFirst();
        return inReplyTo != null ? inReplyTo : id;
    }

    public static FiveUMessage from(JsonNode n) {
        Long id = longOrNull(first(n, "id", "message_id"));
        if (id == null) throw new IllegalArgumentException("5U mail without id: " + n);
        List<Long> refs = new ArrayList<>();
        for (JsonNode r : first(n, "references", "references_ids", "thread")) {
            Long ref = longOrNull(r.isObject() ? r.path("id") : r);
            if (ref != null) refs.add(ref);
        }
        List<Attachment> atts = new ArrayList<>();
        for (JsonNode a : first(n, "attachments")) {
            Long aid = longOrNull(first(a, "id", "attachment_id"));
            if (aid == null) continue;
            atts.add(new Attachment(aid,
                    text(first(a, "filename", "name"), "attachment-" + aid),
                    text(first(a, "content_type", "contentType", "mime_type"), null),
                    longOrNull(first(a, "size", "size_bytes"))));
        }
        return new FiveUMessage(
                id,
                address(first(n, "from", "from_addr", "sender")),
                address(first(n, "to", "to_addr", "recipient")),
                text(n.path("subject"), ""),
                text(first(n, "body", "text", "content"), ""),
                longOrNull(first(n, "in_reply_to", "inReplyTo", "parent_id")),
                List.copyOf(refs),
                timestamp(first(n, "created_at", "sent_at", "timestamp", "date", "received_at")),
                List.copyOf(atts),
                n);
    }

    private static JsonNode first(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = n.path(name);
            if (!v.isMissingNode() && !v.isNull()) return v;
        }
        return n.path(names[0]);
    }

    private static String text(JsonNode v, String fallback) {
        return v.isValueNode() && !v.isNull() ? v.asString() : fallback;
    }

    private static String address(JsonNode v) {
        if (v.isObject()) v = first(v, "address", "email", "addr");
        if (v.isArray() && !v.isEmpty()) return address(v.get(0));
        String s = text(v, "");
        int lt = s.indexOf('<');
        int gt = s.indexOf('>');
        if (lt >= 0 && gt > lt) s = s.substring(lt + 1, gt); // "Name <a@b>"
        return s.trim().toLowerCase();
    }

    private static Long longOrNull(JsonNode v) {
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isString()) {
            try {
                return Long.parseLong(v.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static OffsetDateTime timestamp(JsonNode v) {
        if (v.isNumber()) {
            long epoch = v.asLong();
            Instant i = epoch > 100_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
            return i.atOffset(ZoneOffset.UTC);
        }
        if (v.isString()) {
            String s = v.asString().trim().replaceFirst("^(\\d{4}-\\d{2}-\\d{2}) ", "$1T");
            try {
                return OffsetDateTime.parse(s);
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDateTime.parse(s).atOffset(ZoneOffset.UTC); // naive timestamps are UTC
                } catch (DateTimeParseException alsoIgnored) {
                    // fall through
                }
            }
        }
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
