package ai.aeiou.forwarder.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import ai.aeiou.forwarder.tariff.ExtraCharge;
import ai.aeiou.forwarder.tariff.ParsedLane;
import ai.aeiou.forwarder.tariff.WeightBreak;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hand-calculated prices for one lane of every kit tariff. Lane values are copied from the sheets; the expected
 * totals are worked out in the comments (freight = max(rate × billed, minimum), + (fuel + security) × billed).
 */
class PricingEngineTest {

    // Cobalt CGN-EZE: min 79.00 | 0:5.41 45:4.17 100:3.21 300:2.89 500:2.54 1000:2.06 2000:1.67 | fuel 0.70 sec 0.20
    private static final ParsedLane COBALT_CGN_EZE = lane(79.00, 0.70, 0.20, 0, 5.41, 45, 4.17, 100, 3.21, 300, 2.89, 500, 2.54, 1000, 2.06, 2000, 1.67);
    // Helix FRA-LAX: min 112.00 | 0:3.33 45:2.73 100:2.24 250:2.24 500:1.62 1000:1.25 2000:1.25 | fuel 1.40 sec 0.21
    private static final ParsedLane HELIX_FRA_LAX = lane(112.00, 1.40, 0.21, 0, 3.33, 45, 2.73, 100, 2.24, 250, 2.24, 500, 1.62, 1000, 1.25, 2000, 1.25);
    // Orion CGN-MIA: min 78.00 | 0:2.91 45:2.12 100:1.61 300:1.26 500:1.10 1000:0.84 | fuel 1.11 sec 0.23
    private static final ParsedLane ORION_CGN_MIA = lane(78.00, 1.11, 0.23, 0, 2.91, 45, 2.12, 100, 1.61, 300, 1.26, 500, 1.10, 1000, 0.84);
    // Elbflug DUS-TLV: min 85.86 | 0:3.52 45:2.89 100:2.49 300:1.87 500:1.35 1000:1.04 | fuel 1.34 sec 0.14
    private static final ParsedLane ELBFLUG_DUS_TLV = lane(85.86, 1.34, 0.14, 0, 3.52, 45, 2.89, 100, 2.49, 300, 1.87, 500, 1.35, 1000, 1.04);
    // Kestrel FRA-BOM: min 134.62 | 0:4.25 45:3.23 100:2.34 250:2.34 500:1.93 1000:1.70 2000:1.30 | fuel 0.81 sec 0.13
    private static final ParsedLane KESTREL_FRA_BOM = lane(134.62, 0.81, 0.13, 0, 4.25, 45, 3.23, 100, 2.34, 250, 2.34, 500, 1.93, 1000, 1.70, 2000, 1.30);
    // Vega HAM-DFW: min 83.74 | 0:3.84 45:2.76 100:2.35 300:2.14 500:1.78 1000:1.57 2000:1.30 | fuel 1.37 sec 0.14
    private static final ParsedLane VEGA_HAM_DFW = lane(83.74, 1.37, 0.14, 0, 3.84, 45, 2.76, 100, 2.35, 300, 2.14, 500, 1.78, 1000, 1.57, 2000, 1.30);
    // Wayzata FRA-DOH: min 136.00 | 0:3.44 45:2.64 100:2.20 300:1.67 500:1.48 1000:1.15 | fuel 1.21 sec 0.19
    private static final ParsedLane WAYZATA_FRA_DOH = lane(136.00, 1.21, 0.19, 0, 3.44, 45, 2.64, 100, 2.20, 300, 1.67, 500, 1.48, 1000, 1.15);
    // Quetzal FRA-RUH: min 129.00 | 0:3.17 45:2.63 100:2.26 300:1.79 500:1.43 1000:1.26 2000:1.26 | fuel 0.95 sec 0.18
    private static final ParsedLane QUETZAL_FRA_RUH = lane(129.00, 0.95, 0.18, 0, 3.17, 45, 2.63, 100, 2.26, 300, 1.79, 500, 1.43, 1000, 1.26, 2000, 1.26);
    // Yellowtail HAM-BOG: min 127.20 | 0:4.62 45:4.06 100:3.53 250:3.53 500:2.44 1000:1.76 2000:1.29 | fuel 1.43 sec 0.23
    private static final ParsedLane YELLOWTAIL_HAM_BOG = lane(127.20, 1.43, 0.23, 0, 4.62, 45, 4.06, 100, 3.53, 250, 3.53, 500, 2.44, 1000, 1.76, 2000, 1.29);
    // Danube FRA-DXB: min 138.86 | 0:2.90 45:2.23 100:2.05 300:1.66 500:1.45 1000:1.33 | fuel 1.12 sec 0.22
    private static final ParsedLane DANUBE_FRA_DXB = lane(138.86, 1.12, 0.22, 0, 2.90, 45, 2.23, 100, 2.05, 300, 1.66, 500, 1.45, 1000, 1.33);

