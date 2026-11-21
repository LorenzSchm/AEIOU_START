package ai.aeiou.forwarder.tariff;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TariffSanityCheckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);

    @Test
    void firstTariffOnlyNeedsToBeWellFormed() {
        TariffSanityCheck.Result r = TariffSanityCheck.check(tariff("EUR", lane("FRA", "SIN", "4.00", "3.00")), null, List.of(), TODAY);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void comparesWithTheVersionInForce() {
        ParsedTariff old = tariff("EUR", lane("FRA", "SIN", "4.00", "3.00"), lane("FRA", "JFK", "4.00", "3.00"), lane("MUC", "JFK", "4.00", "3.00"));
        StoredTariff current = stored(old);

        assertThat(TariffSanityCheck.check(tariff("EUR", lane("FRA", "SIN", "4.40", "2.70"), lane("FRA", "JFK", "4.00", "3.00"),
                lane("MUC", "JFK", "4.00", "3.00")), current, old.lanes(), TODAY).passed()).isTrue();

        assertThat(TariffSanityCheck.check(tariff("EUR", lane("FRA", "SIN", "6.10", "3.00"), lane("FRA", "JFK", "4.00", "3.00"),
                lane("MUC", "JFK", "4.00", "3.00")), current, old.lanes(), TODAY).warnings())
                .anyMatch(p -> p.contains("FRA-SIN 4.00 -> 6.10"));
        assertThat(TariffSanityCheck.check(tariff("EUR", lane("FRA", "SIN", "4.00", "3.00")), current, old.lanes(), TODAY).warnings())
                .anyMatch(p -> p.contains("Lane count dropped from 3 to 1"));
        // big moves are warnings, not blockers: the hourly setup changes may legitimately move prices
        TariffSanityCheck.Result usd = TariffSanityCheck.check(tariff("USD", old.lanes().toArray(ParsedLane[]::new)), current, old.lanes(), TODAY);
        assertThat(usd.warnings()).anyMatch(p -> p.contains("Currency changed from EUR to USD"));
        assertThat(usd.passed()).isTrue();
    }

    @Test
    void structuralProblems() {
        ParsedLane descending = new ParsedLane("FRA", "SIN", 3, new BigDecimal("50"),
                List.of(new WeightBreak(45, new BigDecimal("3")), new WeightBreak(0, new BigDecimal("4"))),
                new BigDecimal("1"), new BigDecimal("0.1"), List.of());
        ParsedLane negativeFuel = new ParsedLane("FRA", "JFK", 3, new BigDecimal("50"),
                List.of(new WeightBreak(0, new BigDecimal("4"))), new BigDecimal("-1"), new BigDecimal("0.1"), List.of());
        assertThat(TariffSanityCheck.structural(tariff("EUR", descending, negativeFuel)))
                .anyMatch(p -> p.contains("FRA-SIN breaks not ascending"))
                .anyMatch(p -> p.contains("FRA-JFK surcharges out of range"));
        ParsedTariff expired = new ParsedTariff("X", null, "EUR", null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                List.of(lane("FRA", "SIN", "4", "3")), List.of());
        assertThat(TariffSanityCheck.check(expired, null, List.of(), TODAY).problems()).anyMatch(p -> p.contains("expired"));
    }

    private static ParsedTariff tariff(String currency, ParsedLane... lanes) {
        return new ParsedTariff("X Air", null, currency, null, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), List.of(lanes), List.of());
    }

    private static ParsedLane lane(String origin, String destination, String rate0, String rate45) {
        return new ParsedLane(origin, destination, 3, new BigDecimal("80"),
                List.of(new WeightBreak(0, new BigDecimal(rate0)), new WeightBreak(45, new BigDecimal(rate45))),
                new BigDecimal("1.00"), new BigDecimal("0.20"), List.of());
    }

    private static StoredTariff stored(ParsedTariff t) {
        return new StoredTariff(1, t.airlineName(), null, "2026-11", "kit", "f", null, LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30), t.currency(), null, TariffStatus.active, t.lanes().size(), null, OffsetDateTime.now());
    }
}
