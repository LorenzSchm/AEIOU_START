package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.PriceSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Map;

public record PriceCandidate(
        long id,
        PriceSource source,
        @Schema(nullable = true) String airlineCode,
        @Schema(nullable = true) String airlineName,
        String total,
        String currency,
        String totalEur,
        @Schema(nullable = true) Integer transitDays,
        @Schema(nullable = true) String rateId,
        @Schema(nullable = true) OffsetDateTime validUntil,
        @Schema(nullable = true) Map<String, Object> breakdown,
        boolean chosen,
        @Schema(nullable = true) String rejectReason,
        OffsetDateTime createdAt) {}
