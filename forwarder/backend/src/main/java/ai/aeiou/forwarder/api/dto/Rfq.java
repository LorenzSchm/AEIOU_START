package ai.aeiou.forwarder.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "Shipment request extracted from the customer's mail.")
public record Rfq(
        @Schema(nullable = true) String originZip,
        @Schema(nullable = true) String originAirport,
        @Schema(nullable = true) String destination,
        @Schema(nullable = true) String weightKg,
        @Schema(nullable = true) String volumeCbm,
        @Schema(nullable = true) String chargeableKg,
        @Schema(nullable = true) Integer pieces,
        @Schema(nullable = true) String mode,
        boolean doorPickup,
        @Schema(nullable = true) LocalDate readyDate,
        @Schema(nullable = true) String commodity,
        @Schema(nullable = true) String language,
        @Schema(nullable = true) String customerName) {}
