package ai.aeiou.forwarder.api;

import ai.aeiou.forwarder.api.dto.Admin;
import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.fiveu.ApiResponse;
import ai.aeiou.forwarder.fiveu.FiveUClient;
import ai.aeiou.forwarder.fiveu.FiveUClient.OpenAiKeyStatus;
import ai.aeiou.forwarder.settings.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Hidden admin page: gateway key registration and the live switches.
 *
 * <p>The OpenAI key passes through in memory only: it is never logged, stored or put into an event. Gateway error
 * texts are scrubbed of the key before they are returned, because validation errors may quote the input.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "admin")
public class AdminController {

    static final String POLLING_ENABLED = "pollingEnabled";
    static final String AUTOPILOT = "autopilot";
    private static final String HUMAN = "human";
    private static final String NOT_CONFIGURED = "TEAM_API_KEY is not configured";
    private static final String UNREACHABLE = "Gateway unreachable";
    private static final List<String> MESSAGE_FIELDS = List.of("message", "detail", "status");

    private final FiveUClient fiveU;
    private final SettingsService settings;
    private final EventLog events;
    private final boolean teamKeyConfigured;
    private final boolean envPollerEnabled;
    private final boolean envSendEnabled;

    public AdminController(FiveUClient fiveU, SettingsService settings, EventLog events,
                           @Value("${app.fiveu.api-key:}") String teamKey,
                           @Value("${app.poller.enabled:false}") boolean envPollerEnabled,
                           @Value("${app.mail.send-enabled:false}") boolean envSendEnabled) {
        this.fiveU = fiveU;
        this.settings = settings;
        this.events = events;
        this.teamKeyConfigured = !teamKey.isBlank();
        this.envPollerEnabled = envPollerEnabled;
        this.envSendEnabled = envSendEnabled;
    }

    @GetMapping("/status")
    @Operation(operationId = "getAdminStatus", summary = "Gateway key status, live switches and env safety flags")
    public Admin.Status status() {
        KeyStatus key = keyStatus();
        return new Admin.Status(key.registered(), key.message(), key.last4(), key.checkedAt(),
                settings.getBool(POLLING_ENABLED, true), settings.getBool(AUTOPILOT, true), envPollerEnabled, envSendEnabled);
    }

    /** The key is forwarded once and then dropped; the event only carries its last four characters. */
    @PostMapping("/openai-key")
    @Operation(operationId = "registerOpenAiKey", summary = "Store our OpenAI key on the 5U gateway")
    public Admin.OpenAiKeyResult registerOpenAiKey(@RequestBody Admin.OpenAiKeyRequest request) {
        String key = request.apiKey() == null ? "" : request.apiKey().trim();
        if (key.isEmpty()) throw ApiException.badRequest("apiKey is required");
        Admin.OpenAiKeyResult result = register(key);
        String last4 = key.length() >= 4 ? key.substring(key.length() - 4) : "";
        events.append(null, null, "admin.openai_key_registered", Actor.human,
                (result.ok() ? "OpenAI key registered" : "OpenAI key registration failed") + " (…" + last4 + ")",
                Map.of("ok", result.ok(), "last4", last4));
        return result;
    }

    /** Each changed switch leaves one {@code setting.changed} event, written by the settings service. */
    @PutMapping("/live")
    @Operation(operationId = "setLive", summary = "Toggle mail polling and autopilot at runtime")
    public Admin.Status setLive(@RequestBody Admin.LiveUpdate update) {
        if (update.pollingEnabled() != null) settings.set(POLLING_ENABLED, update.pollingEnabled(), HUMAN);
        if (update.autopilot() != null) settings.set(AUTOPILOT, update.autopilot(), HUMAN);
        return status();
    }

    private record KeyStatus(Boolean registered, String message, String last4, String checkedAt) {
        static KeyStatus unknown(String message) {
            return new KeyStatus(null, message, null, null);
        }
    }

    private KeyStatus keyStatus() {
        if (!teamKeyConfigured) return KeyStatus.unknown(NOT_CONFIGURED);
        try {
            ApiResponse<OpenAiKeyStatus> res = fiveU.openAiKeyStatus();
            if (!res.isSuccess()) return KeyStatus.unknown("Gateway answered HTTP " + res.status());
            OpenAiKeyStatus body = res.body();
            if (body == null) return KeyStatus.unknown("Empty answer from gateway");
            return new KeyStatus(body.set(), body.set() == null ? "Unrecognized answer from gateway" : null,
                    body.last4(), body.checkedAt());
        } catch (RestClientException e) {
            return KeyStatus.unknown(UNREACHABLE);
        }
    }

    private Admin.OpenAiKeyResult register(String apiKey) {
        if (!teamKeyConfigured) return new Admin.OpenAiKeyResult(false, NOT_CONFIGURED);
        try {
            ApiResponse<JsonNode> res = fiveU.registerOpenAiKey(apiKey);
            if (res.isSuccess()) {
                return new Admin.OpenAiKeyResult(true, FiveUClient.redact(successMessage(res.body()), apiKey));
            }
            String prefix = "Gateway rejected the key (HTTP " + res.status() + ")";
            String detail = res.errorDetail();
            return new Admin.OpenAiKeyResult(false, FiveUClient.redact(detail == null ? prefix : prefix + ": " + detail, apiKey));
        } catch (RestClientException e) {
            return new Admin.OpenAiKeyResult(false, UNREACHABLE);
        }
    }

    /** Only plain string messages are used; structured answers (which may quote the input) are dropped. */
    private static String successMessage(JsonNode body) {
        if (body != null) {
            for (String field : MESSAGE_FIELDS) {
                if (body.path(field).isString()) return body.path(field).asString();
            }
        }
        return "Key stored";
    }
}
