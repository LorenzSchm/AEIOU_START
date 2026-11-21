package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record Booking(
        long id,
        long sessionId,
        String publicRef,
        @Schema(nullable = true) String rateId,
        String teamReference,
        @Schema(nullable = true) String bookingReference,
        @Schema(nullable = true) String airlineCode,
        @Schema(nullable = true) String airlineName,
        String origin,
        String destination,
        @Schema(nullable = true) String price,
        @Schema(nullable = true) String currency,
        @Schema(nullable = true) Integer transitDays,
        BookingStatus status,
        OffsetDateTime bookedAt,
        @Schema(nullable = true) OffsetDateTime confirmedAt,
        @Schema(nullable = true) OffsetDateTime departureAt,
        @Schema(nullable = true) OffsetDateTime etaAt) {}
