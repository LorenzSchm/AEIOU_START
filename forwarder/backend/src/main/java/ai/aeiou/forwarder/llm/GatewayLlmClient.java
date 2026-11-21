package ai.aeiou.forwarder.llm;

import ai.aeiou.forwarder.config.CoreConfig;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link LlmClient} against the 5U gateway's OpenAI-compatible
 * {@code POST /v1/gateway/chat/completions}, authenticated with the team key as Bearer token.
 *
 * <p>{@link #structured} sends {@code response_format: json_schema} (strict) with a schema derived
 * from the target record by {@link JsonSchemaGenerator}. Every attempt is recorded with tokens and
 * cost. A 5xx, a network error or an answer that does not parse is retried exactly once.
 */
@Service
public class GatewayLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayLlmClient.class);
    private static final String PATH = "/chat/completions";  // relative to the LLM base url (gateway or direct)
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient http;
    private final JsonMapper json;
    private final LlmCallRecorder recorder;

    public GatewayLlmClient(@Qualifier(CoreConfig.LLM_REST_CLIENT) RestClient http, JsonMapper json,
                            LlmCallRecorder recorder) {
        this.http = http;
        this.json = json;
        this.recorder = recorder;
    }

    @Override
    public <T> LlmResult<T> structured(LlmRequest request, Class<T> type) {
        Map<String, Object> responseFormat = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", type.getSimpleName(),
                        "strict", true,
                        "schema", JsonSchemaGenerator.forRecord(type)));
        return call(request, responseFormat, content -> json.readValue(content, type));
    }

    @Override
    public LlmResult<String> text(LlmRequest request) {
        return call(request, null, content -> content);
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(String content);
    }

    /** Outcome of one HTTP attempt before parsing. */
    private record Attempt(int status, String raw, long latencyMs) {}

    private <T> LlmResult<T> call(LlmRequest request, Map<String, Object> responseFormat, Parser<T> parser) {
        String body = json.writeValueAsString(requestBody(request, responseFormat));
        String lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Attempt res;
            try {
                res = post(body);
            } catch (ResourceAccessException e) {
                lastError = "network: " + e.getMessage();
                recordFailure(request, body, null, 0, 0, 0, lastError);
                continue;
            }
            if (res.status() < 200 || res.status() >= 300) {
                lastError = "HTTP " + res.status() + ": " + abbreviate(res.raw());
                recordFailure(request, body, res.raw(), 0, 0, res.latencyMs(), lastError);
                if (res.status() >= 500) continue;
                break; // 4xx will not get better on retry
            }

            JsonNode root = parseTree(res.raw());
            String model = text(root.path("model"), request.model());
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            double cost = ModelPricing.costUsd(model, promptTokens, completionTokens);
            JsonNode message = root.path("choices").path(0).path("message");
            String content = text(message.path("content"), null);
            String refusal = text(message.path("refusal"), null);
            try {
                if (refusal != null) throw new LlmException("model refused: " + refusal);
                if (content == null) throw new LlmException("empty completion");
                T value = parser.parse(content);
                recorder.record(new LlmCallRecorder.Call(request.sessionId(), request.purpose(), model, promptTokens,
                        completionTokens, cost, res.latencyMs(), true, body, res.raw(), null));
                return new LlmResult<>(value, model, promptTokens, completionTokens, cost, res.latencyMs());
            } catch (JacksonException | LlmException e) {
                lastError = "unusable answer: " + e.getMessage();
                recordFailure(request, body, res.raw(), promptTokens, completionTokens, res.latencyMs(), lastError,
                        model, cost);
                if (refusal != null) break;
            }
        }
        throw new LlmException("LLM call '" + request.purpose() + "' failed: " + lastError);
    }

    private Attempt post(String body) {
        long start = System.nanoTime();
        return http.post().uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> new Attempt(
                        res.getStatusCode().value(),
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8),
                        (System.nanoTime() - start) / 1_000_000));
    }

    private Map<String, Object> requestBody(LlmRequest request, Map<String, Object> responseFormat) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();
        body.put("messages", messages);
        if (request.temperature() != null && supportsTemperature(request.model())) {
            body.put("temperature", request.temperature());
        }
        if (responseFormat != null) body.put("response_format", responseFormat);
        return body;
    }

    /** Reasoning models (gpt-5*, o*) only accept the default temperature. */
    private static boolean supportsTemperature(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return !(m.startsWith("gpt-5") || m.matches("^o\\d.*"));
    }

    private void recordFailure(LlmRequest request, String body, String raw, int prompt, int completion,
                               long latencyMs, String error) {
        recordFailure(request, body, raw, prompt, completion, latencyMs, error, request.model(), 0);
    }

    private void recordFailure(LlmRequest request, String body, String raw, int prompt, int completion,
                               long latencyMs, String error, String model, double cost) {
        log.warn("LLM {} attempt failed: {}", request.purpose(), error);
        recorder.record(new LlmCallRecorder.Call(request.sessionId(), request.purpose(), model, prompt, completion,
                cost, latencyMs, false, body, asJsonOrWrapped(raw, error), error));
    }

    /** {@code llm_call.response} is JSONB: keep valid JSON, wrap anything else. */
    private String asJsonOrWrapped(String raw, String error) {
        if (raw != null) {
            try {
                json.readTree(raw);
                return raw;
            } catch (JacksonException ignored) {
                // not JSON, wrap below
            }
        }
        return json.writeValueAsString(Map.of("error", error, "raw", raw == null ? "" : abbreviate(raw)));
    }

    private JsonNode parseTree(String raw) {
        try {
            return json.readTree(raw);
        } catch (JacksonException e) {
            return json.createObjectNode();
        }
    }

    private static String text(JsonNode node, String fallback) {
        return node.isString() ? node.asString() : fallback;
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
