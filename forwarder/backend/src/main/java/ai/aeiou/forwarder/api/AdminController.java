package ai.aeiou.forwarder.api;

import ai.aeiou.forwarder.api.dto.Admin;
import ai.aeiou.forwarder.api.port.GatewayKeyClient;
import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.settings.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hidden admin page: gateway key registration and the live switches. */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "admin")
public class AdminController {

    static final String POLLING_ENABLED = "pollingEnabled";
    static final String AUTOPILOT = "autopilot";
    private static final String HUMAN = "human";

    private final GatewayKeyClient gateway;
    private final SettingsService settings;
    private final EventLog events;
    private final boolean envPollerEnabled;
    private final boolean envSendEnabled;

    public AdminController(GatewayKeyClient gateway, SettingsService settings, EventLog events,
                           @Value("${app.poller.enabled:false}") boolean envPollerEnabled,
                           @Value("${app.mail.send-enabled:false}") boolean envSendEnabled) {
        this.gateway = gateway;
        this.settings = settings;
        this.events = events;
        this.envPollerEnabled = envPollerEnabled;
        this.envSendEnabled = envSendEnabled;
    }

    @GetMapping("/status")
    @Operation(operationId = "getAdminStatus", summary = "Gateway key status, live switches and env safety flags")
    public Admin.Status status() {
        GatewayKeyClient.KeyStatus key = gateway.status();
        return new Admin.Status(key.registered(), key.message(), settings.getBool(POLLING_ENABLED, true),
                settings.getBool(AUTOPILOT, true), envPollerEnabled, envSendEnabled);
    }

    /** The key is forwarded once and then dropped: never logged, stored or put into an event payload. */
    @PostMapping("/openai-key")
    @Operation(operationId = "registerOpenAiKey", summary = "Store our OpenAI key on the 5U gateway")
    public Admin.OpenAiKeyResult registerOpenAiKey(@RequestBody Admin.OpenAiKeyRequest request) {
        String key = request.apiKey() == null ? "" : request.apiKey().trim();
        if (key.isEmpty()) throw ApiException.badRequest("apiKey is required");
        GatewayKeyClient.Registration r = gateway.register(key);
        String last4 = key.length() >= 4 ? key.substring(key.length() - 4) : "";
        events.append(null, null, "admin.openai_key_registered", Actor.human,
                (r.ok() ? "OpenAI key registered" : "OpenAI key registration failed") + " (…" + last4 + ")",
                Map.of("ok", r.ok(), "last4", last4));
        return new Admin.OpenAiKeyResult(r.ok(), r.message());
    }

    @PutMapping("/live")
    @Operation(operationId = "setLive", summary = "Toggle mail polling and autopilot at runtime")
    public Admin.Status setLive(@RequestBody Admin.LiveUpdate update) {
        Map<String, Object> changed = new LinkedHashMap<>();
        if (update.pollingEnabled() != null) {
            settings.set(POLLING_ENABLED, update.pollingEnabled(), HUMAN);
            changed.put(POLLING_ENABLED, update.pollingEnabled());
        }
        if (update.autopilot() != null) {
            settings.set(AUTOPILOT, update.autopilot(), HUMAN);
            changed.put(AUTOPILOT, update.autopilot());
        }
        if (!changed.isEmpty()) {
            events.append(null, null, "human.live_toggled", Actor.human, "Live switches changed: " + changed, changed);
        }
        return status();
    }
}
