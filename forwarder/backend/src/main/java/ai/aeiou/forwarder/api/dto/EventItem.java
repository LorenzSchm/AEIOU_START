package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventAppended;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "Audit trail entry without its payload; load the payload lazily via getEventPayload.")
public record EventItem(
        long id,
        @Schema(nullable = true) Long sessionId,
        @Schema(nullable = true) Long mailId,
        @Schema(example = "route.decision") String kind,
        Actor actor,
        String summary,
        OffsetDateTime createdAt,
        boolean hasPayload) {

    public static EventItem of(EventAppended e) {
        return new EventItem(e.id(), e.sessionId(), e.mailId(), e.kind(), Actor.valueOf(e.actor()), e.summary(),
                e.createdAt(), e.hasPayload());
    }
}
