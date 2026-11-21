package ai.aeiou.forwarder.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Error body returned with every 4xx/5xx response.")
public record ApiError(String error, String message) {}
