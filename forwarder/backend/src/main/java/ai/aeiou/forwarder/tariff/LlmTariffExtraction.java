package ai.aeiou.forwarder.tariff;

import ai.aeiou.forwarder.tariff.parse.Numbers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Structured-output shape for the LLM fallback when no deterministic parser can read a sheet. Plain JSON types
 * only (strings, numbers, lists) so a strict JSON schema can be derived from it; converted to {@link ParsedTariff}.
 */
public record LlmTariffExtraction(
        String airlineName,
        String currency,
        Double fxEurPerUnit,
        String validFrom,
        String validTo,
        List<Lane> lanes,
        List<String> notes) {

    public record Lane(String origin, String destination, Integer transitDays, Double minimum, List<Break> breaks,
                       Double fuelPerKg, Double securityPerKg, List<Extra> extras) {}

    /** {@code fromKg} 0 for the "-45" / "<45" bracket. */
    public record Break(Integer fromKg, Double rate) {}

    /** {@code basis}: PER_KG_BILLED, PER_KG_CHARGEABLE, PER_SHIPMENT or PERCENT_OF_FREIGHT. */
    public record Extra(String name, String basis, Double amount) {}

    public static final String INSTRUCTIONS = """
            You extract an air-freight export tariff into JSON. Copy numbers exactly as printed; never compute or guess.
            - origin/destination: 3-letter IATA codes (resolve city names, e.g. Frankfurt -> FRA).
            - breaks: one entry per weight-break column, ascending; "-45", "<45", "min" or "0 kg" bracket has fromKg 0, "+45" has fromKg 45.
            - minimum: the minimum freight charge of the lane. fuelPerKg / securityPerKg: per kg surcharges of that lane
              (or the sheet-wide value if only one is given). Decimal commas ("3,87") mean 3.87.
            - currency: ISO code of the rates. fxEurPerUnit: only if the sheet states an exchange rate (EUR per 1 unit).
            - validFrom/validTo: ISO dates (yyyy-MM-dd).
            - extras: any other charge (per shipment fee, per kg fee, percentage of freight) with its basis.
            - notes: conditions or footnotes that affect the price.
            """;

    public ParsedTariff toParsedTariff() {
        List<ParsedLane> parsed = lanes == null ? List.of() : lanes.stream().map(LlmTariffExtraction::toLane).toList();
        return new ParsedTariff(airlineName, null, currency == null ? null : currency.trim().toUpperCase(Locale.ROOT),
                decimal(fxEurPerUnit), Numbers.date(String.valueOf(validFrom)).orElse(null),
                Numbers.date(String.valueOf(validTo)).orElse(null), parsed, notes);
    }

    private static ParsedLane toLane(Lane l) {
        List<WeightBreak> breaks = l.breaks() == null ? List.of()
                : l.breaks().stream().map(b -> new WeightBreak(b.fromKg() == null ? 0 : b.fromKg(), decimal(b.rate()))).toList();
        List<ExtraCharge> extras = l.extras() == null ? List.of()
                : l.extras().stream().map(e -> new ExtraCharge(e.name(), basis(e.basis()), decimal(e.amount()))).toList();
        return new ParsedLane(upper(l.origin()), upper(l.destination()), l.transitDays(), decimal(l.minimum()), breaks,
                decimal(l.fuelPerKg()), decimal(l.securityPerKg()), extras);
    }

    private static ExtraCharge.Basis basis(String basis) {
        try {
            return ExtraCharge.Basis.valueOf(String.valueOf(basis).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ExtraCharge.Basis.PER_SHIPMENT;
        }
    }

    private static BigDecimal decimal(Double d) {
        return d == null ? null : BigDecimal.valueOf(d);
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
