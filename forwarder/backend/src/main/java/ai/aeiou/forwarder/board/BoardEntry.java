package ai.aeiou.forwarder.board;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * One request row of {@code GET /v1/board/me}. The 5U spec leaves the body untyped ({@code additionalProperties}),
 * so parsing is deliberately tolerant: known field-name variants are tried in order and the raw row is kept in
 * {@code board_result.raw} so the mapping can be refined once real responses are in.
 *
 * @param mailIds numeric identifiers found on the row (request/message ids); matched against our mail external ids
 * @param refs    short string identifiers (public refs, booking/team references); matched against sessions/bookings
 */
record BoardEntry(String requestKey, String round, String verdict, String reasonCode, Map<String, Double> points,
                  List<Long> mailIds, List<String> refs, OffsetDateTime sentAt, JsonNode raw) {

    private static final List<String> KEY_FIELDS =
            List.of("request_id", "requestId", "request", "id", "message_id", "messageId", "email_id", "mail_id");
    private static final List<String> LIST_FIELDS = List.of("requests", "results", "items", "rows", "entries", "data");
    private static final List<String> ROUND_FIELDS = List.of("round_no", "roundNo", "round");
    private static final List<String> REASON_FIELDS = List.of("reason_code", "reasonCode", "reason");
    private static final List<String> POINTS_FIELDS = List.of("points", "points_by_kind", "pointsByKind", "score");
    private static final int MAX_REF_LENGTH = 40;

    /** Extracts all request rows; rows without any usable key are skipped. Order is as delivered (newest first). */
    static List<BoardEntry> parseAll(JsonNode root) {
        List<BoardEntry> out = new ArrayList<>();
        for (JsonNode row : rows(root)) {
            BoardEntry e = parse(row);
            if (e != null) out.add(e);
        }
        return out;
    }

    private static Iterable<JsonNode> rows(JsonNode root) {
        if (root == null) return List.of();
        if (root.isArray()) return root.values();
        for (String f : LIST_FIELDS) {
            JsonNode n = root.get(f);
            if (n != null && n.isArray()) return n.values();
        }
        // Fallback: the first array of objects that carry a verdict (skips e.g. "bookings").
        for (JsonNode n : root.values()) {
            if (n.isArray() && !n.isEmpty() && n.get(0).isObject() && n.get(0).has("verdict")) return n.values();
        }
        return List.of();
    }

    /**
     * The live rows carry no request id, e.g. {@code {"round_no": 3, "sent_at": "...", "verdict": "accepted",
     * "reason": null}}: the round number is the key then, and {@code sent_at} matches the row to our RFQ by time.
     */
    static BoardEntry parse(JsonNode row) {
        if (row == null || !row.isObject()) return null;
        String round = firstScalar(row, ROUND_FIELDS);
        String key = firstScalar(row, KEY_FIELDS);
        if (key == null && round != null) key = "round:" + round;
        if (key == null) return null;
        String verdict = firstScalar(row, List.of("verdict"));
        return new BoardEntry(key, round,
                verdict == null ? null : verdict.trim().toLowerCase(Locale.ROOT),
                firstScalar(row, REASON_FIELDS), points(row), mailIds(row), refs(row), sentAt(row), row);
    }

    private static OffsetDateTime sentAt(JsonNode row) {
        String raw = firstScalar(row, List.of("sent_at", "sentAt", "received_at"));
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    boolean isFinal() {
        return "accepted".equals(verdict) || "rejected".equals(verdict);
    }

    private static String firstScalar(JsonNode row, List<String> fields) {
        for (String f : fields) {
            JsonNode n = row.get(f);
            if (n != null && n.isValueNode() && !n.isNull() && !n.asString().isBlank()) return n.asString();
        }
        return null;
    }

    private static Map<String, Double> points(JsonNode row) {
        for (String f : POINTS_FIELDS) {
            JsonNode n = row.get(f);
            if (n == null || n.isNull()) continue;
            Map<String, Double> points = new LinkedHashMap<>();
            if (n.isNumber()) {
                points.put("total", n.asDouble());
            } else if (n.isObject()) {
                n.properties().forEach(p -> {
                    if (p.getValue().isNumber()) points.put(p.getKey(), p.getValue().asDouble());
                });
            }
            return points.isEmpty() ? null : points;
        }
        return null;
    }

    /** Integral values (or digit strings) of id-like fields: candidates for our mail external ids. */
    private static List<Long> mailIds(JsonNode row) {
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<String, JsonNode> p : row.properties()) {
            String name = p.getKey().toLowerCase(Locale.ROOT);
            if (!(name.endsWith("id") || name.contains("message") || name.equals("request"))) continue;
            JsonNode v = p.getValue();
            if (v.isIntegralNumber() && v.canConvertToLong()) ids.add(v.asLong());
            else if (v.isString() && v.asString().matches("\\d{1,18}")) ids.add(Long.parseLong(v.asString()));
        }
        return ids;
    }

    /** Short top-level strings, e.g. a team reference that contains our public ref, or a booking reference. */
    private static List<String> refs(JsonNode row) {
        List<String> refs = new ArrayList<>();
        for (JsonNode v : row.values()) {
            if (v.isString() && !v.asString().isBlank() && v.asString().length() <= MAX_REF_LENGTH) {
                refs.add(v.asString().trim());
            }
        }
        return refs;
    }
}
