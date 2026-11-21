package ai.aeiou.forwarder.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record EventPayload(long id, @Schema(nullable = true) Object payload) {}
