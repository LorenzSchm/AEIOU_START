package ai.aeiou.forwarder.event;

import java.time.OffsetDateTime;

/**
 * Spring application event published after an {@code event} row was committed.
 * The SSE hub forwards it to the UI as {@code event: event}.
 */
public record EventAppended(long id, Long sessionId, Long mailId, String kind, String actor, String summary,
                            OffsetDateTime createdAt, boolean hasPayload) {}
