package ai.aeiou.forwarder.fiveu;

import ai.aeiou.forwarder.config.CoreConfig;
import ai.aeiou.forwarder.fiveu.PortalModels.Booking;
import ai.aeiou.forwarder.fiveu.PortalModels.Brackets;
import ai.aeiou.forwarder.fiveu.PortalModels.Ports;
import ai.aeiou.forwarder.fiveu.PortalModels.Rate;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thin typed client for the 5U mailbox, rate portal and board.
 *
 * <p>Portal and send calls return an {@link ApiResponse} instead of throwing, so callers can react
 * to 429 ({@code Retry-After}, {@code X-RateLimit-*}), 410 (expired rate) and 503 (portal down).
 * Network failures (timeouts, connection refused) surface as Spring's {@code ResourceAccessException}.
 *
 * <p>Chat completions have their own client ({@code GatewayLlmClient}); this class only manages the
 * gateway's OpenAI key registration.
 */
@Component
public class FiveUClient {

    /** Largest page the mailbox allows. */
    public static final int MAX_POLL_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(FiveUClient.class);

    private final RestClient http;
    private final JsonMapper snakeCase;

    public FiveUClient(@Qualifier(CoreConfig.FIVEU_REST_CLIENT) RestClient http, JsonMapper jsonMapper) {
        this.http = http;
        this.snakeCase = jsonMapper.rebuild()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    // ---------------------------------------------------------------- mailbox

    /** One page of a mailbox poll; {@code nextSinceId} is the value to pass on the next call. */
    public record MailboxPage(List<FiveUMessage> messages, long nextSinceId) {}

    /** {@code GET /v1/mailbox/messages}: mail sent to us with id &gt; {@code sinceId}, oldest first. */
    public MailboxPage poll(long sinceId, int limit) {
        JsonNode root = http.get()
                .uri(u -> u.path("/v1/mailbox/messages")
                        .queryParam("since_id", sinceId)
                        .queryParam("limit", Math.min(limit, MAX_POLL_LIMIT))
                        .build())
                .retrieve()
                .body(JsonNode.class);
        return parsePage(root, sinceId);
    }

    /** {@code GET /v1/mailbox/messages/{id}}: one mail we sent or received. */
    public FiveUMessage readMessage(long messageId) {
        JsonNode node = http.get().uri("/v1/mailbox/messages/{id}", messageId).retrieve().body(JsonNode.class);
        return FiveUMessage.from(node);
    }

    /** Raw bytes of one attachment. */
    public byte[] downloadAttachment(long messageId, long attachmentId) {
        byte[] bytes = http.get()
                .uri("/v1/mailbox/messages/{id}/attachments/{aid}", messageId, attachmentId)
                .retrieve()
                .body(byte[].class);
        return bytes == null ? new byte[0] : bytes;
    }

    /** Body of {@code POST /v1/mailbox/messages}. {@code inReplyTo} keeps the thread. */
    public record SendRequest(String to, String subject, String body, Long inReplyTo, List<SendAttachment> attachments) {}

    public record SendAttachment(String filename, String contentType, byte[] content) {}

    /** Sends a mail. 201 carries the stored mail; 429 means the 60/min send limit was hit. */
    public ApiResponse<FiveUMessage> send(SendRequest request) {
        List<Map<String, String>> atts = new ArrayList<>();
        for (SendAttachment a : request.attachments() == null ? List.<SendAttachment>of() : request.attachments()) {
            atts.add(Map.of(
                    "filename", a.filename(),
                    "content_type", a.contentType() == null ? "application/octet-stream" : a.contentType(),
                    "content_base64", Base64.getEncoder().encodeToString(a.content())));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("to", request.to());
        payload.put("subject", request.subject() == null ? "" : request.subject());
        payload.put("body", request.body() == null ? "" : request.body());
        payload.put("in_reply_to", request.inReplyTo());
        payload.put("attachments", atts);
        return exchange(http.post().uri("/v1/mailbox/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(snakeCase.writeValueAsString(payload)), JsonNode.class)
                .map(FiveUMessage::from);
    }

    // ---------------------------------------------------------------- portal

    /** {@code GET /v1/portal/ports}: lanes and the airlines that can be priced on them. Not rate-limited. */
    public ApiResponse<Ports> ports() {
        return exchange(http.get().uri("/v1/portal/ports"), Ports.class);
    }

    /** {@code GET /v1/portal/brackets}: weight breaks of one airline; 404 if the airline is not on the portal. */
    public ApiResponse<Brackets> brackets(String airline) {
        return exchange(http.get().uri(u -> u.path("/v1/portal/brackets").queryParam("airline", airline).build()),
                Brackets.class);
    }

    /** {@code GET /v1/portal/rates}: one priced rate. Rate-limited per minute (see {@link ApiResponse#rateLimitRemaining()}). */
    public ApiResponse<Rate> rate(String origin, String destination, String airline, BigDecimal weightKg, BigDecimal volumeCbm) {
        return exchange(http.get().uri(u -> u.path("/v1/portal/rates")
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .queryParam("airline", airline)
                .queryParam("weight_kg", plain(weightKg))
                .queryParam("volume_cbm", volumeCbm == null ? "0" : plain(volumeCbm))
                .build()), Rate.class);
    }

    /** {@code POST /v1/portal/bookings}: 201 booked, 200 retry, 404/409/410/503 see the 5U spec. */
    public ApiResponse<Booking> book(String rateId, String teamReference) {
        String json = snakeCase.writeValueAsString(Map.of("rate_id", rateId, "team_reference", teamReference));
        return exchange(http.post().uri("/v1/portal/bookings").contentType(MediaType.APPLICATION_JSON).body(json),
                Booking.class);
    }

    // ---------------------------------------------------------------- board

    /** {@code GET /v1/board/me}: our verdicts, reason codes and points per request. */
    public ApiResponse<JsonNode> boardMe() {
        return exchange(http.get().uri("/v1/board/me"), JsonNode.class);
    }

    /** {@code GET /v1/board}: the public leaderboard. */
    public ApiResponse<JsonNode> board() {
        return exchange(http.get().uri("/v1/board"), JsonNode.class);
    }

    // ---------------------------------------------------------------- gateway key

    /**
     * {@code POST /v1/gateway/openai-key}: registers the team's own OpenAI key with the 5U gateway.
     * The key is sent once and never logged, stored or put into an event; only the outcome is logged.
     */
    public ApiResponse<JsonNode> registerOpenAiKey(String apiKey) {
        String json = snakeCase.writeValueAsString(Map.of("api_key", apiKey));
        ApiResponse<JsonNode> res = exchange(http.post().uri("/v1/gateway/openai-key")
                .contentType(MediaType.APPLICATION_JSON).body(json), JsonNode.class);
        if (res.isSuccess()) {
            log.info("OpenAI key registered: ok");
        } else {
            log.warn("OpenAI key registered: failed (HTTP {}) {}", res.status(), redact(Objects.toString(res.errorDetail(), ""), apiKey));
        }
        return res;
    }

    /**
     * {@code GET /v1/gateway/openai-key} answer, e.g. {@code {"set":true,"last4":"b_wA","checked_at":"..."}}.
     *
     * @param set       whether the gateway holds a key for our team
     * @param last4     the last four characters of the stored key, as shown by 5U
     * @param checkedAt when 5U last validated the key (ISO-8601), may be null
     */
    public record OpenAiKeyStatus(Boolean set, String last4, String checkedAt) {}

    /** {@code GET /v1/gateway/openai-key}: whether a key is registered, as reported by 5U. */
    public ApiResponse<OpenAiKeyStatus> openAiKeyStatus() {
        return exchange(http.get().uri("/v1/gateway/openai-key"), OpenAiKeyStatus.class);
    }

    /** Replaces every occurrence of {@code secret} in {@code text} with {@code ***}. */
    public static String redact(String text, String secret) {
        if (text == null || secret == null || secret.isEmpty()) return text;
        return text.replace(secret, "***");
    }

    // ---------------------------------------------------------------- internals

    /** "250" rather than "250.00": the portal keys identical queries by their text. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private <T> ApiResponse<T> exchange(RestClient.RequestHeadersSpec<?> spec, Class<T> type) {
        return spec.exchange((request, response) -> {
            int status = response.getStatusCode().value();
            HttpHeaders headers = HttpHeaders.copyOf(response.getHeaders());
            String raw = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
            T body = null;
            if (status >= 200 && status < 300 && !raw.isBlank()) {
                try {
                    body = snakeCase.readValue(raw, type);
                } catch (JacksonException e) {
                    throw new IOException("Unexpected 5U response for " + request.getURI().getPath() + ": " + raw, e);
                }
            }
            return new ApiResponse<>(status, headers, body, raw);
        });
    }

    static MailboxPage parsePage(JsonNode root, long sinceId) {
        JsonNode items = root == null ? null : root.isArray() ? root : firstArray(root, "messages", "items", "data", "results");
        List<FiveUMessage> messages = new ArrayList<>();
        long maxId = sinceId;
        if (items != null) {
            for (JsonNode item : items) {
                FiveUMessage m = FiveUMessage.from(item);
                messages.add(m);
                maxId = Math.max(maxId, m.id());
            }
        }
        JsonNode next = root == null ? null : root.path("next_since_id");
        long nextSinceId = next != null && next.isNumber() ? Math.max(next.asLong(), sinceId) : maxId;
        return new MailboxPage(List.copyOf(messages), nextSinceId);
    }

    private static JsonNode firstArray(JsonNode root, String... names) {
        for (String name : names) {
            if (root.path(name).isArray()) return root.path(name);
        }
        return null;
    }
}
