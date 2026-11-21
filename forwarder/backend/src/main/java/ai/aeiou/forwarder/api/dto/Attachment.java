package ai.aeiou.forwarder.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record Attachment(
        long id,
        String filename,
        @Schema(nullable = true) String contentType,
        @Schema(nullable = true) Long size) {}
