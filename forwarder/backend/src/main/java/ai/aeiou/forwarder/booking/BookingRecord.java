package ai.aeiou.forwarder.booking;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A row of the {@code booking} table. {@code status}: requested | booked | confirmed | failed. */
public record BookingRecord(
        long id,
        long sessionId,
        String rateId,
        String teamReference,
        String bookingReference,
        String airlineCode,
        String airlineName,
        String origin,
        String destination,
        BigDecimal price,
        String currency,
        Integer transitDays,
        String status,
        OffsetDateTime bookedAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime departureAt,
        OffsetDateTime etaAt) {

    public static final String REQUESTED = "requested";
    public static final String BOOKED = "booked";
    public static final String CONFIRMED = "confirmed";

    public String airline() {
        return airlineName != null ? airlineName : airlineCode;
    }
}
