package ai.aeiou.forwarder.tariff;

import java.math.BigDecimal;
import java.util.List;

/**
 * One origin-destination row of a tariff. Rates are per kg in the tariff currency.
 *
 * @param breaks weight breaks ascending by {@code fromKg}; the first one usually starts at 0 ("-45")
 * @param extras sheet-specific charges beyond fuel and security (per-shipment fees, percentages, ...)
 */
public record ParsedLane(
        String origin,
        String destination,
        Integer transitDays,
        BigDecimal minimum,
        List<WeightBreak> breaks,
        BigDecimal fuelPerKg,
        BigDecimal securityPerKg,
        List<ExtraCharge> extras) {

    public ParsedLane {
        breaks = breaks == null ? List.of() : List.copyOf(breaks);
        extras = extras == null ? List.of() : List.copyOf(extras);
    }

    public String key() {
        return origin + "-" + destination;
    }
}
