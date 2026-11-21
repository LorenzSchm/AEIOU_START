package ai.aeiou.forwarder.board;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Minimal client for {@code GET /v1/board/me}. Kept local to the board package on purpose.
 * TODO(integration): fold into the shared FiveUClient once it is merged.
 */
class BoardClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient http;

    BoardClient(String baseUrl, String apiKey) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-API-Key", apiKey)
                .build();
    }

    /** Raw JSON body; the schema is untyped in the 5U spec, so parsing happens in {@link BoardEntry}. */
    String fetchMine() {
        return http.get().uri("/v1/board/me").retrieve().body(String.class);
    }
}
