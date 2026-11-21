package ai.aeiou.forwarder.fiveu;

import java.util.OptionalInt;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;

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

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean isRateLimited() {
        return status == 429;
    }

    /** {@code Retry-After} in seconds, if the server sent one. */
    public OptionalInt retryAfterSeconds() {
        return intHeader(HttpHeaders.RETRY_AFTER);
    }

    /** {@code X-RateLimit-Limit}: the current per-minute limit. */
    public OptionalInt rateLimitLimit() {
        return intHeader("X-RateLimit-Limit");
    }

    /** {@code X-RateLimit-Remaining}: calls left in the current window. */
    public OptionalInt rateLimitRemaining() {
        return intHeader("X-RateLimit-Remaining");
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
