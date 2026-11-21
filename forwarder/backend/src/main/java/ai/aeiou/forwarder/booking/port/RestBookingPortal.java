package ai.aeiou.forwarder.booking.port;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** {@link BookingPortal} over HTTP against the 5U API, authenticated with {@code X-API-Key}. */
@Component
public class RestBookingPortal implements BookingPortal {

    private static final Logger log = LoggerFactory.getLogger(RestBookingPortal.class);

    private final RestClient http;

    @Autowired
    public RestBookingPortal(ObjectProvider<RestClient.Builder> builders,
                             @Value("${app.fiveu.base-url}") String baseUrl,
                             @Value("${app.fiveu.api-key:}") String apiKey) {
        this(builders.getIfAvailable(RestClient::builder).clone().requestFactory(timeouts()), baseUrl, apiKey);
    }

    /** Uses {@code builder} as is, e.g. one bound to a {@code MockRestServiceServer} in tests. */
    public RestBookingPortal(RestClient.Builder builder, String baseUrl, String apiKey) {
        this.http = builder.clone().baseUrl(baseUrl).defaultHeader("X-API-Key", apiKey).build();
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }

    @Override
    public BookingResult book(String rateId, String teamReference) {
        try {
            return http.post().uri("/v1/portal/bookings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("rate_id", rateId, "team_reference", teamReference))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 200 || status == 201) {
                            return new BookingResult(status, response.bodyTo(BookingOut.class), null);
                        }
                        return new BookingResult(status, null, StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8));
                    });
        } catch (ResourceAccessException e) {
            log.warn("Portal booking unreachable for {}: {}", rateId, e.getMessage());
            return new BookingResult(UNREACHABLE, null, e.getMessage());
        }
    }

    @Override
    public RateResult rate(RateQuery q) {
        try {
            return http.get().uri(b -> b.path("/v1/portal/rates")
                            .queryParam("origin", q.origin())
                            .queryParam("destination", q.destination())
                            .queryParam("airline", q.airline())
                            .queryParam("weight_kg", q.weightKg().stripTrailingZeros().toPlainString())
                            .queryParam("volume_cbm", q.volumeCbm() == null ? "0" : q.volumeCbm().stripTrailingZeros().toPlainString())
                            .build())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 200) return new RateResult(status, response.bodyTo(Rate.class), null);
                        return new RateResult(status, null, StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8));
                    });
        } catch (ResourceAccessException e) {
            log.warn("Portal rate unreachable for {}: {}", q, e.getMessage());
            return new RateResult(UNREACHABLE, null, e.getMessage());
        }
    }
}
