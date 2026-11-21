package ai.aeiou.forwarder.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** DTOs of the hidden admin page. */
public final class Admin {

    private Admin() {}

    @Schema(name = "AdminStatus")
    public record Status(
            @Schema(nullable = true, description = "Whether the 5U gateway holds our OpenAI key; null if unknown")
            Boolean openAiKeyRegistered,
            @Schema(nullable = true) String openAiKeyMessage,
            @Schema(description = "Runtime setting pollingEnabled") boolean pollingEnabled,
            @Schema(description = "Runtime setting autopilot: answer live mails automatically") boolean autopilot,
            @Schema(description = "app.poller.enabled (env POLLER_ENABLED)") boolean envPollerEnabled,
            @Schema(description = "app.mail.send-enabled (env SEND_ENABLED)") boolean envSendEnabled) {}

    @Schema(name = "OpenAiKeyRequest")
    public record OpenAiKeyRequest(String apiKey) {
        @Override
        public String toString() {
            return "OpenAiKeyRequest[apiKey=***]";  // never leak the key through logs
        }
    }

    @Schema(name = "OpenAiKeyResult")
    public record OpenAiKeyResult(boolean ok, String message) {}

    @Schema(name = "LiveUpdate", description = "Only the fields that are present are changed.")
    public record LiveUpdate(@Schema(nullable = true) Boolean pollingEnabled, @Schema(nullable = true) Boolean autopilot) {}
}
