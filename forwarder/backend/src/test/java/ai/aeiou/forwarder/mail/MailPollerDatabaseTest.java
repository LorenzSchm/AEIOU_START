package ai.aeiou.forwarder.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.aeiou.forwarder.CoreDatabaseTest;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.fiveu.FiveUClient.MailboxPage;
import ai.aeiou.forwarder.fiveu.FiveUMessage;
import ai.aeiou.forwarder.llm.LlmClient.LlmRequest;
import ai.aeiou.forwarder.llm.LlmClient.LlmResult;
import ai.aeiou.forwarder.router.Extraction;
import ai.aeiou.forwarder.router.MailType;
import ai.aeiou.forwarder.router.Router;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.settings.SettingsService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Poller storage idempotency and session assignment against a real Postgres. */
class MailPollerDatabaseTest extends CoreDatabaseTest {

    private static final AtomicLong IDS = new AtomicLong(10_000);

    @Autowired MailRepository mails;
    @Autowired MailGateway gateway;
    @Autowired Router router;
    @Autowired EventLog events;
    @Autowired SessionService sessions;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired RecordingHandler handler;
    @Autowired SettingsService settings;

    private MailPoller poller;

    @BeforeEach
    void setUp() {
        // Route synchronously so assertions see the result; production uses one virtual thread per mail.
        poller = new MailPoller(fiveU, mails, router, events, json, Runnable::run, settings);
        handler.received.clear();
    }

    @Test
    void storesEachMailOnceAndThreadsTheReplyIntoTheSameSession() {
        long first = IDS.incrementAndGet();
        long reply = IDS.incrementAndGet();
        MailboxPage page = new MailboxPage(List.of(
                message(first, "buyer@acme.example", "Air freight quote FRA-JFK", null),
                message(reply, "buyer@acme.example", "Re: Air freight quote FRA-JFK", first)), reply);
        MailboxPage drained = new MailboxPage(List.of(), reply);
        when(fiveU.poll(anyLong(), anyInt())).thenReturn(page, drained, page, drained);
        when(llm.structured(any(), eq(Extraction.class)))
                .thenReturn(llmResult(MailType.NEW_RFQ, 0.95), llmResult(MailType.FOLLOW_UP, 0.9));

        assertThat(poller.pollOnce()).isEqualTo(2);
        assertThat(poller.pollOnce()).as("replayed mails are ignored").isZero();

        StoredMail rfq = mails.findByExternalId(first).orElseThrow();
        StoredMail followUp = mails.findByExternalId(reply).orElseThrow();
        assertThat(count("SELECT count(*) FROM mail WHERE external_id IN (?, ?)", first, reply)).isEqualTo(2);
        assertThat(rfq.sessionId()).isNotNull().isEqualTo(followUp.sessionId());
        assertThat(followUp.threadRootExt()).isEqualTo(first);
        assertThat(mails.sinceId()).isGreaterThanOrEqualTo(reply);

        SessionService.SessionView session = sessions.find(rfq.sessionId()).orElseThrow();
        assertThat(session.type()).isEqualTo("RFQ");
        assertThat(session.publicRef()).matches("AEI-[34679ACDEFGHJKMNPQRTUVWXY]{5}");
        assertThat(session.deadlineAt()).isEqualTo(rfq.sentAt().plus(Duration.ofMinutes(6)));
        assertThat(mails.routeType(followUp.id())).contains("FOLLOW_UP");
        assertThat(count("SELECT count(*) FROM event WHERE session_id = ? AND kind = 'mail.in'", rfq.sessionId()))
                .isEqualTo(2);
        assertThat(count("SELECT mail_count FROM session WHERE id = ?", rfq.sessionId())).isEqualTo(2);
        assertThat(handler.received).extracting(r -> r.type()).containsExactly(MailType.NEW_RFQ, MailType.FOLLOW_UP);
    }

    @Test
    void carrierReplyToOurBidRequestJoinsTheBidRequestsSession() {
        String carrierEmail = "test-air-" + IDS.incrementAndGet() + "@hackloop.mail";
        long carrierId = jdbc.sql("INSERT INTO carrier (name, kind, email, serves) VALUES ('Test Air', 'airline', ?, '{JFK}') RETURNING id")
                .param(carrierEmail).query(Long.class).single();
        long sessionId = sessions.create("RFQ", "buyer@acme.example", "Quote", null);
        StoredMail request = gateway.send(sessionId, OutgoingMail.fresh(carrierEmail, "Rate request FRA-JFK", "Hi"));
        jdbc.sql("INSERT INTO bid_request (session_id, carrier_id, kind, out_mail_id) VALUES (?, ?, 'airline', ?)")
                .params(sessionId, carrierId, request.id()).update();

        long bidId = IDS.incrementAndGet();
        when(fiveU.poll(anyLong(), anyInt())).thenReturn(
                new MailboxPage(List.of(message(bidId, carrierEmail, "Re: Rate request FRA-JFK", request.externalId())), bidId),
                new MailboxPage(List.of(), bidId));
        when(llm.structured(any(), eq(Extraction.class))).thenReturn(llmResult(MailType.CARRIER_BID, 0.9));

        poller.pollOnce();

        StoredMail bid = mails.findByExternalId(bidId).orElseThrow();
        assertThat(request.externalId()).as("dry run uses negative ids").isNegative();
        assertThat(bid.sessionId()).isEqualTo(sessionId);
        assertThat(mails.routeType(bid.id())).contains("CARRIER_BID");
        ArgumentCaptor<LlmRequest> llmRequest = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).structured(llmRequest.capture(), eq(Extraction.class));
        assertThat(llmRequest.getValue().sessionId()).isEqualTo(sessionId);
        assertThat(llmRequest.getValue().messages().get(1).content()).contains("replies to a bid request we sent");
        assertThat(handler.received).singleElement().satisfies(r -> {
            assertThat(r.stage()).isEqualTo(1);
            assertThat(r.extraction().bid().price()).isEqualByComparingTo("1200");
        });
    }

    @Test
    void pollingEnabledSettingPausesTheScheduledTick() {
        settings.set("pollingEnabled", false, "test");
        try {
            poller.tick();
            verify(fiveU, never()).poll(anyLong(), anyInt());
        } finally {
            settings.set("pollingEnabled", true, "test");
        }
    }

    private FiveUMessage message(long id, String from, String subject, Long inReplyTo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("from", from);
        m.put("to", "aeiou@hackloop.mail");
        m.put("subject", subject);
        m.put("body", "Hello, we need 120 kg from Frankfurt to New York.\nBest, Maria");
        m.put("in_reply_to", inReplyTo);
        m.put("references", inReplyTo == null ? List.of() : List.of(inReplyTo));
        m.put("created_at", "2026-11-21T10:00:00Z");
        m.put("attachments", List.of());
        return FiveUMessage.from(json.valueToTree(m));
    }

    private static LlmResult<Extraction> llmResult(MailType type, double confidence) {
        Extraction.Bid bid = type == MailType.CARRIER_BID
                ? new Extraction.Bid(new BigDecimal("1200"), "EUR", 3, null, "Test Air", false, null) : null;
        Extraction e = new Extraction(type, confidence, "test", "en", "Maria", null, bid, null);
        return new LlmResult<>(e, "gpt-4o-mini", 500, 80, 0.0001, 300);
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }
}
