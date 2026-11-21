package ai.aeiou.forwarder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.fiveu.ApiResponse;
import ai.aeiou.forwarder.fiveu.FiveUClient.OpenAiKeyStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

class AdminApiTest extends ApiTestBase {

    private static final String KEY = "sk-test-secret-abcd";

    @BeforeEach
    void stubs() {
        when(fiveU.openAiKeyStatus()).thenReturn(new ApiResponse<>(200, new HttpHeaders(),
                new OpenAiKeyStatus(true, "abcd", "2026-11-21T13:52:24.494264Z"), ""));
        when(settingsService.getBool(eq("pollingEnabled"), anyBoolean())).thenReturn(true);
        when(settingsService.getBool(eq("autopilot"), anyBoolean())).thenReturn(false);
    }

    @Test
    void statusCombinesGatewaySettingsAndEnvFlags() throws Exception {
        JsonNode s = getJson("/api/admin/status");
        assertThat(s.get("openAiKeyRegistered").asBoolean()).isTrue();
        assertThat(s.get("openAiKeyLast4").asString()).isEqualTo("abcd");
        assertThat(s.get("openAiKeyCheckedAt").asString()).isEqualTo("2026-11-21T13:52:24.494264Z");
        assertThat(s.get("pollingEnabled").asBoolean()).isTrue();
        assertThat(s.get("autopilot").asBoolean()).isFalse();
        assertThat(s.get("envPollerEnabled").asBoolean()).isFalse();
        assertThat(s.get("envSendEnabled").asBoolean()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void keyRegistrationLogsOnlyTheLastFourCharacters() throws Exception {
        when(fiveU.registerOpenAiKey(KEY)).thenReturn(new ApiResponse<>(200, new HttpHeaders(),
                mapper.readTree("{\"message\":\"Key stored\"}"), "{\"message\":\"Key stored\"}"));

        JsonNode r = postJson("/api/admin/openai-key", "{\"apiKey\":\"" + KEY + "\"}", 200);
        assertThat(r.get("ok").asBoolean()).isTrue();
        assertThat(r.get("message").asString()).isEqualTo("Key stored");

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
        verify(settingsService, never()).set(eq("pollingEnabled"), any(), any());
        verifyNoInteractions(eventLog); // the settings service writes the one setting.changed event
    }
}
