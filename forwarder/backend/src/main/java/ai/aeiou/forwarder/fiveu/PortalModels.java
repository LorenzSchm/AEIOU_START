package ai.aeiou.forwarder.fiveu;

import java.math.BigDecimal;
import java.util.List;

/** Typed 5U portal payloads (snake_case on the wire, mapped by {@link FiveUClient}). */
public final class PortalModels {

    private PortalModels() {}

    /** {@code GET /v1/portal/ports}: every lane the portal can price. */
    public record Ports(List<Lane> lanes) {}

    public record Lane(String origin, String destination, List<Airline> airlines) {}

    public record Airline(String code, String name) {}

    /** {@code GET /v1/portal/brackets}: a bracket runs from its break up to the next one. */
    public record Brackets(String airline, String airlineCode, List<Integer> breaksKg) {}

    /** {@code GET /v1/portal/rates}: one priced offer, bookable via {@code rateId} for 15 minutes. */
    public record Rate(
            String rateId,
            String airline,
            String airlineCode,
            String origin,
            String destination,
            BigDecimal weightKg,
            BigDecimal chargeableWeightKg,
            BigDecimal total,
            String currency,
            BigDecimal fxEurPerUsd,
            Integer transitDays,
            String validUntil,
            String datasetVersion) {}

    /** {@code POST /v1/portal/bookings} result. {@code status} stays "booked" until the confirmation mail. */
    public record Booking(
            String bookingReference,
            String teamReference,
            String rateId,
            String airline,
            String airlineCode,
            BigDecimal price,
            String currency,
            String status) {}
}
