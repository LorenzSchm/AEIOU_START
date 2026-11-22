package ai.aeiou.freight;

import java.math.BigDecimal;

public record Shipment(
    String origin,
    String destination,
    BigDecimal weightKg,
    BigDecimal volumeCbm,
    String preference,
    String pickupZone,
    BigDecimal pickupEur,
    double pickupDays) {
  /** Weight for mail and PDF text: 343.0020142703 reads as 343, 12.345 as 12.35. Prices keep the exact value. */
  public static String displayKg(BigDecimal kg) {
    return kg.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
  }

  /** Volume for mail and PDF text: 1.54015328613888 reads as 1.54. */
  public static String displayCbm(BigDecimal cbm) {
    return cbm.setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
  }

  public BigDecimal chargeableKg() {
    return weightKg.max(volumeCbm.multiply(BigDecimal.valueOf(167)));
  }

  public Shipment withPickup(BigDecimal price, double days) {
    return new Shipment(
        origin, destination, weightKg, volumeCbm, preference, pickupZone, price, days);
  }
}