    @Test
    void cobaltNextBreakTrap() {
        // 90 kg in the 45 bracket: 90×4.17 = 375.30 + 90×0.90 = 81.00 → 456.30
        // billed at 100 kg:       100×3.21 = 321.00 + 100×0.90 = 90.00 → 411.00 (cheaper)
        LanePrice p = PricingEngine.price(COBALT_CGN_EZE, kg(90));
        assertTotal(p, "411.00");
        assertThat(p.bracketFromKg()).isEqualTo(45);
        assertThat(p.usedBreakFromKg()).isEqualTo(100);
        assertThat(p.higherBreakUsed()).isTrue();
        assertThat(p.billedKg()).isEqualByComparingTo("100");
        assertThat(p.freight()).isEqualByComparingTo("321.00");
        assertThat(p.fuel()).isEqualByComparingTo("70.00");
        assertThat(p.security()).isEqualByComparingTo("20.00");
    }

    @Test
    void helixFlatRateBreaks() {
        // 400 kg (bracket 250 @2.24): 896.00 + 400×1.61 = 644.00 → 1540.00; at 500: 810.00 + 805.00 = 1615.00
        assertTotal(PricingEngine.price(HELIX_FRA_LAX, kg(400)), "1540.00");
        // 450 kg: 1008.00 + 724.50 = 1732.50; billed at 500 → 1615.00
        LanePrice p = PricingEngine.price(HELIX_FRA_LAX, kg(450));
        assertTotal(p, "1615.00");
        assertThat(p.usedBreakFromKg()).isEqualTo(500);
    }

    @Test
    void orionMinimumAppliesToFreightBeforeSurcharges() {
        // 10 kg: 10×2.91 = 29.10 < 78.00 → freight 78.00, + 10×1.34 = 13.40 → 91.40 (45 kg: 95.40+60.30 = 155.70)
        LanePrice p = PricingEngine.price(ORION_CGN_MIA, kg(10));
        assertTotal(p, "91.40");
        assertThat(p.minimumApplied()).isTrue();
        assertThat(p.freightAtRate()).isEqualByComparingTo("29.10");
        assertThat(p.freight()).isEqualByComparingTo("78.00");
        assertThat(p.fuel()).isEqualByComparingTo("11.10");
        assertThat(p.security()).isEqualByComparingTo("2.30");
    }

    @Test
    void elbflugStaysInBracketOrJumps() {
        // 250 kg @2.49: 622.50 + 250×1.48 = 370.00 → 992.50; at 300: 561.00 + 444.00 = 1005.00
        assertTotal(PricingEngine.price(ELBFLUG_DUS_TLV, kg(250)), "992.50");
        // 280 kg: 697.20 + 414.40 = 1111.60 → at 300: 1005.00
        assertTotal(PricingEngine.price(ELBFLUG_DUS_TLV, kg(280)), "1005.00");
    }

    @Test
    void kestrelVolumeWeightDecides() {
        // 100 kg but 2 cbm → 334 kg chargeable (bracket 250 @2.34): 781.56 + 334×0.94 = 313.96 → 1095.52
        LanePrice p = PricingEngine.price(KESTREL_FRA_BOM, new Shipment("FRA", "BOM", BigDecimal.valueOf(100), new BigDecimal("2.0")));
        assertTotal(p, "1095.52");
        assertThat(p.chargeableKg()).isEqualByComparingTo("334");
        // 450 kg: 1053.00 + 423.00 = 1476.00; at 500: 965.00 + 470.00 = 1435.00
        assertTotal(PricingEngine.price(KESTREL_FRA_BOM, kg(450)), "1435.00");
    }

    @Test
    void vegaMinimum() {
        // 20 kg: 76.80 < 83.74 → 83.74 + 20×1.51 = 30.20 → 113.94
        LanePrice p = PricingEngine.price(VEGA_HAM_DFW, kg(20));
        assertTotal(p, "113.94");
        assertThat(p.minimumApplied()).isTrue();
    }

    @Test
    void wayzataTopBracketAndJumpToLastBreak() {
        // 1500 kg @1.15: 1725.00 + 1500×1.40 = 2100.00 → 3825.00
        assertTotal(PricingEngine.price(WAYZATA_FRA_DOH, kg(1500)), "3825.00");
        // 900 kg @1.48: 1332.00 + 1260.00 = 2592.00; at 1000: 1150.00 + 1400.00 = 2550.00
        assertTotal(PricingEngine.price(WAYZATA_FRA_DOH, kg(900)), "2550.00");
    }

