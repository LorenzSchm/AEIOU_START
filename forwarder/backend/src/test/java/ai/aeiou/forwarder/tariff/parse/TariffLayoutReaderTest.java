package ai.aeiou.forwarder.tariff.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.aeiou.forwarder.tariff.ExtraCharge;
import ai.aeiou.forwarder.tariff.ParsedLane;
import ai.aeiou.forwarder.tariff.ParsedTariff;
import ai.aeiou.forwarder.tariff.TariffParseException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Layout variants and traps that the kit does not contain but a monthly tariff mail might. */
class TariffLayoutReaderTest {

    private static final AirportResolver CITIES = city -> Optional.ofNullable(
            Map.of("frankfurt", "FRA", "singapore", "SIN", "munich", "MUC", "new york", "JFK").get(city.toLowerCase()));

    @Test
    void usdSheetWithStatedFxGlobalSurchargesAndExtras() throws Exception {
        ParsedTariff t = TariffLayoutReader.read(List.of(
                "Nimbus Cargo | Air export tariff",
                "Currency USD",
                "Validity: 01.12.2026 - 31.12.2026",
                "Exchange rate: 1 USD = 0.92 EUR",
                "Lane  Transit  Minimum  -45  +45  +100  +300",
                "FRA-SIN  3  USD 80.00  USD 5.00  USD 4.00  USD 3.00  USD 2.00",
                "MUC-JFK  2  USD 90.00  USD 4.50  USD 3.50  USD 2.50  USD 1.50",
                "Fuel surcharge: USD 1.10 per kg",
                "Security surcharge: USD 0.20 per kg",
                "AWB fee: USD 25.00 per shipment",
                "MUC-JFK: screening fee 0.05 per kg"), AirportResolver.NONE);

        assertThat(t.currency()).isEqualTo("USD");
        assertThat(t.fxEurPerUnit()).isEqualByComparingTo("0.92");
        assertThat(t.validFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(t.validTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        ParsedLane sin = KitTariffParsingTest.lane(t, "FRA-SIN");
        assertThat(sin.fuelPerKg()).isEqualByComparingTo("1.10");
        assertThat(sin.securityPerKg()).isEqualByComparingTo("0.20");
        assertThat(sin.extras()).containsExactly(new ExtraCharge("AWB fee", ExtraCharge.Basis.PER_SHIPMENT, new java.math.BigDecimal("25.00")));
        ParsedLane jfk = KitTariffParsingTest.lane(t, "MUC-JFK");
        assertThat(jfk.extras()).extracting(ExtraCharge::basis)
                .containsExactly(ExtraCharge.Basis.PER_KG_BILLED, ExtraCharge.Basis.PER_SHIPMENT);
    }

    @Test
    void inverseFxQuoteIsInverted() throws Exception {
        ParsedTariff t = TariffLayoutReader.read(List.of(
                "Nimbus Cargo tariff", "Currency USD", "1 EUR = 1.25 USD",
                "FRA-SIN", "Transit 3", "Minimum 80", "from 0 kg 5.00", "from 45 kg 4.00", "Fuel 1.00", "Security 0.10"));
        assertThat(t.fxEurPerUnit()).isEqualByComparingTo("0.8");
        assertThat(t.airlineName()).isEqualTo("Nimbus Cargo");
    }

    @Test
    void laneNamedByCityOnly() throws Exception {
        ParsedTariff t = TariffLayoutReader.read(List.of(
                "Nimbus Cargo | tariff", "Currency EUR",
                "Lane\tTransit\tMinimum\tfrom 0 kg\tfrom 45 kg\tfrom 100 kg\tFuel\tSecurity",
                "Frankfurt > Singapore\t4 days\t70,00\t4,00\t3,00\t2,00\t1,00\t0,20"), CITIES);
        ParsedLane lane = KitTariffParsingTest.lane(t, "FRA-SIN");
        assertThat(lane.transitDays()).isEqualTo(4);
        assertThat(lane.minimum()).isEqualByComparingTo("70");
        assertThat(lane.securityPerKg()).isEqualByComparingTo("0.20");
    }

    @Test
    void percentageFuelBecomesAnExtra() throws Exception {
        ParsedTariff t = TariffLayoutReader.read(List.of(
                "X Air | tariff", "Currency EUR", "Lane Transit Minimum -45 +45 +100",
                "FRA-SIN 3 80.00 5.00 4.00 3.00",
                "FRA-SIN: fuel 12% of freight, security 0.15 per kg"));
        ParsedLane lane = t.lanes().getFirst();
        assertThat(lane.extras()).containsExactly(new ExtraCharge("fuel", ExtraCharge.Basis.PERCENT_OF_FREIGHT, new java.math.BigDecimal("12")));
        assertThat(lane.securityPerKg()).isEqualByComparingTo("0.15");
        assertThat(lane.fuelPerKg()).isEqualByComparingTo("0");
    }

    @Test
    void missingSurchargeIsAProblemNotAZero() {
        assertThatThrownBy(() -> TariffLayoutReader.read(List.of(
                "X Air | tariff", "Currency EUR", "Lane Transit Minimum -45 +45 +100", "FRA-SIN 3 80.00 5.00 4.00 3.00")))
                .isInstanceOf(TariffParseException.class)
                .hasMessageContaining("FRA-SIN has no fuel surcharge");
    }

    @Test
    void wrongColumnCountIsAProblem() {
        assertThatThrownBy(() -> TariffLayoutReader.read(List.of(
                "X Air | tariff", "Currency EUR", "Lane Transit Minimum -45 +45 +100",
                "FRA-SIN 3 80.00 5.00 4.00", "FRA-SIN: fuel 1.00 per kg, security 0.10 per kg")))
                .isInstanceOf(TariffParseException.class)
                .hasMessageContaining("has 4 numbers, expected 5");
    }

    @Test
    void poundsAreRejected() {
        assertThatThrownBy(() -> TariffLayoutReader.read(List.of(
                "X Air | tariff", "Currency USD", "All rates per lb",
                "FRA-SIN", "Minimum 80", "from 0 kg 5.00", "Fuel 1.00", "Security 0.10")))
                .isInstanceOf(TariffParseException.class)
                .hasMessageContaining("pounds");
    }

    @Test
    void numbersAndDates() {
        assertThat(Numbers.parse("3,87")).isEqualByComparingTo("3.87");
        assertThat(Numbers.parse("1.234,50")).isEqualByComparingTo("1234.50");
        assertThat(Numbers.parse("1,234.50")).isEqualByComparingTo("1234.50");
        assertThat(Numbers.parse("1,250")).isEqualByComparingTo("1250");
        assertThat(Numbers.date("01/11/2026")).contains(LocalDate.of(2026, 11, 1));
        assertThat(Numbers.date("30.11.2026")).contains(LocalDate.of(2026, 11, 30));
        assertThat(Numbers.date("1 November 2026")).contains(LocalDate.of(2026, 11, 1));
        assertThat(Numbers.date("2026-11-30")).contains(LocalDate.of(2026, 11, 30));
    }
}
