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
  public BigDecimal chargeableKg() {
    return weightKg.max(volumeCbm.multiply(BigDecimal.valueOf(167)));
  }

  public Shipment withPickup(BigDecimal price, double days) {
    return new Shipment(
        origin, destination, weightKg, volumeCbm, preference, pickupZone, price, days);
  }
}
