package ai.aeiou.forwarder.tariff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Format-independent result of reading one tariff sheet. Every parser (xlsx, pdf, docx, txt, LLM fallback,
 * hand transcription) produces this shape; the pricing engine only ever sees it.
 *
 * @param fxEurPerUnit EUR per one unit of {@code currency} when the sheet states its own rate, else null
 * @param notes        sheet text that was not turned into numbers (conditions, footnotes), kept for review
 */
public record ParsedTariff(
        String airlineName,
        String airlineCode,
        String currency,
        BigDecimal fxEurPerUnit,
        LocalDate validFrom,
        LocalDate validTo,
        List<ParsedLane> lanes,
        List<String> notes) {

    public ParsedTariff {
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public ParsedTariff withAirline(String name, String code) {
        return new ParsedTariff(name, code, currency, fxEurPerUnit, validFrom, validTo, lanes, notes);
    }
}
