package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.QuoteMode;
import ai.aeiou.forwarder.api.dto.Enums.SessionType;
import ai.aeiou.forwarder.session.SessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "One row of the live board. Read from the denormalized session table only.")
public record SessionSummary(
        long id,
        String publicRef,
        SessionType type,
        SessionStatus status,
        @Schema(nullable = true) String customerAddr,
        @Schema(nullable = true) String subject,
        @Schema(nullable = true) QuoteMode mode,
        boolean needsHuman,
        @Schema(nullable = true) String needsHumanReason,
        boolean autopilot,
        @Schema(nullable = true) OffsetDateTime deadlineAt,
        @Schema(nullable = true) OffsetDateTime quotedAt,
        @Schema(nullable = true) String quotedTotal,
        @Schema(nullable = true) String quotedCurrency,
        boolean booked,
        String llmCostUsd,
        int eventCount,
        int mailCount,
        OffsetDateTime lastEventAt,
        @Schema(nullable = true) String lastEventSummary,
        OffsetDateTime createdAt,
        @Schema(nullable = true, example = "FRA → SIN") String lane,
        @Schema(nullable = true, description = "accepted | rejected | pending | open | no answer | voided")
        String boardVerdict) {}
