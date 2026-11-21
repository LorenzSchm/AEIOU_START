package ai.aeiou.forwarder.fiveu;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Raw outcome of a 5U call. Never thrown as an exception: callers branch on {@link #status()}
 * (429 rate limited, 410 rate expired, 503 portal down, ...).
 *
 * @param status  HTTP status code
 * @param headers response headers (rate-limit headers live here)
 * @param body    parsed body for 2xx responses, otherwise null
 * @param rawBody raw response text; for errors it holds the 5U message (e.g. where else to get a rate)
 */
public record ApiResponse<T>(int status, HttpHeaders headers, T body, String rawBody) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> MESSAGE_FIELDS = List.of("detail", "message", "error");

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean isRateLimited() {
        return status == 429;
    }

    /** {@code Retry-After} in seconds (delta-seconds or HTTP-date form), if the server sent one. */
    public OptionalInt retryAfterSeconds() {
        OptionalInt seconds = intHeader(HttpHeaders.RETRY_AFTER);
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (seconds.isPresent() || value == null) return seconds;
        try {
            Instant at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return OptionalInt.of((int) Math.max(0, Duration.between(Instant.now(), at).toSeconds()));
        } catch (DateTimeParseException e) {
            return OptionalInt.empty();
        }
    }

    /** {@code X-RateLimit-Limit}: the current per-minute limit. */
    public OptionalInt rateLimitLimit() {
        return intHeader("X-RateLimit-Limit");
    }

    /** {@code X-RateLimit-Remaining}: calls left in the current window. */
    public OptionalInt rateLimitRemaining() {
        return intHeader("X-RateLimit-Remaining");
    }

    /**
     * The plain-text error of a FastAPI-style body ({@code detail}, {@code message} or {@code error}), or null.
     * Structured details (validation errors that may quote the input) are deliberately not returned.
     */
    public String errorDetail() {
        if (rawBody == null || rawBody.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(rawBody);
            for (String field : MESSAGE_FIELDS) {
                if (node.path(field).isString()) return node.path(field).asString();
            }
        } catch (JacksonException ignored) {
            // not JSON
        }
        return null;
    }

    /** {@link #errorDetail()}, else the raw body; null when there is no body. */
    public String errorMessage() {
        String detail = errorDetail();
        return detail != null ? detail : rawBody == null || rawBody.isBlank() ? null : rawBody;
    }

    public <R> ApiResponse<R> map(Function<T, R> mapper) {
        return new ApiResponse<>(status, headers, body == null ? null : mapper.apply(body), rawBody);
    }

    private OptionalInt intHeader(String name) {
        String value = headers == null ? null : headers.getFirst(name);
        if (value == null) return OptionalInt.empty();
        try {
            return OptionalInt.of((int) Math.ceil(Double.parseDouble(value.trim())));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
