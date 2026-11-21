package ai.aeiou.forwarder.booking.port;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    record BookingResult(int status, BookingOut booking, String message) {
        public boolean booked() {
            return (status == 200 || status == 201) && booking != null;
        }
    }

    record RateResult(int status, Rate rate, String message) {
        public boolean ok() {
            return status == 200 && rate != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BookingOut(
            @JsonProperty("booking_reference") String bookingReference,
            @JsonProperty("team_reference") String teamReference,
            @JsonProperty("rate_id") String rateId,
            @JsonProperty("airline") String airline,
            @JsonProperty("airline_code") String airlineCode,
            @JsonProperty("price") BigDecimal price,
            @JsonProperty("currency") String currency,
            @JsonProperty("status") String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Rate(
            @JsonProperty("rate_id") String rateId,
            @JsonProperty("airline") String airline,
            @JsonProperty("airline_code") String airlineCode,
            @JsonProperty("origin") String origin,
            @JsonProperty("destination") String destination,
            @JsonProperty("total") BigDecimal total,
            @JsonProperty("currency") String currency,
            @JsonProperty("transit_days") Integer transitDays,
            @JsonProperty("valid_until") String validUntil) {}
}
