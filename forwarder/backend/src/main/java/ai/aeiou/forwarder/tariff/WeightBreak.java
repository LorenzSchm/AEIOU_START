package ai.aeiou.forwarder.tariff;

import java.math.BigDecimal;

/** A bracket runs from {@code fromKg} up to the next break; {@code rate} is per kg. */
public record WeightBreak(int fromKg, BigDecimal rate) {}
