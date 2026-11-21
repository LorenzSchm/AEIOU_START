package ai.aeiou.forwarder.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.aeiou.forwarder.api.ApiTestBase;
import ai.aeiou.forwarder.event.Actor;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Board snapshots are fed in as JSON strings; the watcher itself (and 5U) is never involved. */
class BoardIngestTest extends ApiTestBase {

    @Autowired BoardIngest ingest;

    @Test
    void liveRowsWithoutIdsAreKeyedByRoundAndMatchedByTime() {
        OffsetDateTime received = OffsetDateTime.parse("2026-11-21T15:13:10.4Z");
        long rfq = data.session("AEI-TIME", "RFQ", "QUOTED", received, false);
        data.mail(7001, "in", rfq, "Air freight quote request", "quote please", received);
        long earlier = data.session("AEI-EARLY", "RFQ", "QUOTED", received.minusMinutes(6), false);
        data.mail(7002, "in", earlier, "Air freight quote request", "quote please", received.minusMinutes(6));

        // The real /v1/board/me shape: no request id at all.
        BoardIngest.Result r = ingest.ingest("""
                {"team": "aeiou", "rank": 11, "requests": [
                  {"round_no": 3, "sent_at": "2026-11-21T15:13:10.155511+00:00", "answered_at": "2026-11-21T15:13:58.647750+00:00",
                   "verdict": "accepted", "reason": null, "booked": null},
                  {"round_no": 2, "sent_at": "2026-11-21T15:07:05.268407+00:00", "verdict": "rejected", "reason": "too_high"},
                  {"round_no": 1, "sent_at": "2026-11-21T14:30:00+00:00", "verdict": "accepted"}
                ], "bookings": []}""");

        assertThat(r.seen()).isEqualTo(3);
        assertThat(sessionOf("round:3")).isEqualTo(rfq);
        assertThat(sessionOf("round:2")).isEqualTo(earlier);
        assertThat(sessionOf("round:1")).isNull();  // nothing of ours within ±10 s
        assertThat(jdbc.sql("SELECT reason_code FROM board_result WHERE request_key = 'round:2'").query(String.class).single())
                .isEqualTo("too_high");
        assertThat(jdbc.sql("SELECT round FROM board_result WHERE request_key = 'round:3'").query(String.class).single())
                .isEqualTo("3");
    }

    @Test
    void storesVerdictsMapsSessionsAndAlertsOnStreaksAndNewReasons() {
        long rfq = data.session("AEI-ONE", "RFQ", "QUOTED", OffsetDateTime.now(), false);
        data.mail(5001, "in", rfq, "RFQ", "quote please", OffsetDateTime.now());
        long other = data.session("AEI-TWO", "RFQ", "QUOTED", OffsetDateTime.now(), false);

        // Newest first, like the board.
        String snapshot = """
                {"team": "aeiou", "bookings": [{"booking_reference": "BK-1"}],
                 "requests": [
                   {"request_id": "r3", "round": 4, "verdict": "Rejected", "reason_code": "PRICE_HIGH",
                    "points": {"price": 0, "speed": 3}},
                   {"request_id": "r2", "team_reference": "AEI-TWO", "verdict": "rejected", "reason_code": "LATE"},
                   {"request_id": "r1", "message_id": 5001, "verdict": "pending"},
                   {"verdict": "accepted"}
                 ]}""";
        BoardIngest.Result first = ingest.ingest(snapshot);

        assertThat(first.seen()).isEqualTo(3);
        assertThat(first.changed()).isEqualTo(3);
        assertThat(sessionOf("r1")).isEqualTo(rfq);
        assertThat(sessionOf("r2")).isEqualTo(other);
        assertThat(sessionOf("r3")).isNull();
        assertThat(jdbc.sql("SELECT verdict FROM board_result WHERE request_key = 'r3'").query(String.class).single())
                .isEqualTo("rejected");
        verify(eventLog, times(3)).append(any(), isNull(), eq("board.verdict"), eq(Actor.system), anyString(), any());
        verify(eventLog).append(isNull(), isNull(), eq("system.alert"), eq(Actor.system),
                eq("Two board rejections in a row (latest: PRICE_HIGH)"), any());
        verify(eventLog).append(isNull(), isNull(), eq("system.alert"), eq(Actor.system),
                eq("New board reason code: LATE, PRICE_HIGH"), any());

        clearInvocations(eventLog);
        BoardIngest.Result unchanged = ingest.ingest(snapshot);
        assertThat(unchanged.changed()).isZero();
        verify(eventLog, never()).append(any(), any(), anyString(), any(), anyString(), any());

        ingest.ingest("""
                {"requests": [{"request_id": "r1", "message_id": 5001, "verdict": "accepted", "points": 12}]}""");
        verify(eventLog).append(eq(rfq), isNull(), eq("board.verdict"), eq(Actor.system), eq("Board: accepted"), any());
        assertThat(jdbc.sql("SELECT points->>'total' FROM board_result WHERE request_key = 'r1'")
                .query(String.class).single()).isEqualTo("12.0");
    }

    private Long sessionOf(String key) {
        return jdbc.sql("SELECT session_id FROM board_result WHERE request_key = :k").param("k", key)
                .query((rs, n) -> rs.getObject("session_id", Long.class)).list().getFirst();
    }
}
