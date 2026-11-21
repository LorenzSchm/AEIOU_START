package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.BidKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record BidRequest(
        long id,
        String carrierName,
        String carrierEmail,
        BidKind kind,
        OffsetDateTime sentAt,
        @Schema(nullable = true) OffsetDateTime answeredAt,
        @Schema(nullable = true) String price,
        @Schema(nullable = true) String currency,
        @Schema(nullable = true) Integer transitDays,
        boolean declined) {}
