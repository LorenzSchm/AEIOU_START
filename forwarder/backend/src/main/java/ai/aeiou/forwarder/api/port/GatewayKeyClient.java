package ai.aeiou.forwarder.api.port;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI key registration on the 5U LLM gateway ({@code /v1/gateway/openai-key}).
 * <p>
 * The key passes through in memory only: it is never logged, stored or echoed. Error texts from the gateway are
 * scrubbed of the key before they are returned, because validation errors may quote the input.
 * TODO(integration): replace with FiveUClient.openAiKeyStatus()/registration once the core client is merged.
 */
@Component
public class GatewayKeyClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {};
    private static final List<String> STORED_FIELDS =
            List.of("stored", "registered", "has_key", "hasKey", "key_stored", "configured", "exists", "present");
    private static final List<String> MESSAGE_FIELDS = List.of("message", "detail", "status");

    private final RestClient http;
    private final boolean configured;

    @Autowired
    public GatewayKeyClient(@Value("${app.fiveu.base-url}") String baseUrl,
                            @Value("${app.fiveu.api-key:}") String teamKey) {
        this(defaultBuilder().baseUrl(baseUrl).defaultHeader("X-API-Key", teamKey), !teamKey.isBlank());
    }

    /** For tests: bind a {@code MockRestServiceServer} to the builder before passing it in. */
    GatewayKeyClient(RestClient.Builder builder, boolean configured) {
        this.http = builder.build();
        this.configured = configured;
    }

    public record KeyStatus(Boolean registered, String message) {}

    public record Registration(boolean ok, String message) {}

    public KeyStatus status() {
        if (!configured) return new KeyStatus(null, "TEAM_API_KEY is not configured");
        try {
            Map<String, Object> body = http.get().uri("/v1/gateway/openai-key").retrieve().body(JSON_OBJECT);
            if (body == null) return new KeyStatus(null, "Empty answer from gateway");
            Boolean stored = STORED_FIELDS.stream().map(body::get).filter(Boolean.class::isInstance)
                    .map(Boolean.class::cast).findFirst().orElse(null);
            return new KeyStatus(stored, message(body, stored == null ? "Unrecognized answer: " + body.keySet() : null));
        } catch (RestClientResponseException e) {
            return new KeyStatus(null, "Gateway answered HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            return new KeyStatus(null, "Gateway unreachable");
        }
    }

    public Registration register(String apiKey) {
        if (!configured) return new Registration(false, "TEAM_API_KEY is not configured");
        try {
            Map<String, Object> body = http.post().uri("/v1/gateway/openai-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("api_key", apiKey))
                    .retrieve()
                    .body(JSON_OBJECT);
            return new Registration(true, scrub(message(body, "Key stored"), apiKey));
        } catch (RestClientResponseException e) {
            return new Registration(false, scrub(errorMessage(e), apiKey));
        } catch (RestClientException e) {
            return new Registration(false, "Gateway unreachable");
        }
    }

    private static String errorMessage(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        String prefix = "Gateway rejected the key (HTTP " + status.value() + ")";
        try {
            Map<String, Object> body = e.getResponseBodyAs(JSON_OBJECT);
            String detail = body == null ? null : message(body, null);
            return detail == null ? prefix : prefix + ": " + detail;
        } catch (RuntimeException notJson) {
            return prefix;
        }
    }

    /** Only plain string messages are used; structured validation errors (which quote the input) are dropped. */
    private static String message(Map<String, Object> body, String fallback) {
        if (body == null) return fallback;
        return MESSAGE_FIELDS.stream().map(body::get).filter(String.class::isInstance).map(String.class::cast)
                .findFirst().orElse(fallback);
    }

    private static String scrub(String message, String apiKey) {
        return message == null || apiKey.isEmpty() ? message : message.replace(apiKey, "***");
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder().requestFactory(factory);
    }
}
