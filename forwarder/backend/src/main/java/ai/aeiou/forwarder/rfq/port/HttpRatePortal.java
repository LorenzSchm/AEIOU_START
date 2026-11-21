package ai.aeiou.forwarder.rfq.port;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link RatePortal} over the 5U REST API ({@code docs/5u-openapi.json}). Authenticates with {@code X-API-Key}.
 * Lanes change rarely (at most with the hourly setup change), so they are cached for {@link #LANES_TTL}.
 */
@Component
public class HttpRatePortal implements RatePortal {

    static final Duration LANES_TTL = Duration.ofSeconds(60);
    private static final Logger log = LoggerFactory.getLogger(HttpRatePortal.class);

    private final RestClient http;
    private final JsonMapper json;
    private final Clock clock;
    private volatile CachedLanes cachedLanes;

    @Autowired
    public HttpRatePortal(@Value("${app.fiveu.base-url}") String baseUrl,
                          @Value("${app.fiveu.api-key:}") String apiKey) {
        this(RestClient.builder(), baseUrl, apiKey, Clock.systemUTC());
    }

    /** Test seam: lets tests bind a {@code MockRestServiceServer} to the builder. */
    HttpRatePortal(RestClient.Builder builder, String baseUrl, String apiKey, Clock clock) {
        this.http = builder.baseUrl(baseUrl).defaultHeader("X-API-Key", apiKey).build();
        this.json = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        this.clock = clock;
    }

    @Override
    public List<Lane> lanes() {
        CachedLanes cached = cachedLanes;
        if (cached != null && clock.instant().isBefore(cached.expiresAt())) return cached.lanes();
        String body = http.get().uri("/v1/portal/ports").retrieve().body(String.class);
        List<Lane> lanes = List.copyOf(json.readValue(body, PortsDto.class).lanes());
        cachedLanes = new CachedLanes(lanes, clock.instant().plus(LANES_TTL));
        return lanes;
    }

    @Override
    public Optional<Brackets> brackets(String airline) {
        return http.get()
                .uri(b -> b.path("/v1/portal/brackets").queryParam("airline", airline).build())
                .exchange((req, res) -> {
                    if (res.getStatusCode().value() != 200) return Optional.<Brackets>empty();
                    return Optional.of(json.readValue(read(res.getBody()), Brackets.class));
                });
    }

    @Override
    public RateResult rate(RateQuery q) {
        try {
            return http.get()
                    .uri(b -> b.path("/v1/portal/rates")
                            .queryParam("origin", q.origin())
                            .queryParam("destination", q.destination())
                            .queryParam("airline", q.airline())
                            .queryParam("weight_kg", q.weightKg().toPlainString())
                            .queryParam("volume_cbm", q.volumeCbm() == null ? "0" : q.volumeCbm().toPlainString())
                            .build())
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        HttpHeaders h = res.getHeaders();
                        String body = read(res.getBody());
                        Rate rate = status == 200 ? toRate(json.readValue(body, RateDto.class)) : null;
                        return new RateResult(status, rate, intHeader(h, "X-RateLimit-Limit"),
                                intHeader(h, "X-RateLimit-Remaining"), retryAfter(h),
                                status == 200 ? null : errorMessage(body));
                    });
        } catch (RestClientException | JacksonException e) {
            log.warn("Portal rate call failed for {}: {}", q, e.getMessage());
            return new RateResult(0, null, null, null, null, e.getMessage());
        }
    }

    private static Rate toRate(RateDto d) {
        return new Rate(d.rateId(), d.airline(), d.airlineCode(), decimal(d.total()), d.currency(),
                decimal(d.fxEurPerUsd()), decimal(d.chargeableWeightKg()), d.transitDays(),
                d.validUntil() == null ? null : OffsetDateTime.parse(d.validUntil()), d.datasetVersion());
    }

    /** FastAPI errors are {@code {"detail": "..."}}; anything else is returned raw. */
    private String errorMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode detail = json.readTree(body).get("detail");
            if (detail != null) return detail.isString() ? detail.asString() : detail.toString();
        } catch (JacksonException ignored) {
            // not JSON: fall through to the raw body
        }
        return body;
    }

    private Duration retryAfter(HttpHeaders h) {
        String v = h.getFirst(HttpHeaders.RETRY_AFTER);
        if (v == null) return null;
        try {
            return Duration.ofSeconds(Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            try { // HTTP-date form
                Instant at = OffsetDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Duration.between(clock.instant(), at).abs();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static Integer intHeader(HttpHeaders h, String name) {
        String v = h.getFirst(name);
        try {
            return v == null ? null : Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(String s) {
        return s == null || s.isBlank() ? null : new BigDecimal(s.trim());
    }

    private static String read(java.io.InputStream in) throws java.io.IOException {
        return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
    }

    private record CachedLanes(List<Lane> lanes, Instant expiresAt) {}

    private record PortsDto(List<Lane> lanes) {}

    private record RateDto(String rateId, String airline, String airlineCode, String total, String currency,
                           String fxEurPerUsd, String chargeableWeightKg, Integer transitDays, String validUntil,
                           String datasetVersion) {}
}
