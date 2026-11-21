package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.TariffSource;
import ai.aeiou.forwarder.api.dto.Enums.TariffStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record TariffSummary(
        long id,
        String airlineName,
        @Schema(nullable = true) String airlineCode,
        String version,
        TariffSource source,
        @Schema(nullable = true) LocalDate validFrom,
        @Schema(nullable = true) LocalDate validTo,
        String currency,
        TariffStatus status,
        int laneCount,
        @Schema(nullable = true) String notes,
        OffsetDateTime createdAt) {}
