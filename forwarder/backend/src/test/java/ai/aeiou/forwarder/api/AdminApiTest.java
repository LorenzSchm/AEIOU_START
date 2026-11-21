package ai.aeiou.forwarder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.aeiou.forwarder.api.port.GatewayKeyClient;
import ai.aeiou.forwarder.event.Actor;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

class AdminApiTest extends ApiTestBase {

    private static final String KEY = "sk-test-secret-abcd";

    @BeforeEach
    void stubs() {
        when(gatewayKeyClient.status()).thenReturn(new GatewayKeyClient.KeyStatus(true, "stored"));
        when(settingsService.getBool(eq("pollingEnabled"), anyBoolean())).thenReturn(true);
        when(settingsService.getBool(eq("autopilot"), anyBoolean())).thenReturn(false);
    }

    @Test
    void statusCombinesGatewaySettingsAndEnvFlags() throws Exception {
        JsonNode s = getJson("/api/admin/status");
        assertThat(s.get("openAiKeyRegistered").asBoolean()).isTrue();
        assertThat(s.get("pollingEnabled").asBoolean()).isTrue();
        assertThat(s.get("autopilot").asBoolean()).isFalse();
        assertThat(s.get("envPollerEnabled").asBoolean()).isFalse();
        assertThat(s.get("envSendEnabled").asBoolean()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void keyRegistrationLogsOnlyTheLastFourCharacters() throws Exception {
        when(gatewayKeyClient.register(KEY)).thenReturn(new GatewayKeyClient.Registration(true, "Key stored"));

        JsonNode r = postJson("/api/admin/openai-key", "{\"apiKey\":\"" + KEY + "\"}", 200);
        assertThat(r.get("ok").asBoolean()).isTrue();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(eventLog).append(isNull(), isNull(), eq("admin.openai_key_registered"), eq(Actor.human),
                summary.capture(), payload.capture());
        assertThat(summary.getValue()).doesNotContain(KEY).endsWith("(…abcd)");
        assertThat((Map<String, Object>) payload.getValue()).containsOnly(Map.entry("ok", true), Map.entry("last4", "abcd"));

        postJson("/api/admin/openai-key", "{\"apiKey\":\"  \"}", 400);
    }

    @Test
    void liveSwitchesUpdateSettingsAndReturnStatus() throws Exception {
        mvc.perform(put("/api/admin/live").contentType("application/json").content("{\"autopilot\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envPollerEnabled").value(false));
        verify(settingsService).set("autopilot", true, "human");
        verify(eventLog).append(isNull(), isNull(), eq("human.live_toggled"), eq(Actor.human), anyString(), any());
    }
}
