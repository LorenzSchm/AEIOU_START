package ai.aeiou.forwarder.event;

import java.time.OffsetDateTime;

/**
 * Append-only audit trail. Every append also updates the session's denormalized counters
 * (event_count, last_event_at, last_event_summary) and is pushed to SSE clients after commit.
 * Event kinds: see ARCHITECTURE.md §6 (e.g. "mail.in", "route.decision", "pricing.decision").
 */
public interface EventLog {

    EventRecord append(Long sessionId, Long mailId, String kind, Actor actor, String summary, Object payload);

    default EventRecord append(Long sessionId, String kind, Actor actor, String summary, Object payload) {
        return append(sessionId, null, kind, actor, summary, payload);
    }

    record EventRecord(long id, Long sessionId, Long mailId, String kind, Actor actor, String summary, OffsetDateTime createdAt) {}
}
