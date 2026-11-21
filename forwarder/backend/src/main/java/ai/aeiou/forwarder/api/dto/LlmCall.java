package ai.aeiou.forwarder.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** {@code request}/{@code response} are only filled (and serialized) by {@code getLlmCall}. */
public record LlmCall(
        long id,
        @Schema(nullable = true) Long sessionId,
        String purpose,
        String model,
        @Schema(nullable = true) Integer promptTokens,
        @Schema(nullable = true) Integer completionTokens,
        @Schema(nullable = true) String costUsd,
        @Schema(nullable = true) Integer latencyMs,
        boolean success,
        OffsetDateTime createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(nullable = true, description = "Detail endpoint only")
        Object request,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(nullable = true, description = "Detail endpoint only")
        Object response) {}
