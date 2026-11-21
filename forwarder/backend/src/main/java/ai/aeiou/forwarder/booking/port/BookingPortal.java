package ai.aeiou.forwarder.booking.port;

import ai.aeiou.forwarder.fiveu.PortalModels.Booking;
import ai.aeiou.forwarder.fiveu.PortalModels.Rate;
import java.math.BigDecimal;

/**
 * The two 5U portal calls the booking workflow needs: {@code POST /v1/portal/bookings} and, to replace an expired
 * rate, {@code GET /v1/portal/rates}. Implementations never throw on HTTP errors; they report the status instead.
 */
public interface BookingPortal {

    /** Status used when the portal could not be reached at all (timeout, connection refused). */
    int UNREACHABLE = 0;

    BookingResult book(String rateId, String teamReference);

    RateResult rate(RateQuery query);

    record RateQuery(String origin, String destination, String airline, BigDecimal weightKg, BigDecimal volumeCbm) {}

    /** {@code status}: 201 booked, 200 retry of the same pair, 404, 409, 410, 503, or {@link #UNREACHABLE}. */
    record BookingResult(int status, Booking booking, String message) {
        public boolean booked() {
            return (status == 200 || status == 201) && booking != null;
        }
    }

    record RateResult(int status, Rate rate, String message) {
        public boolean ok() {
            return status == 200 && rate != null;
        }
    }
}
