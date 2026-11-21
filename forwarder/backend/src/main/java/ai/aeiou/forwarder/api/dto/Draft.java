package ai.aeiou.forwarder.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "Outbound mail waiting for a human (needs_human or autopilot off).")
public record Draft(
        long sessionId,
        String to,
        String subject,
        String body,
        @Schema(nullable = true) Long inReplyToExt,
        OffsetDateTime createdAt) {}
