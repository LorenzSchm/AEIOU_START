package ai.aeiou.forwarder.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ShipmentFactsTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-21T15:00:00Z");
    private static final Function<String, String> CITIES = Map.of("FRA", "Frankfurt", "SIN", "Singapore")::get;

    private static BookingRecord booking(String status, String departure, String eta) {
        return new BookingRecord(1, 1, "R-x", "AEI-7K2Q9", "BK-7Q2M9X4L", "HX", "Helix Air Freight", "FRA", "SIN",
                new BigDecimal("1387.35"), "EUR", 4, status, NOW, null, OffsetDateTime.parse(departure), OffsetDateTime.parse(eta));
    }

    @Test
    void beforeDepartureInGerman() {
        String progress = ShipmentFacts.progress(booking("booked", "2026-11-22T23:00:00Z", "2026-11-26T23:00:00Z"), NOW, "de", CITIES);
        // 22 Nov 23:00 UTC is already 23 Nov in Berlin: the day after tomorrow, so a weekday and date.
        assertThat(progress).isEqualTo("gebucht, Abflug ab Frankfurt am Montag, 23. November, Ankunft in Singapore am Freitag, 27. November");
    }

    @Test
    void afterEtaSaysScheduledArrival() {
        String progress = ShipmentFacts.progress(booking("confirmed", "2026-11-16T10:00:00Z", "2026-11-20T10:00:00Z"), NOW, "en", CITIES);
        assertThat(progress).isEqualTo("scheduled to have arrived in Singapore yesterday");
    }

    @Test
    void departureIsTheReadyDateInBerlinOrNow() {
        assertThat(ShipmentFacts.departure(LocalDate.parse("2026-07-01"), NOW)).isEqualTo(OffsetDateTime.parse("2026-06-30T22:00:00Z"));
        assertThat(ShipmentFacts.departure(null, NOW)).isEqualTo(NOW);
        assertThat(ShipmentFacts.eta(NOW, 3)).isEqualTo(NOW.plusDays(3));
    }

    @Test
    void factsCarryPlacesAndTotal() {
        Map<String, Object> facts = ShipmentFacts.forBooking(booking("booked", "2026-11-22T23:00:00Z", "2026-11-26T23:00:00Z"),
                new BigDecimal("1387.35"), "EUR", "en", NOW, CITIES);
        assertThat(facts).containsEntry("origin", "Frankfurt (FRA)").containsEntry("total", "1,387.35")
                .containsEntry("currency", "EUR").containsEntry("departure", "Monday, 23 November");
    }
}
