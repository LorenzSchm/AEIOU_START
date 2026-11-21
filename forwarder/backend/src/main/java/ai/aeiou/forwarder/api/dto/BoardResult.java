package ai.aeiou.forwarder.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Map;

@Schema(description = "Our result for one request on the 5U board (cached from /v1/board/me).")
public record BoardResult(
        String requestKey,
        @Schema(nullable = true) Long sessionId,
        @Schema(nullable = true) String publicRef,
        @Schema(nullable = true) String round,
        @Schema(nullable = true, description = "accepted | rejected | pending | open | no answer | voided") String verdict,
        @Schema(nullable = true) String reasonCode,
        @Schema(nullable = true) Map<String, Double> points,
        OffsetDateTime fetchedAt) {}
