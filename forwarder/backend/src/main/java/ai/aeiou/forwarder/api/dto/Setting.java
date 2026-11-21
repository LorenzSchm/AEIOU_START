package ai.aeiou.forwarder.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record Setting(
        String key,
        @Schema(description = "Any JSON value") Object value,
        OffsetDateTime updatedAt,
        @Schema(nullable = true) String updatedBy) {

    @Schema(name = "SettingUpdate")
    public record Update(@Schema(description = "Any JSON value") Object value) {}
}
