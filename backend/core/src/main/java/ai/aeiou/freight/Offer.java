package ai.aeiou.freight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;

public record Offer(
    String airline,
    double days,
    BigDecimal totalEur,
    BigDecimal billedKg,
    String source,
    String rateId,
    Instant validUntil) {
  public Offer {
    if (!Double.isFinite(days) || days < 0 || totalEur.signum() < 0)
      throw new IllegalArgumentException("Invalid offer");
    totalEur = totalEur.setScale(2, RoundingMode.HALF_UP);
  }

  public static Comparator<Offer> order(String preference) {
    var cost = Comparator.comparing(Offer::totalEur);
    var time = Comparator.comparingDouble(Offer::days);
    return ("fastest".equals(preference) ? time.thenComparing(cost) : cost.thenComparing(time))
        .thenComparing(o -> o.rateId == null)
        .thenComparing(Offer::airline);
  }

  public String transitLabel() {
    long hours = Math.round(days * 24);
    long wholeDays = hours / 24, remainder = hours % 24;
    String dayText = wholeDays + " day" + (wholeDays == 1 ? "" : "s");
    String hourText = remainder + " hour" + (remainder == 1 ? "" : "s");
    if (remainder == 0) return dayText;
    return wholeDays == 0 ? hourText : dayText + " and " + hourText;
  }

  public Offer addPickup(Shipment s) {
    return addPickup(s, null);
  }

  public Offer addPickup(Shipment s, Instant pickupExpiry) {
    if (s.pickupZone() == null) return this;
    if (s.pickupEur() == null) throw new IllegalArgumentException("Missing confirmed pickup price");
    return new Offer(
        airline,
        days + s.pickupDays(),
        totalEur.add(s.pickupEur()),
        billedKg,
        source,
        rateId,
        pickupExpiry != null && (validUntil == null || pickupExpiry.isBefore(validUntil))
            ? pickupExpiry : validUntil);
  }
}
