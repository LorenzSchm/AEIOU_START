package ai.aeiou.forwarder.board;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a {@code /v1/board/me} snapshot in {@code board_result} and turns differences into events: a
 * {@code board.verdict} per new or changed verdict, and a {@code system.alert} (no session) when two final verdicts
 * in a row are rejections or a reason code shows up that we have never seen. Those are the early warning for the
 * unannounced hourly changes (ARCHITECTURE.md §12).
 */
@Service
public class BoardIngest {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventLog events;
    private final JsonMapper mapper;

    /** Rejections in a row across polls, counted over final verdicts in chronological order. */
    private int rejectionStreak;

    public BoardIngest(JdbcClient jdbc, TransactionTemplate tx, EventLog events, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.mapper = mapper;
    }

    public record Result(int seen, int changed, int alerts) {}

    public synchronized Result ingest(String body) {
        List<BoardEntry> entries = BoardEntry.parseAll(mapper.readTree(body));
        if (entries.isEmpty()) return new Result(0, 0, 0);
        return tx.execute(s -> store(entries.reversed()));  // the board lists newest first
    }

    private Result store(List<BoardEntry> chronological) {
        Map<String, Stored> existing = existing(chronological);
        Set<String> knownReasons = new HashSet<>(jdbc.sql(
                "SELECT DISTINCT reason_code FROM board_result WHERE reason_code IS NOT NULL").query(String.class).list());

        int changed = 0;
        int alerts = 0;
        Set<String> newReasons = new LinkedHashSet<>();
        for (BoardEntry e : chronological) {
            Stored before = existing.get(e.requestKey());
            Long sessionId = before != null && before.sessionId() != null ? before.sessionId() : resolveSession(e);
            upsert(e, sessionId);
            boolean isChange = before == null
                    || !Objects.equals(before.verdict(), e.verdict())
                    || !Objects.equals(before.reasonCode(), e.reasonCode());
            if (!isChange) continue;

            changed++;
            events.append(sessionId, null, "board.verdict", Actor.system, summary(e), verdictPayload(e, before));
            if (e.reasonCode() != null && !knownReasons.contains(e.reasonCode())) newReasons.add(e.reasonCode());
            if (e.isFinal() && trackStreak(e)) {
                alerts++;
                events.append(null, null, "system.alert", Actor.system,
                        "Two board rejections in a row (latest: " + orUnknown(e.reasonCode()) + ")",
                        Map.of("type", "rejection_streak", "requestKey", e.requestKey(),
                                "reasonCode", orUnknown(e.reasonCode())));
            }
        }
        if (!newReasons.isEmpty()) {
            alerts++;
            events.append(null, null, "system.alert", Actor.system,
                    "New board reason code: " + String.join(", ", newReasons),
                    Map.of("type", "new_reason_code", "reasonCodes", List.copyOf(newReasons)));
        }
        return new Result(chronological.size(), changed, alerts);
    }

    /** Returns true exactly when a streak reaches two rejections; an acceptance resets it. */
    private boolean trackStreak(BoardEntry e) {
        if (!"rejected".equals(e.verdict())) {
            rejectionStreak = 0;
            return false;
        }
        return ++rejectionStreak == 2;
    }

    private record Stored(String requestKey, Long sessionId, String verdict, String reasonCode) {}

    private Map<String, Stored> existing(List<BoardEntry> entries) {
        Map<String, Stored> out = new HashMap<>();
        jdbc.sql("SELECT request_key, session_id, verdict, reason_code FROM board_result WHERE request_key IN (:keys)")
                .param("keys", entries.stream().map(BoardEntry::requestKey).toList())
                .query((rs, n) -> new Stored(rs.getString("request_key"), rs.getObject("session_id", Long.class),
                        rs.getString("verdict"), rs.getString("reason_code")))
                .list()
                .forEach(s -> out.put(s.requestKey(), s));
        return out;
    }

    /** A board row's {@code sent_at} and our stored RFQ mail time may differ by this much. */
    static final java.time.Duration TIME_MATCH_WINDOW = java.time.Duration.ofSeconds(10);

