package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.TariffSource;
import ai.aeiou.forwarder.api.dto.Enums.TariffStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "TariffSummary plus all lanes.")
public record TariffDetail(
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
        OffsetDateTime createdAt,
        List<Lane> lanes) {

    public static TariffDetail of(TariffSummary s, List<Lane> lanes) {
        return new TariffDetail(s.id(), s.airlineName(), s.airlineCode(), s.version(), s.source(), s.validFrom(),
                s.validTo(), s.currency(), s.status(), s.laneCount(), s.notes(), s.createdAt(), lanes);
    }

    @Schema(name = "TariffLane")
    public record Lane(
            String origin,
            String destination,
            @Schema(nullable = true) Integer transitDays,
            String minimum,
            List<Break> breaks,
            String fuelPerKg,
            String securityPerKg) {}

    @Schema(name = "TariffBreak")
    public record Break(BigDecimal fromKg, String rate) {}
}
