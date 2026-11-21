package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.SessionActionType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        A human action on a session. `body` is required for edit_and_send, `type` (a MailType) for reroute,
        `note` is optional for resolve.""")
public record SessionAction(
        SessionActionType action,
        @Schema(nullable = true, description = "edit_and_send: the body to send") String body,
        @Schema(nullable = true, description = "reroute: MailType for the session's latest inbound mail",
                example = "FOLLOW_UP") String type,
        @Schema(nullable = true, description = "resolve: optional note") String note) {}