    /**
     * Maps a board row to our session: by time (the row's {@code sent_at} next to an RFQ mail we received), then the
     * RFQ mail's 5U id (or its thread root), then our public ref, then a booking/team reference. Unmatched rows are
     * kept with a null session and retried next poll.
     */
    private Long resolveSession(BoardEntry e) {
        if (e.sentAt() != null) {
            Optional<Long> byTime = jdbc.sql("""
                            SELECT s.id FROM session s JOIN mail m ON m.session_id = s.id AND m.direction = 'in'
                            WHERE s.type = 'RFQ' AND m.sent_at BETWEEN :t - make_interval(secs => :w) AND :t + make_interval(secs => :w)
                            ORDER BY abs(extract(epoch FROM m.sent_at - :t)), m.id LIMIT 1""")
                    .param("t", e.sentAt())
                    .param("w", TIME_MATCH_WINDOW.toSeconds())
                    .query(Long.class)
                    .optional();
            if (byTime.isPresent()) return byTime.get();
        }
        List<Long> ids = e.mailIds().isEmpty() ? List.of(Long.MIN_VALUE) : e.mailIds();
        List<String> refs = e.refs().isEmpty() ? List.of("") : e.refs();
        return jdbc.sql("""
                        SELECT session_id FROM (
                            SELECT m.session_id, 1 AS prio FROM mail m
                            WHERE m.session_id IS NOT NULL AND m.direction = 'in' AND m.external_id IN (:ids)
                            UNION ALL
                            SELECT m.session_id, 2 FROM mail m
                            WHERE m.session_id IS NOT NULL AND m.thread_root_ext IN (:ids)
                            UNION ALL
                            SELECT s.id, 3 FROM session s WHERE s.public_ref IN (:refs)
                            UNION ALL
                            SELECT b.session_id, 4 FROM booking b
                            WHERE b.booking_reference IN (:refs) OR b.team_reference IN (:refs)
                        ) candidates ORDER BY prio LIMIT 1""")
                .param("ids", ids)
                .param("refs", refs)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private void upsert(BoardEntry e, Long sessionId) {
        jdbc.sql("""
                        INSERT INTO board_result (request_key, session_id, round, verdict, reason_code, points, raw, fetched_at)
                        VALUES (:key, :sessionId, :round, :verdict, :reason, CAST(:points AS jsonb), CAST(:raw AS jsonb), now())
                        ON CONFLICT (request_key) DO UPDATE SET
                            session_id = coalesce(EXCLUDED.session_id, board_result.session_id),
                            round = EXCLUDED.round, verdict = EXCLUDED.verdict, reason_code = EXCLUDED.reason_code,
                            points = EXCLUDED.points, raw = EXCLUDED.raw, fetched_at = EXCLUDED.fetched_at""")
                .param("key", e.requestKey())
                .param("sessionId", sessionId)
                .param("round", e.round())
                .param("verdict", e.verdict())
                .param("reason", e.reasonCode())
                .param("points", e.points() == null ? null : mapper.writeValueAsString(e.points()))
                .param("raw", mapper.writeValueAsString(e.raw()))
                .update();
    }

    private static String summary(BoardEntry e) {
        StringBuilder sb = new StringBuilder("Board: ").append(orUnknown(e.verdict()));
        if (e.reasonCode() != null) sb.append(" (").append(e.reasonCode()).append(')');
        if (e.round() != null) sb.append(" · round ").append(e.round());
        return sb.toString();
    }

    private static Map<String, Object> verdictPayload(BoardEntry e, Stored before) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("requestKey", e.requestKey());
        p.put("round", e.round());
        p.put("verdict", e.verdict());
        p.put("reasonCode", e.reasonCode());
        p.put("points", e.points());
        p.put("previousVerdict", before == null ? null : before.verdict());
        p.put("previousReasonCode", before == null ? null : before.reasonCode());
        return p;
    }

    private static String orUnknown(String s) {
        return s == null ? "unknown" : s;
    }

}