    @Test
    void quetzalMinimumBeatsBillingUp() {
        // 40 kg: 126.80 < 129 → 129.00 + 40×1.13 = 45.20 → 174.20
        // at 45: 118.35 < 129 → 129.00 + 50.85 = 179.85, so stay at 40 kg
        LanePrice p = PricingEngine.price(QUETZAL_FRA_RUH, kg(40));
        assertTotal(p, "174.20");
        assertThat(p.usedBreakFromKg()).isZero();
        assertThat(p.minimumApplied()).isTrue();
    }

    @Test
    void yellowtailFlatBreakThenJump() {
        // 200 kg @3.53: 706.00 + 200×1.66 = 332.00 → 1038.00
        assertTotal(PricingEngine.price(YELLOWTAIL_HAM_BOG, kg(200)), "1038.00");
        // 400 kg (bracket 250 @3.53): 1412.00 + 664.00 = 2076.00; at 500: 1220.00 + 830.00 = 2050.00
        assertTotal(PricingEngine.price(YELLOWTAIL_HAM_BOG, kg(400)), "2050.00");
    }

    @Test
    void danubeMinimumAndNarrowJump() {
        // 60 kg: 133.80 < 138.86 → 138.86 + 60×1.34 = 80.40 → 219.26
        assertTotal(PricingEngine.price(DANUBE_FRA_DXB, kg(60)), "219.26");
        // 95 kg: 211.85 + 127.30 = 339.15; at 100: 205.00 + 134.00 = 339.00
        assertTotal(PricingEngine.price(DANUBE_FRA_DXB, kg(95)), "339.00");
    }

    @Test
    void fractionalWeightAndExactBreak() {
        // 45 kg exactly is in the 45 bracket: 45×4.17 = 187.65 + 40.50 = 228.15 (100 kg: 411.00)
        assertTotal(PricingEngine.price(COBALT_CGN_EZE, kg(45)), "228.15");
        // 44.5 kg: 44.5×5.41 = 240.745 + 40.05 = 280.795; at 45: 228.15 → billed up
        assertTotal(PricingEngine.price(COBALT_CGN_EZE, new BigDecimal("44.5")), "228.15");
    }

    @Test
    void extrasAreAddedPerTheirBasis() {
        ParsedLane withExtras = new ParsedLane("FRA", "SIN", 3, new BigDecimal("50"),
                List.of(new WeightBreak(0, new BigDecimal("2.00")), new WeightBreak(100, new BigDecimal("1.50"))),
                new BigDecimal("0.50"), new BigDecimal("0.10"),
                List.of(new ExtraCharge("AWB fee", ExtraCharge.Basis.PER_SHIPMENT, new BigDecimal("25")),
                        new ExtraCharge("screening", ExtraCharge.Basis.PER_KG_CHARGEABLE, new BigDecimal("0.05")),
                        new ExtraCharge("war risk", ExtraCharge.Basis.PER_KG_BILLED, new BigDecimal("0.10")),
                        new ExtraCharge("peak", ExtraCharge.Basis.PERCENT_OF_FREIGHT, new BigDecimal("10"))));
        // 80 kg @2.00: freight 160 + 80×0.60 = 48 + 25 + 80×0.05 = 4 + 80×0.10 = 8 + 16 → 261.00
        // at 100 @1.50: freight 150 + 60 + 25 + 4 (chargeable) + 10 + 15 → 264.00
        LanePrice p = PricingEngine.price(withExtras, kg(80));
        assertTotal(p, "261.00");
        assertThat(p.extras()).extracting(LanePrice.AppliedExtra::charge).map(BigDecimal::toPlainString)
                .containsExactly("25.00", "4.00", "8.00", "16.00");
    }

    @Test
    void weightsToTryForBracketPricedSources() {
        assertThat(PricingEngine.weightsToTry(List.of(0, 45, 100, 300), new BigDecimal("90")))
                .map(BigDecimal::toPlainString).containsExactly("90", "100", "300");
    }

    private static void assertTotal(LanePrice p, String expected) {
        assertThat(p.total().toPlainString()).isEqualTo(expected);
    }

    private static BigDecimal kg(int kg) {
        return BigDecimal.valueOf(kg);
    }

    /** lane(minimum, fuel, security, fromKg1, rate1, fromKg2, rate2, ...) */
    static ParsedLane lane(double minimum, double fuel, double security, double... breaks) {
        List<WeightBreak> list = new ArrayList<>();
        for (int i = 0; i < breaks.length; i += 2) list.add(new WeightBreak((int) breaks[i], BigDecimal.valueOf(breaks[i + 1])));
        return new ParsedLane("XXX", "YYY", 3, BigDecimal.valueOf(minimum), list, BigDecimal.valueOf(fuel), BigDecimal.valueOf(security), List.of());
    }
}
