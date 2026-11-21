package ai.aeiou.forwarder.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ShipmentPoint(String iata, @Schema(nullable = true) String city, double lat, double lon) {}
