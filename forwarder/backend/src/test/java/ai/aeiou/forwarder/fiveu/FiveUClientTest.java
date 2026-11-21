package ai.aeiou.forwarder.fiveu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.aeiou.forwarder.fiveu.FiveUClient.MailboxPage;
import ai.aeiou.forwarder.fiveu.FiveUClient.SendRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Runs against a local mock server only; never the live 5U API. */
class FiveUClientTest {

    private static final String BASE = "http://fiveu.test";

    private MockRestServiceServer server;
    private FiveUClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FiveUClient(builder.build(), JsonMapper.builder().build());
    }

    @Test
    void pollMapsThreadAttachmentsAndTimestamp() {
        server.expect(requestTo(BASE + "/v1/mailbox/messages?since_id=7&limit=200"))
                .andRespond(withSuccess("""
                        {"messages": [{
                            "id": 12, "from": "Maria Keller <Maria@Acme.example>", "to": "aeiou@hackloop.mail",
                            "subject": "Re: quote", "body": "Please book.", "in_reply_to": 11,
                            "references": [9, 11], "created_at": "2026-11-21T10:15:00Z",
                            "attachments": [{"id": 3, "filename": "po.pdf", "content_type": "application/pdf", "size": 1024}]
                        }], "next_since_id": 12}""", MediaType.APPLICATION_JSON));

        MailboxPage page = client.poll(7, 500);

        assertThat(page.nextSinceId()).isEqualTo(12);
        FiveUMessage m = page.messages().getFirst();
        assertThat(m.from()).isEqualTo("maria@acme.example");
        assertThat(m.inReplyTo()).isEqualTo(11L);
        assertThat(m.references()).containsExactly(9L, 11L);
        assertThat(m.threadRootId()).isEqualTo(9L);
        assertThat(m.sentAt()).hasToString("2026-11-21T10:15Z");
        assertThat(m.attachments()).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo(3);
            assertThat(a.filename()).isEqualTo("po.pdf");
        });
    }

    @Test
    void pollWithoutNextSinceIdFallsBackToTheHighestId() {
        server.expect(requestTo(BASE + "/v1/mailbox/messages?since_id=0&limit=200"))
                .andRespond(withSuccess("""
                        {"items": [{"id": 5, "from": "a@b.c", "subject": "x", "body": "y"}]}""", MediaType.APPLICATION_JSON));

        MailboxPage page = client.poll(0, 200);

        assertThat(page.nextSinceId()).isEqualTo(5);
        assertThat(page.messages().getFirst().threadRootId()).isEqualTo(5);
    }

    @Test
    void rateLimitIsReportedNotThrown() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "17");
        headers.add("X-RateLimit-Limit", "30");
        headers.add("X-RateLimit-Remaining", "0");
        server.expect(requestTo(BASE + "/v1/portal/rates?origin=FRA&destination=JFK&airline=PL&weight_kg=120&volume_cbm=0"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body("{\"detail\":\"slow down\"}"));

        ApiResponse<PortalModels.Rate> res = client.rate("FRA", "JFK", "PL", new BigDecimal("120"), null);

        assertThat(res.isRateLimited()).isTrue();
        assertThat(res.body()).isNull();
        assertThat(res.retryAfterSeconds()).hasValue(17);
        assertThat(res.rateLimitLimit()).hasValue(30);
        assertThat(res.rateLimitRemaining()).hasValue(0);
        assertThat(res.rawBody()).contains("slow down");
    }

    @Test
    void rateParsesSnakeCaseDecimalStrings() {
        server.expect(requestTo(BASE + "/v1/portal/rates?origin=FRA&destination=JFK&airline=PL&weight_kg=120&volume_cbm=1.5"))
                .andRespond(withSuccess("""
                        {"rate_id": "R-abcdefgh12", "airline": "Polar", "airline_code": "PL", "origin": "FRA",
                         "destination": "JFK", "weight_kg": "120", "chargeable_weight_kg": "250.50",
                         "total": "1387.35", "currency": "USD", "fx_eur_per_usd": "0.92", "transit_days": 3,
                         "valid_until": "2026-11-21T10:30:00Z", "dataset_version": "v1"}""", MediaType.APPLICATION_JSON));

        PortalModels.Rate rate = client.rate("FRA", "JFK", "PL", new BigDecimal("120"), new BigDecimal("1.5")).body();

        assertThat(rate.rateId()).isEqualTo("R-abcdefgh12");
        assertThat(rate.total()).isEqualByComparingTo("1387.35");
        assertThat(rate.fxEurPerUsd()).isEqualByComparingTo("0.92");
        assertThat(rate.transitDays()).isEqualTo(3);
    }

    @Test
    void bookingFailuresKeepStatusAndMessage() {
        server.expect(requestTo(BASE + "/v1/portal/bookings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"rate_id\":\"R-abcdefgh12\",\"team_reference\":\"AEI-7K2Q9\"}"))
                .andRespond(withStatus(HttpStatus.GONE).body("rate expired"));

        ApiResponse<PortalModels.Booking> res = client.book("R-abcdefgh12", "AEI-7K2Q9");

        assertThat(res.status()).isEqualTo(410);
        assertThat(res.isSuccess()).isFalse();
    }

    @Test
    void sendPostsSnakeCaseAndReturnsTheStoredMail() {
        server.expect(requestTo(BASE + "/v1/mailbox/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"to\":\"buyer@acme.example\",\"subject\":\"Re: quote\",\"in_reply_to\":11}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"id": 13, "from": "aeiou@hackloop.mail", "to": "buyer@acme.example", "subject": "Re: quote",
                         "body": "Hi", "in_reply_to": 11, "references": [9, 11], "created_at": "2026-11-21T10:16:00Z"}"""));

        ApiResponse<FiveUMessage> res = client.send(new SendRequest("buyer@acme.example", "Re: quote", "Hi", 11L, List.of()));

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().id()).isEqualTo(13);
        assertThat(res.body().threadRootId()).isEqualTo(9);
    }

    @Test
    void registerOpenAiKeyPostsTheKeyAndReportsTheOutcome() {
        server.expect(requestTo(BASE + "/v1/gateway/openai-key"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"api_key\":\"sk-test-not-real\"}"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"invalid key sk-test-not-real\"}"));
        server.expect(requestTo(BASE + "/v1/gateway/openai-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"set\":true,\"last4\":\"b_wA\",\"checked_at\":\"2026-11-21T13:52:24.494264Z\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.registerOpenAiKey("sk-test-not-real").status()).isEqualTo(422);
        assertThat(client.openAiKeyStatus().body())
                .isEqualTo(new FiveUClient.OpenAiKeyStatus(true, "b_wA", "2026-11-21T13:52:24.494264Z"));
        server.verify();
    }
}
