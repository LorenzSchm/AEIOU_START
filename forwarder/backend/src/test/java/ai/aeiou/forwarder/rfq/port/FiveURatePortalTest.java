package ai.aeiou.forwarder.rfq.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.aeiou.forwarder.fiveu.FiveUClient;
import ai.aeiou.forwarder.rfq.port.RatePortal.RateQuery;
import ai.aeiou.forwarder.rfq.port.RatePortal.RateResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The adapter over a real {@link FiveUClient} against canned 5U responses; nothing leaves the JVM. */
class FiveURatePortalTest {

    private static final String BASE = "http://portal.test";
    private MockRestServiceServer server;
    private FiveURatePortal portal;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        portal = new FiveURatePortal(new FiveUClient(builder.build(), JsonMapper.builder().build()), Clock.systemUTC());
    }

    @Test
    void parsesRateAndRateLimitHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-ratelimit-limit", "10");
        headers.add("x-ratelimit-remaining", "9");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v1/portal/rates")))
                .andExpect(queryParam("airline", "HX"))
                .andExpect(queryParam("weight_kg", "250"))
                .andRespond(withSuccess("""
                        {"rate_id":"r-123","airline":"Helix Air Freight","airline_code":"HX","origin":"FRA",
                         "destination":"SIN","weight_kg":"250","chargeable_weight_kg":"250","total":"1387.35",
                         "currency":"USD","fx_eur_per_usd":"0.92","transit_days":4,
                         "valid_until":"2026-11-21T15:08:39.958327+00:00","dataset_version":"2026-11"}""", MediaType.APPLICATION_JSON)
                        .headers(headers));

        RateResult res = portal.rate(new RateQuery("FRA", "SIN", "HX", new BigDecimal("250"), null));

        assertThat(res.ok()).isTrue();
        assertThat(res.rate().rateId()).isEqualTo("r-123");
        assertThat(res.rate().total()).isEqualByComparingTo("1387.35");
        assertThat(res.rate().fxEurPerUnit()).isEqualByComparingTo("0.92");
        assertThat(res.rate().transitDays()).isEqualTo(4);
        assertThat(res.rate().validUntil()).isNotNull();
        assertThat(res.rateLimitRemaining()).isEqualTo(9);
        assertThat(res.rateLimitLimit()).isEqualTo(10);
        server.verify();
    }

    @Test
    void reports429WithRetryAfter() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v1/portal/rates")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "17")
                        .header("X-RateLimit-Remaining", "0").body("{\"detail\":\"rate limit\"}"));

        RateResult res = portal.rate(new RateQuery("FRA", "SIN", "HX", BigDecimal.TEN, null));

        assertThat(res.rateLimited()).isTrue();
        assertThat(res.retryAfter()).isEqualTo(Duration.ofSeconds(17));
        assertThat(res.rateLimitRemaining()).isZero();
    }

    @Test
    void reports503WithTheFallbackMessage() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v1/portal/rates")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("{\"detail\":\"Portal unavailable, ask ratedesk@hackloop.mail\"}"));

        RateResult res = portal.rate(new RateQuery("FRA", "SIN", "HX", BigDecimal.TEN, null));

        assertThat(res.unavailable()).isTrue();
        assertThat(res.message()).isEqualTo("Portal unavailable, ask ratedesk@hackloop.mail");
    }

    @Test
    void networkFailureIsStatusZero() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v1/portal/rates")))
                .andRespond(withException(new IOException("reset")));

        RateResult res = portal.rate(new RateQuery("FRA", "SIN", "HX", BigDecimal.TEN, null));

        assertThat(res.status()).isZero();
        assertThat(res.ok()).isFalse();
    }

    @Test
    void cachesLanesAndReadsBrackets() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/v1/portal/ports"))
                .andRespond(withSuccess("""
                        {"lanes":[{"origin":"FRA","destination":"SIN","airlines":[{"code":"HX","name":"Helix Air Freight"}]}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/portal/brackets?airline=HX"))
                .andRespond(withSuccess("{\"airline\":\"Helix Air Freight\",\"airline_code\":\"HX\",\"breaks_kg\":[0,45,100,300,500,1000]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/portal/brackets?airline=ZZ"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"detail\":\"email zz@hackloop.mail\"}"));

        assertThat(portal.lanes()).singleElement().satisfies(l -> {
            assertThat(l.destination()).isEqualTo("SIN");
            assertThat(l.airlines()).extracting(RatePortal.Airline::code).containsExactly("HX");
        });
        assertThat(portal.lanes()).hasSize(1); // cached, no second call
        assertThat(portal.brackets("HX")).hasValueSatisfying(b -> assertThat(b.breaksKg()).containsExactly(0, 45, 100, 300, 500, 1000));
        assertThat(portal.brackets("ZZ")).isEmpty();
        server.verify();
    }
}
