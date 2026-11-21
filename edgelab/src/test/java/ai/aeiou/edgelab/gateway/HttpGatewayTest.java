package ai.aeiou.edgelab.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.aeiou.edgelab.gateway.GatewaySettings.AudioMethod;

/** HttpGateway against a local fake server: request shape, auth, parsing, failures. Never the real gateway. */
class HttpGatewayTest {

    private static final String KEY = "test-key-SECRET-123";

    private FakeGatewayServer server;

    @BeforeEach
    void start() throws Exception {
        server = new FakeGatewayServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpGateway gateway(AudioMethod method) {
        return new HttpGateway(new GatewaySettings(server.baseUrl(), KEY, "audio-model-x", method,
                "vision-model-y", Duration.ofSeconds(5)));
    }

    private static String chatReply(String content) {
        return "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                + Json.quote(content) + "}}]}";
    }

    @Test
    void transcribeViaChatSendsInputAudioWithBearerKey() {
        server.reply = chatReply("  Hello, 340 kilos from Munich to ORD.  ");
        byte[] wav = "RIFF....WAVE".getBytes(StandardCharsets.ISO_8859_1);

        Gateway.Answer a = gateway(AudioMethod.CHAT).transcribe(wav, "wav");

        assertTrue(a.ok(), a.problem());
        assertEquals("Hello, 340 kilos from Munich to ORD.", a.text());
        assertEquals("audio-model-x", a.model());
        assertNull(a.selfRating());
        FakeGatewayServer.Request r = server.last();
        assertEquals("POST", r.method());
        assertEquals("/v1/gateway/chat/completions", r.path());
        assertEquals("Bearer " + KEY, r.authorization());
        Object body = Json.parse(r.body());
        assertEquals("audio-model-x", Json.path(body, "model"));
        assertEquals("input_audio", Json.path(body, "messages", 0, "content", 1, "type"));
        assertEquals("wav", Json.path(body, "messages", 0, "content", 1, "input_audio", "format"));
        assertEquals(Base64.getEncoder().encodeToString(wav),
                Json.path(body, "messages", 0, "content", 1, "input_audio", "data"));
    }

    @Test
    void transcribeViaTranscriptionEndpointSendsMultipart() {
        server.reply = "{\"text\":\"Guten Tag, Angebot 4711\"}";

        Gateway.Answer a = gateway(AudioMethod.TRANSCRIPTIONS).transcribe(new byte[] {1, 2, 3}, "mp3");

        assertTrue(a.ok(), a.problem());
        assertEquals("Guten Tag, Angebot 4711", a.text());
        FakeGatewayServer.Request r = server.last();
        assertEquals("/v1/gateway/audio/transcriptions", r.path());
        assertTrue(r.contentType().startsWith("multipart/form-data; boundary="), r.contentType());
        assertTrue(r.body().contains("name=\"model\"\r\n\r\naudio-model-x"), r.body());
        assertTrue(r.body().contains("filename=\"audio.mp3\""), r.body());
        assertEquals("Bearer " + KEY, r.authorization());
    }

    @Test
    void copyTextSendsLowDetailDataUrlWithFixedPromptAndReadsSelfRating() {
        server.reply = chatReply("RATE SHEET\nMUC | ORD | 2.40 EUR/kg\nCONFIDENCE: 85");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

        Gateway.Answer a = gateway(AudioMethod.CHAT).copyText(png, "image/png");

        assertTrue(a.ok(), a.problem());
        assertEquals("RATE SHEET\nMUC | ORD | 2.40 EUR/kg", a.text());
        assertEquals(0.85, a.selfRating(), 1e-9);
        assertEquals("vision-model-y", a.model());
        Object body = Json.parse(server.last().body());
        assertEquals("vision-model-y", Json.path(body, "model"));
        assertEquals(Gateway.COPY_TEXT_PROMPT, Json.path(body, "messages", 0, "content", 0, "text"));
        assertEquals("low", Json.path(body, "messages", 0, "content", 1, "image_url", "detail"));
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(png),
                Json.path(body, "messages", 0, "content", 1, "image_url", "url"));
    }

    @Test
    void copyTextWithoutSelfRatingKeepsAllTextAndNullRating() {
        server.reply = chatReply("just text");
        Gateway.Answer a = gateway(AudioMethod.CHAT).copyText(new byte[] {1}, "image/jpeg");
        assertEquals("just text", a.text());
        assertNull(a.selfRating());
    }

    @Test
    void httpErrorIsAProblemAndNeverContainsTheKey() {
        server.status = 401;
        server.reply = "{\"error\":{\"message\":\"Invalid key " + KEY + "\"}}";

        Gateway.Answer a = gateway(AudioMethod.CHAT).transcribe(new byte[] {1}, "wav");

        assertFalse(a.ok());
        assertTrue(a.problem().startsWith("gateway HTTP 401"), a.problem());
        assertFalse(a.problem().contains(KEY), a.problem());
        assertEquals("", a.text());
    }

    @Test
    void oddResponsesAreProblemsNotExceptions() {
        for (String reply : List.of("not json", "{\"choices\":[]}", "{\"choices\":[{\"message\":{}}]}")) {
            server.reply = reply;
            Gateway.Answer a = gateway(AudioMethod.CHAT).copyText(new byte[] {1}, "image/png");
            assertFalse(a.ok(), reply);
            assertNotNull(a.problem());
        }
    }

    @Test
    void unreachableGatewayIsAProblem() {
        HttpGateway g = new HttpGateway(new GatewaySettings("http://127.0.0.1:1/v1/gateway", KEY,
                "a", AudioMethod.CHAT, "v", Duration.ofSeconds(2)));
        Gateway.Answer a = g.transcribe(new byte[] {1}, "wav");
        assertFalse(a.ok());
        assertTrue(a.problem().startsWith("gateway call failed"), a.problem());
    }

    @Test
    void noKeyMeansNoCallAndAClearProblem() {
        HttpGateway g = new HttpGateway(new GatewaySettings(server.baseUrl(), null, "a", AudioMethod.CHAT,
                "v", Duration.ofSeconds(2)));
        Gateway.Answer a = g.copyText(new byte[] {1}, "image/png");
        assertEquals("TEAM_API_KEY not set", a.problem());
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void testsRunWithTheGatewayDisabled() {
        // build.gradle sets edgelab.gateway.enabled=false for the test task (unless -PrealGateway).
        if (!"false".equals(System.getProperty("edgelab.gateway.enabled"))) return;
        Gateway g = HttpGateway.fromSettings();
        Gateway.Answer a = g.transcribe(new byte[] {1}, "wav");
        assertFalse(a.ok());
        assertTrue(a.problem().contains("disabled"), a.problem());
    }

    @Test
    void jsonQuotesAndParsesRoundTrip() {
        String tricky = "line1\n\"quoted\" \\ tab\t \u0001 ü";
        Object parsed = Json.parse("{\"a\":[1,2.5,true,null," + Json.quote(tricky) + "],\"b\":{\"c\":\"\\u00fc\"}}");
        assertEquals(tricky, Json.path(parsed, "a", 4));
        assertEquals(2.5, Json.path(parsed, "a", 1));
        assertEquals("ü", Json.path(parsed, "b", "c"));
        assertNull(Json.path(parsed, "a", 9));
        assertTrue(parsed instanceof Map<?, ?>);
    }
}
