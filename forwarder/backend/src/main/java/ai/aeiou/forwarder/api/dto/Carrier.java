package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.CarrierKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record Carrier(
        long id,
        String name,
        @Schema(nullable = true) String code,
        CarrierKind kind,
        String email,
        @Schema(description = "IATA destinations (airline) or 2-digit zip zones (trucker)") List<String> serves) {}
