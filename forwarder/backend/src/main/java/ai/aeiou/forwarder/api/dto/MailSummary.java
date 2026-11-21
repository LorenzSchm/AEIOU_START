package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.MailDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record MailSummary(
        long id,
        long externalId,
        MailDirection direction,
        String fromAddr,
        String toAddr,
        String subject,
        @Schema(description = "First 160 characters of the body") String snippet,
        @Schema(nullable = true) Long sessionId,
        @Schema(nullable = true) String sessionRef,
        @Schema(nullable = true) String routeType,
        @Schema(nullable = true) Double routeConfidence,
        OffsetDateTime sentAt,
        int attachmentCount) {}
