package ai.aeiou.forwarder.booking;

import ai.aeiou.forwarder.writer.Facts;
import ai.aeiou.forwarder.writer.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns a CRM booking into the fact block for customer mails: places, dates in the reader's language and a computed
 * progress sentence ("left Frankfurt yesterday, due in Singapore on Thursday, 26 November").
 */
public final class ShipmentFacts {

    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private ShipmentFacts() {}

    /** Planned departure: the RFQ ready date at the start of the day (Berlin time), otherwise now. */
    public static OffsetDateTime departure(LocalDate readyDate, OffsetDateTime now) {
        return readyDate == null ? now : readyDate.atStartOfDay(ZONE).toOffsetDateTime();
    }

    public static OffsetDateTime eta(OffsetDateTime departure, Integer transitDays) {
        return transitDays == null ? null : departure.plusDays(transitDays);
    }

    /** "Frankfurt (FRA)" when the city is known, else the IATA code. */
    public static String place(String iata, Function<String, String> cityOf) {
        if (iata == null) return "";
        String city = cityOf.apply(iata);
        return city == null || city.isBlank() ? iata : city + " (" + iata + ")";
    }

    public static Map<String, Object> forBooking(BookingRecord b, BigDecimal total, String currency, String language,
                                                 OffsetDateTime now, Function<String, String> cityOf) {
        boolean de = "de".equals(language);
        Map<String, Object> facts = new LinkedHashMap<>();
        if (b.bookingReference() != null) facts.put(Facts.BOOKING_REFERENCE, b.bookingReference());
        facts.put(Facts.OUR_REFERENCE, b.teamReference());
        facts.put(Facts.AIRLINE, b.airline());
        facts.put(Facts.ORIGIN, place(b.origin(), cityOf));
        facts.put(Facts.DESTINATION, place(b.destination(), cityOf));
        if (total != null && currency != null) {
            facts.put(Facts.TOTAL, Money.amount(total));
            facts.put(Facts.CURRENCY, currency);
        }
        if (b.transitDays() != null) facts.put(Facts.TRANSIT_DAYS, b.transitDays());
        if (b.departureAt() != null) facts.put(Facts.DEPARTURE, date(b.departureAt(), language));
        if (b.etaAt() != null) facts.put(Facts.ETA, date(b.etaAt(), language));
        facts.put(Facts.STATUS, statusLabel(b.status(), de));
        facts.put(Facts.TODAY, date(now, language));
        facts.put(Facts.PROGRESS, progress(b, now, language, cityOf));
        return facts;
    }

    static String statusLabel(String status, boolean de) {
        return switch (status) {
            case BookingRecord.CONFIRMED -> de ? "gebucht und von der Airline bestätigt" : "booked and confirmed by the airline";
            case BookingRecord.BOOKED -> de ? "gebucht" : "booked";
            case BookingRecord.REQUESTED -> de ? "Buchung bei der Airline angefragt, Bestätigung ausstehend"
                    : "booking requested with the airline, confirmation pending";
            default -> status;
        };
    }

    /** Where the shipment is now, from the planned departure and ETA. We have no live feed, so this is the plan. */
    public static String progress(BookingRecord b, OffsetDateTime now, String language, Function<String, String> cityOf) {
        boolean de = "de".equals(language);
        String from = cityOrCode(b.origin(), cityOf);
        String to = cityOrCode(b.destination(), cityOf);
        if (b.departureAt() == null) {
            return de ? "gebucht von " + from + " nach " + to : "booked from " + from + " to " + to;
        }
        String dep = relative(b.departureAt(), now, language);
        String eta = b.etaAt() == null ? null : relative(b.etaAt(), now, language);
        if (BookingRecord.REQUESTED.equals(b.status())) {
            return de ? "Buchung bei " + b.airline() + " angefragt, Abflug ab " + from + " geplant " + dep
                    : "booking requested with " + b.airline() + ", departure from " + from + " planned " + dep;
        }
        if (now.isBefore(b.departureAt())) {
            String base = de ? "gebucht, Abflug ab " + from + " " + dep : "booked, departing " + from + " " + dep;
            return eta == null ? base : base + (de ? ", Ankunft in " + to + " " + eta : ", due in " + to + " " + eta);
        }
        if (b.etaAt() == null || now.isBefore(b.etaAt())) {
            String base = de ? "unterwegs, " + dep + " ab " + from + " gestartet" : "in transit, left " + from + " " + dep;
            return eta == null ? base : base + (de ? ", planmäßige Ankunft in " + to + " " + eta : ", due in " + to + " " + eta);
        }
        return de ? "planmäßig " + eta + " in " + to + " angekommen" : "scheduled to have arrived in " + to + " " + eta;
    }

    private static String cityOrCode(String iata, Function<String, String> cityOf) {
        String city = iata == null ? null : cityOf.apply(iata);
        return city == null || city.isBlank() ? iata : city;
    }

    /** "today" / "yesterday" / "tomorrow", otherwise "on Thursday, 26 November" (German: "am Donnerstag, 26. November"). */
    static String relative(OffsetDateTime when, OffsetDateTime now, String language) {
        boolean de = "de".equals(language);
        long days = ChronoUnit.DAYS.between(now.atZoneSameInstant(ZONE).toLocalDate(), when.atZoneSameInstant(ZONE).toLocalDate());
        if (days == 0) return de ? "heute" : "today";
        if (days == 1) return de ? "morgen" : "tomorrow";
        if (days == -1) return de ? "gestern" : "yesterday";
        return (de ? "am " : "on ") + date(when, de ? "de" : "en");
    }

    public static String date(OffsetDateTime when, String language) {
        boolean de = "de".equals(language);
        DateTimeFormatter f = de ? DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
                : DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH);
        return f.format(when.atZoneSameInstant(ZONE));
    }
}
