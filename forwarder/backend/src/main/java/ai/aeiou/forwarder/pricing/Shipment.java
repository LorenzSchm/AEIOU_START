package ai.aeiou.forwarder.pricing;

import java.math.BigDecimal;

public record Shipment(String origin, String destination, BigDecimal weightKg, BigDecimal volumeCbm) {

    public static final BigDecimal KG_PER_CBM = BigDecimal.valueOf(167);

    public BigDecimal chargeableKg() {
        BigDecimal vol = volumeCbm == null ? BigDecimal.ZERO : volumeCbm.multiply(KG_PER_CBM);
        return weightKg.max(vol);
    }
}
