package ai.aeiou.forwarder.rfq.port;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * What the RFQ workflow needs from the 5U rate portal ({@code /v1/portal/*}). Kept deliberately small so the
 * workflow does not depend on the full 5U client.
 */
public interface RatePortal {

    /** Every lane the portal can price ({@code GET /v1/portal/ports}). Not rate-limited. */
    List<Lane> lanes();

    /** An airline's weight breaks ({@code GET /v1/portal/brackets}); empty when the airline is not on the portal. */
    Optional<Brackets> brackets(String airline);

    /** One priced rate ({@code GET /v1/portal/rates}). Never throws for HTTP errors; the status is in the result. */
    RateResult rate(RateQuery query);

    record Lane(String origin, String destination, List<Airline> airlines) {}

    record Airline(String code, String name) {}

    record Brackets(String airline, String airlineCode, List<Integer> breaksKg) {}

    record RateQuery(String origin, String destination, String airline, BigDecimal weightKg, BigDecimal volumeCbm) {}

    record Rate(String rateId, String airline, String airlineCode, BigDecimal total, String currency,
                BigDecimal fxEurPerUnit, BigDecimal chargeableKg, Integer transitDays, OffsetDateTime validUntil,
                String datasetVersion) {}

    /**
     * @param status              HTTP status (200, 429, 503, ...; 0 when the request did not reach the portal)
     * @param rate                the rate on 200, otherwise null
     * @param rateLimitLimit      {@code X-RateLimit-Limit}, null when absent
     * @param rateLimitRemaining  {@code X-RateLimit-Remaining}, null when absent
     * @param retryAfter          {@code Retry-After} on 429, null when absent
     * @param message             error message from the body (on 503 it says where else to get the rate)
     */
    record RateResult(int status, Rate rate, Integer rateLimitLimit, Integer rateLimitRemaining, Duration retryAfter,
                      String message) {

        public boolean ok() { return status == 200 && rate != null; }

        public boolean rateLimited() { return status == 429; }

        public boolean unavailable() { return status == 503; }
    }
}
