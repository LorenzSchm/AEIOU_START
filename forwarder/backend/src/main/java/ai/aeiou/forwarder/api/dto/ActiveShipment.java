package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "A booked or confirmed shipment with airport coordinates, for the globe.")
public record ActiveShipment(
        long bookingId,
        long sessionId,
        String publicRef,
        @Schema(nullable = true) String bookingReference,
        @Schema(nullable = true) String airlineName,
        BookingStatus status,
        ShipmentPoint origin,
        ShipmentPoint destination,
        @Schema(nullable = true) OffsetDateTime departureAt,
        @Schema(nullable = true) OffsetDateTime etaAt,
        @Schema(nullable = true) Integer transitDays,
        @Schema(nullable = true) String price,
        @Schema(nullable = true) String currency) {}
