package ai.aeiou.forwarder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.aeiou.forwarder.api.dto.Admin;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.fiveu.FiveUClient;
import ai.aeiou.forwarder.settings.SettingsService;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Gateway key handling over a real {@link FiveUClient}; the 5U gateway is a MockRestServiceServer. */
class AdminControllerTest {

    private static final String BASE = "http://5u.invalid";
    private static final String KEY = "sk-very-secret-1234";

    private MockRestServiceServer server;
    private FiveUClient fiveU;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        fiveU = new FiveUClient(builder.build(), JsonMapper.builder().build());
    }

    private AdminController controller(String teamKey) {
        return new AdminController(fiveU, mock(SettingsService.class), mock(EventLog.class), teamKey, false, false);
    }

    @Test
    void readsKeyStatusInTheRealGatewayShape() {
        server.expect(requestTo(BASE + "/v1/gateway/openai-key")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"set\":true,\"last4\":\"b_wA\",\"checked_at\":\"2026-11-21T13:52:24.494264Z\"}",
                        MediaType.APPLICATION_JSON));

        Admin.Status s = controller("team").status();

        assertThat(s.openAiKeyRegistered()).isTrue();
        assertThat(s.openAiKeyMessage()).isNull();
        assertThat(s.openAiKeyLast4()).isEqualTo("b_wA");
        assertThat(s.openAiKeyCheckedAt()).isEqualTo("2026-11-21T13:52:24.494264Z");
        server.verify();
    }

    @Test
    void unreachableGatewayLeavesTheStatusUnknown() {
        server.expect(requestTo(BASE + "/v1/gateway/openai-key")).andRespond(withException(new IOException("reset")));

        Admin.Status s = controller("team").status();

        assertThat(s.openAiKeyRegistered()).isNull();
        assertThat(s.openAiKeyMessage()).isEqualTo("Gateway unreachable");
    }

    @Test
    void registersKeyAsApiKeyField() {
        server.expect(requestTo(BASE + "/v1/gateway/openai-key")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"api_key\": \"" + KEY + "\"}"))
                .andRespond(withSuccess("{\"message\": \"Key stored\"}", MediaType.APPLICATION_JSON));

        assertThat(controller("team").registerOpenAiKey(new Admin.OpenAiKeyRequest(KEY)))
                .isEqualTo(new Admin.OpenAiKeyResult(true, "Key stored"));
        server.verify();
    }

    @Test
    void rejectionNeverEchoesTheKey() {
        server.expect(requestTo(BASE + "/v1/gateway/openai-key"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": \"Invalid key " + KEY + "\"}"));

        Admin.OpenAiKeyResult r = controller("team").registerOpenAiKey(new Admin.OpenAiKeyRequest(KEY));

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).doesNotContain(KEY).contains("HTTP 400").contains("***");
    }

    @Test
    void withoutTeamKeyNothingIsSent() {
        server.expect(ExpectedCount.never(), requestTo(BASE + "/v1/gateway/openai-key"));
        AdminController unconfigured = controller("");

        assertThat(unconfigured.status().openAiKeyRegistered()).isNull();
        assertThat(unconfigured.registerOpenAiKey(new Admin.OpenAiKeyRequest(KEY)).ok()).isFalse();
        server.verify();
    }
}
