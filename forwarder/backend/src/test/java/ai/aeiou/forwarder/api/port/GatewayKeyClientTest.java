package ai.aeiou.forwarder.api.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The 5U gateway is simulated with MockRestServiceServer; nothing leaves the JVM. */
class GatewayKeyClientTest {

    private static final String BASE = "http://5u.invalid";
    private static final String KEY = "sk-very-secret-1234";

    private MockRestServiceServer server;
    private GatewayKeyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GatewayKeyClient(builder, true);
    }

    @Test
    void readsKeyStatus() {
        server.expect(requestTo(BASE + "/v1/gateway/openai-key")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"stored\": true}", MediaType.APPLICATION_JSON));
        assertThat(client.status()).isEqualTo(new GatewayKeyClient.KeyStatus(true, null));
        server.verify();
    }

    @Test
    void registersKeyAsApiKeyField() {
        server.expect(requestTo(BASE + "/v1/gateway/openai-key")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"api_key\": \"" + KEY + "\"}"))
                .andRespond(withSuccess("{\"message\": \"Key stored\"}", MediaType.APPLICATION_JSON));
        assertThat(client.register(KEY)).isEqualTo(new GatewayKeyClient.Registration(true, "Key stored"));
        server.verify();
    }

    @Test
    void rejectionNeverEchoesTheKey() {
        server.expect(requestTo(BASE + "/v1/gateway/openai-key"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": \"Invalid key " + KEY + "\"}"));
        GatewayKeyClient.Registration r = client.register(KEY);
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).doesNotContain(KEY).contains("HTTP 400").contains("***");
    }

    @Test
    void withoutTeamKeyNothingIsSent() {
        GatewayKeyClient unconfigured = new GatewayKeyClient(RestClient.builder().baseUrl(BASE), false);
        assertThat(unconfigured.status().registered()).isNull();
        assertThat(unconfigured.register(KEY).ok()).isFalse();
    }
}
