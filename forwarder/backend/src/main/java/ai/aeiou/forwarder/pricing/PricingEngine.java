package ai.aeiou.forwarder.pricing;

import ai.aeiou.forwarder.tariff.ExtraCharge;
import ai.aeiou.forwarder.tariff.ParsedLane;
import ai.aeiou.forwarder.tariff.WeightBreak;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure price math for one tariff lane (ARCHITECTURE.md §5). No I/O, no state.
 * <pre>
 * chargeable = max(kg, cbm × 167)
 * for the chargeable weight's bracket and every higher break b:
 *     billed  = max(chargeable, b)
 *     freight = max(rate[b] × billed, minimum)          minimum applies to freight only
 *     total_b = freight + (fuel + security) × billed + extras
 * price = min(total_b)
 * </pre>
 * Billing at a higher break is legal (the shipment is declared at that weight) and is often cheaper just
 * below a break; rates are also not always monotonic, so every higher break is tried.
 */
public final class PricingEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PricingEngine() {}

    public static LanePrice price(ParsedLane lane, Shipment shipment) {
        return price(lane, shipment.chargeableKg());
    }

    public static LanePrice price(ParsedLane lane, BigDecimal chargeableKg) {
        List<WeightBreak> breaks = lane.breaks();
        if (breaks.isEmpty()) throw new IllegalArgumentException("Lane " + lane.key() + " has no weight breaks");
        int bracket = bracketIndex(breaks, chargeableKg);
        LanePrice best = null;
        for (int i = bracket; i < breaks.size(); i++) {
            LanePrice candidate = priceAt(lane, chargeableKg, breaks.get(bracket).fromKg(), breaks.get(i));
            if (best == null || candidate.total().compareTo(best.total()) < 0) best = candidate;
        }
        return best;
    }

    /**
     * Weights worth asking a bracket-priced source (the portal) for: the chargeable weight itself plus every
     * higher break. The cheapest answer among them is the best price.
     */
    public static List<BigDecimal> weightsToTry(List<Integer> breaksKg, BigDecimal chargeableKg) {
        List<BigDecimal> weights = new ArrayList<>();
        weights.add(chargeableKg);
        for (Integer b : breaksKg) {
            BigDecimal w = BigDecimal.valueOf(b);
            if (w.compareTo(chargeableKg) > 0) weights.add(w);
        }
        return weights;
    }

    static int bracketIndex(List<WeightBreak> breaks, BigDecimal chargeableKg) {
        int index = 0;
        for (int i = 0; i < breaks.size(); i++) {
            if (BigDecimal.valueOf(breaks.get(i).fromKg()).compareTo(chargeableKg) <= 0) index = i;
        }
        return index;
    }

    private static LanePrice priceAt(ParsedLane lane, BigDecimal chargeable, int bracketFromKg, WeightBreak b) {
        BigDecimal billed = chargeable.max(BigDecimal.valueOf(b.fromKg()));
        BigDecimal atRate = b.rate().multiply(billed);
        BigDecimal minimum = nz(lane.minimum());
        boolean minimumApplied = minimum.compareTo(atRate) > 0;
        BigDecimal freight = minimumApplied ? minimum : atRate;
        BigDecimal fuel = nz(lane.fuelPerKg()).multiply(billed);
        BigDecimal security = nz(lane.securityPerKg()).multiply(billed);

        List<LanePrice.AppliedExtra> extras = new ArrayList<>();
        BigDecimal total = freight.add(fuel).add(security);
        for (ExtraCharge e : lane.extras()) {
            BigDecimal charge = switch (e.basis()) {
                case PER_KG_BILLED -> e.amount().multiply(billed);
                case PER_KG_CHARGEABLE -> e.amount().multiply(chargeable);
                case PER_SHIPMENT -> e.amount();
                case PERCENT_OF_FREIGHT -> freight.multiply(e.amount()).divide(HUNDRED);
            };
            extras.add(new LanePrice.AppliedExtra(e.name(), e.basis().name(), e.amount(), cents(charge)));
            total = total.add(charge);
        }
        return new LanePrice(chargeable, bracketFromKg, b.fromKg(), billed, b.rate(), cents(atRate), minimum, minimumApplied,
                cents(freight), nz(lane.fuelPerKg()), cents(fuel), nz(lane.securityPerKg()), cents(security), List.copyOf(extras),
                cents(total));
    }

    static BigDecimal cents(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
