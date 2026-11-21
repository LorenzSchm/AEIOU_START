package ai.aeiou.forwarder.pricing;

import java.math.BigDecimal;
import java.util.List;

/**
 * The cheapest way to bill one shipment on one tariff lane, in the tariff currency. Amounts are exact;
 * {@link #total()} is rounded to cents.
 *
 * @param bracketFromKg   break of the bracket the chargeable weight falls into
 * @param usedBreakFromKg break actually billed (higher than {@code bracketFromKg} when billing up is cheaper)
 * @param billedKg        max(chargeable, used break): freight, fuel and security are charged on this weight
 */
public record LanePrice(
        BigDecimal chargeableKg,
        int bracketFromKg,
        int usedBreakFromKg,
        BigDecimal billedKg,
        BigDecimal ratePerKg,
        BigDecimal freightAtRate,
        BigDecimal minimum,
        boolean minimumApplied,
        BigDecimal freight,
        BigDecimal fuelPerKg,
        BigDecimal fuel,
        BigDecimal securityPerKg,
        BigDecimal security,
        List<AppliedExtra> extras,
        BigDecimal total) {

    public boolean higherBreakUsed() {
        return usedBreakFromKg > bracketFromKg;
    }

    public record AppliedExtra(String name, String basis, BigDecimal amount, BigDecimal charge) {}
}
