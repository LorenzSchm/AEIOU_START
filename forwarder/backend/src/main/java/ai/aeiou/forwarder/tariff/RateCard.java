package ai.aeiou.forwarder.tariff;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One lane of an active tariff together with the tariff facts pricing needs (airline, currency, validity). */
public record RateCard(
        long tariffId,
        String version,
        String airlineName,
        String airlineCode,
        String currency,
        BigDecimal fxEurPerUnit,
        LocalDate validFrom,
        LocalDate validTo,
        ParsedLane lane) {}
