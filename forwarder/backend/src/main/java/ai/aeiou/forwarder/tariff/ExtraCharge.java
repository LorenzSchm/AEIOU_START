package ai.aeiou.forwarder.tariff;

import java.math.BigDecimal;

/** A charge a sheet defines on top of freight, fuel and security. */
public record ExtraCharge(String name, Basis basis, BigDecimal amount) {

    public enum Basis {
        /** amount per kg of the weight the freight is billed on */
        PER_KG_BILLED,
        /** amount per kg of chargeable weight */
        PER_KG_CHARGEABLE,
        /** flat amount per shipment (AWB fee, handling, ...) */
        PER_SHIPMENT,
        /** percent of the freight charge (after the minimum) */
        PERCENT_OF_FREIGHT
    }
}
