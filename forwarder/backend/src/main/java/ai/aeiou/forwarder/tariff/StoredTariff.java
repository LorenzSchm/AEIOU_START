package ai.aeiou.forwarder.tariff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A row of the {@code tariff} table plus its lane count (the API's TariffSummary). */
public record StoredTariff(
        long id,
        String airlineName,
        String airlineCode,
        String version,
        String source,
        String sourceFile,
        Long sourceMailId,
        LocalDate validFrom,
        LocalDate validTo,
        String currency,
        BigDecimal fxEurPerUnit,
        TariffStatus status,
        int laneCount,
        String notes,
        OffsetDateTime createdAt) {}
