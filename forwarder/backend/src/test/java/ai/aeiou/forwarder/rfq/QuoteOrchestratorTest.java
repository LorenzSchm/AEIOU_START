package ai.aeiou.forwarder.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.pricing.FxRates;
import ai.aeiou.forwarder.pricing.PriceQuote;
import ai.aeiou.forwarder.pricing.PricingService;
import ai.aeiou.forwarder.rfq.RfqTestDoubles.FakeMailGateway;
import ai.aeiou.forwarder.rfq.RfqTestDoubles.FakeSessions;
import ai.aeiou.forwarder.rfq.RfqTestDoubles.MapSettings;
import ai.aeiou.forwarder.rfq.RfqTestDoubles.RecordingEventLog;
import ai.aeiou.forwarder.rfq.port.PortalRateLimiter;
import ai.aeiou.forwarder.rfq.port.RatePortal;
import ai.aeiou.forwarder.rfq.port.RatePortal.Airline;
import ai.aeiou.forwarder.rfq.port.RatePortal.Lane;
import ai.aeiou.forwarder.rfq.port.RatePortal.Rate;
import ai.aeiou.forwarder.rfq.port.RatePortal.RateQuery;
import ai.aeiou.forwarder.rfq.port.RatePortal.RateResult;
import ai.aeiou.forwarder.router.Extraction;
import ai.aeiou.forwarder.router.MailType;
import ai.aeiou.forwarder.router.RoutedMail;
import ai.aeiou.forwarder.session.SessionStatus;
import ai.aeiou.forwarder.writer.EmailWriter;
import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import ai.aeiou.forwarder.writer.EmailWriter.Purpose;
import ai.aeiou.forwarder.writer.EmailWriter.WrittenMail;
import ai.aeiou.forwarder.writer.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole RFQ workflow against a real Postgres (Flyway schema), with the 5U portal, the writer and the mailbox
 * replaced by test doubles. Nothing leaves the JVM.
 */
@Testcontainers
class QuoteOrchestratorTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcClient jdbc;

    private static final String CUSTOMER = "buyer@customer.test";
    private static final String AURELIA = "aurelia-air-cargo@hackloop.mail";
    private static final String BOREALIS = "borealis-freight-airways@hackloop.mail";
    private static final String TRUCKER = "rheinufer-spedition@hackloop.mail";
    private static final String RATEDESK = "ratedesk@hackloop.mail";

    private final PricingService pricing = mock(PricingService.class);
    private final RatePortal portal = mock(RatePortal.class);
    private final EmailWriter writer = mock(EmailWriter.class);
    private final MapSettings settingsStore = new MapSettings();
    private final RecordingEventLog eventLog = new RecordingEventLog();

    private FakeMailGateway gateway;
    private FakeSessions sessions;
    private RfqRepository repo;
    private DecisionScheduler scheduler;
    private QuoteOrchestrator orchestrator;
    private QuoteSender sender;
    private RfqHandler handler;
    private long sessionId;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE draft, price_candidate, bid_request, rfq, event, mail, session, carrier, pickup_zone RESTART IDENTITY CASCADE").update();
        jdbc.sql("INSERT INTO pickup_zone (zone, area, airport) VALUES ('64', 'Darmstadt', 'FRA')").update();
        jdbc.sql("""
                INSERT INTO carrier (name, kind, email, serves) VALUES
                  ('Aurelia Air Cargo', 'airline', ?, '{SIN,ORD}'),
                  ('Borealis Freight Airways', 'airline', ?, '{SIN}'),
                  ('Cobalt Skylift', 'airline', 'cobalt-skylift@hackloop.mail', '{JFK}'),
                  ('Rheinufer Spedition', 'trucker', ?, '{20,64}')""").params(AURELIA, BOREALIS, TRUCKER).update();
        sessionId = jdbc.sql("INSERT INTO session (public_ref, type, status, customer_addr) VALUES ('AEI-T1', 'RFQ', 'NEW', ?) RETURNING id")
                .param(CUSTOMER).query(Long.class).single();

        gateway = new FakeMailGateway(jdbc);
        sessions = new FakeSessions(sessionId, CUSTOMER);
        settingsStore.values.put("portalProbeNextBreak", false);
        settingsStore.values.put("autopilot", true); // most tests exercise the automatic path
        RfqSettings settings = new RfqSettings(settingsStore);
        RfqEvents events = new RfqEvents(eventLog, sessions, Clock.systemUTC());
        FxRates fx = new FxRates(settingsStore, new BigDecimal("0.92"));
        repo = new RfqRepository(jdbc, JsonMapper.builder().build());
        ReferenceRepository reference = new ReferenceRepository(jdbc);
        scheduler = new DecisionScheduler();
        sender = new QuoteSender(writer, gateway, sessions, repo, events, settings);
        orchestrator = new QuoteOrchestrator(pricing, portal, new PortalRateLimiter(), reference, repo, writer, gateway,
                sessions, events, settings, fx, scheduler, sender);
        handler = new RfqHandler(sessions, repo, reference, orchestrator, new BidCollector(repo, events, fx, orchestrator),
                writer, events);

        when(writer.write(any())).thenAnswer(inv -> politeWriter(inv.getArgument(0)));
        when(pricing.priceFromSheets(any())).thenReturn(List.of(
                new PriceQuote("sheet", "HX", "Helix Air Freight", new BigDecimal("1500.00"), "EUR", new BigDecimal("1500.00"), 4, null, Map.of())));
        when(portal.brackets(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void fansOutInParallelAndQuotesTheBestOptionOnceEveryoneAnswered() {
        portalLane("AA", "BB", "CC");
        // Each rate call blocks until all three are in flight: sequential calls would time out here.
        CountDownLatch inFlight = new CountDownLatch(3);
        AtomicBoolean parallel = new AtomicBoolean(true);
        when(portal.rate(any())).thenAnswer(inv -> {
            inFlight.countDown();
            if (!inFlight.await(5, TimeUnit.SECONDS)) parallel.set(false);
            RateQuery q = inv.getArgument(0);
            return switch (q.airline()) {
                case "AA" -> ok("AA", "1400.00", "EUR", null, 5);
                case "BB" -> ok("BB", "1300.00", "USD", "0.90", 6);
                default -> ok("CC", "1600.00", "EUR", null, 2);
            };
        });

        StoredMail rfq = newRfq("cheapest", false);

        assertThat(parallel).isTrue();
        assertThat(gateway.sentTo(AURELIA)).hasSize(1);
        assertThat(gateway.sentTo(BOREALIS)).hasSize(1);
        assertThat(gateway.sentTo(RATEDESK)).isEmpty();
        assertThat(gateway.sentTo(CUSTOMER)).isEmpty(); // still waiting for the two bids
        assertThat(sessions.status).isEqualTo(SessionStatus.WAITING_FOR_BIDS);
        assertThat(repo.openBidRequests(sessionId)).hasSize(2);
        assertThat(repo.findRfq(sessionId)).hasValueSatisfying(r -> {
            assertThat(r.originAirport()).isEqualTo("FRA"); // resolved from postcode 64289
            assertThat(r.chargeableKg()).isEqualByComparingTo("334.00"); // 2 cbm x 167 beats 250 kg
        });

        reply(AURELIA, bid("1000", 7));
        assertThat(gateway.sentTo(CUSTOMER)).isEmpty();
        reply(BOREALIS, bid("1100", 3)); // most balanced bid: 0.5*1.1 + 0.5*1 = 1.05 vs Aurelia 1.667

        List<OutgoingMail> quotes = gateway.sentTo(CUSTOMER);
        assertThat(quotes).hasSize(1);
        assertThat(quotes.getFirst().inReplyToExt()).isEqualTo(rfq.externalId());
        assertThat(quotes.getFirst().body()).contains("EUR 1,100.00");
        assertThat(sessions.status).isEqualTo(SessionStatus.QUOTED);
        assertThat(eventLog.count("quote.sent")).isEqualTo(1);
        assertThat(eventLog.count("pricing.decision")).isEqualTo(1);

        List<Candidate> candidates = repo.candidates(sessionId);
        assertThat(candidates).hasSize(6); // sheet + 3 portal + 2 bids
        assertThat(candidates).filteredOn(c -> c.source() == CandidateSource.portal && "BB".equals(c.airlineCode()))
                .singleElement().satisfies(c -> assertThat(c.totalEur()).isEqualByComparingTo("1170.00"));
        assertThat(chosen()).isEqualTo("Borealis Freight Airways");
        assertThat(jdbc.sql("SELECT count(*) FROM price_candidate WHERE NOT chosen AND reject_reason IS NULL").query(Long.class).single())
                .isZero();
    }

    @Test
    void decidesExactlyOnceUnderConcurrentTriggers() throws Exception {
        noPortalLane();
        newRfq("cheapest", false); // two bids open, so nothing is decided yet

        List<Future<Boolean>> results = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 12; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return orchestrator.decide(sessionId, "test");
                }));
            }
            go.countDown();
        }

        long winners = results.stream().filter(f -> f.resultNow()).count();
        assertThat(winners).isEqualTo(1);
        assertThat(gateway.sentTo(CUSTOMER)).hasSize(1);
        assertThat(eventLog.count("bid.timeout")).isEqualTo(2);
        reply(AURELIA, bid("900", 2)); // a late bid is recorded but never triggers a second quote
        assertThat(gateway.sentTo(CUSTOMER)).hasSize(1);
        assertThat(eventLog.count("pricing.decision")).isEqualTo(1);
    }

    @Test
    void doesNotSendWhenTheThreadAlreadyHasAQuote() {
        noPortalLane();
        settingsStore.values.put("bidsEnabled", false);
        sessions.quotedAt = OffsetDateTime.now(); // e.g. a human answered first

        newRfq("cheapest", false);

        assertThat(sessions.claims.get()).isEqualTo(1);
        assertThat(gateway.sentTo(CUSTOMER)).isEmpty();
        assertThat(eventLog.count("quote.skipped")).isEqualTo(1);
    }

    @Test
    void releasesTheClaimWhenSendingFails() {
        noPortalLane();
        settingsStore.values.put("bidsEnabled", false);
        gateway.failWhen = m -> m.to().equals(CUSTOMER);

        newRfq("cheapest", false);

        assertThat(sessions.releases.get()).isEqualTo(1);
        assertThat(sessions.quotedAt).isNull();
        assertThat(sessions.needsHuman).isTrue();
        assertThat(eventLog.count("quote.failed")).isEqualTo(1);
    }

    @Test
    void asksTheRateDeskWhenNeitherPortalNorSheetCoverTheLane() {
        noPortalLane();
        when(pricing.priceFromSheets(any())).thenReturn(List.of());
        settingsStore.values.put("bidPolicy", "missing_only");

        newRfq("fastest", false);

        assertThat(gateway.sentTo(RATEDESK)).hasSize(1);
        assertThat(repo.openBidRequests(sessionId)).extracting(RfqRepository.BidRequestRow::kind)
                .containsExactlyInAnyOrder("ratedesk", "airline", "airline");

        reply(RATEDESK, new Extraction.Bid(new BigDecimal("2000"), "EUR", 2, null, "Kestrel Cargo", false, null));
        reply(AURELIA, bid("1800", 4));
        reply(BOREALIS, new Extraction.Bid(null, null, null, null, null, true, "no capacity"));

        assertThat(gateway.sentTo(CUSTOMER)).singleElement().satisfies(m -> assertThat(m.body()).contains("EUR 2,000.00"));
        assertThat(chosen()).isEqualTo("Kestrel Cargo");
    }

    @Test
    void doorPickupAddsTheCheapestTruckerToTheAirPrice() {
        noPortalLane();

        newRfq("cheapest", true);

        assertThat(gateway.sentTo(TRUCKER)).hasSize(1);
        // both airlines decline: the sheet price (EUR 1,500) plus the trucker is the only door-to-port option
        reply(AURELIA, new Extraction.Bid(null, null, null, null, null, true, null));
        reply(BOREALIS, new Extraction.Bid(null, null, null, null, null, true, null));
        reply(TRUCKER, new Extraction.Bid(new BigDecimal("180"), "EUR", null, null, null, false, null));

        assertThat(gateway.sentTo(CUSTOMER)).singleElement().satisfies(m -> assertThat(m.body()).contains("EUR 1,680.00"));
        assertThat(repo.candidates(sessionId)).filteredOn(Candidate::source, CandidateSource.combined)
                .singleElement().satisfies(c -> assertThat(c.breakdown()).containsEntry("trucker", "Rheinufer Spedition"));
    }

    @Test
    void quoteBodyStatesExactlyOneTotalEvenWhenTheWriterAddsAnother() {
        noPortalLane();
        settingsStore.values.put("bidsEnabled", false);
        doAnswer(inv -> {
            MailIntent intent = inv.getArgument(0);
            return new WrittenMail("Re: RFQ", "We can do " + intent.mustContain().getFirst()
                    + ", or EUR 1,350.00 if you can wait a week.", false);
        }).when(writer).write(any());

        newRfq("cheapest", false);

        String body = gateway.sentTo(CUSTOMER).getFirst().body();
        assertThat(Money.statesExactlyOneTotal(body, "EUR 1,500.00")).isTrue();
        assertThat(Money.countAmounts(body)).isEqualTo(1);
        assertThat(eventLog.count("quote.template")).isEqualTo(1);
    }

    @Test
    void autopilotOffByDefaultLeavesADraftThatApprovalSendsThroughTheSameClaim() {
        noPortalLane();
        settingsStore.values.put("bidsEnabled", false);
        settingsStore.values.remove("autopilot"); // the global switch defaults to off

        newRfq("cheapest", false);

        assertThat(gateway.sentTo(CUSTOMER)).isEmpty();
        assertThat(sessions.needsHuman).isTrue();
        assertThat(repo.findDraft(sessionId)).hasValueSatisfying(d -> {
            assertThat(d.purpose()).isEqualTo("quote");
            assertThat(d.meta()).containsEntry("total", "1500.00").containsEntry("currency", "EUR");
        });

        assertThat(sender.sendDraft(sessionId, null)).isEqualTo(QuoteSender.Outcome.SENT);
        assertThat(sender.sendDraft(sessionId, null)).isEqualTo(QuoteSender.Outcome.NO_DRAFT);
        assertThat(gateway.sentTo(CUSTOMER)).hasSize(1);
        assertThat(sessions.quotedTotal).isEqualByComparingTo("1500.00");
        assertThat(sessions.claims.get()).isEqualTo(1);
    }

    @Test
    void missingWeightGoesToAHumanWithADraftInsteadOfAQuestion() {
        noPortalLane();
        StoredMail mail = gateway.store(sessionId, "in", CUSTOMER, "aeiou@hackloop.mail", "RFQ", "FRA to SIN please", null);
        handler.handle(new RoutedMail(mail, MailType.NEW_RFQ, 0.9, 2, sessionId, extraction(
                new Extraction.Rfq(null, null, "FRA", "SIN", null, null, null, "cheapest", false, null, null, null))));

        assertThat(gateway.sent).isEmpty();
        assertThat(sessions.needsHuman).isTrue();
        assertThat(sessions.needsHumanReason).contains("weight");
        assertThat(repo.findDraft(sessionId)).hasValueSatisfying(d -> assertThat(d.purpose()).isEqualTo("other"));
    }

    @Test
    void followUpAfterTheQuoteIsHandedToAHuman() {
        noPortalLane();
        settingsStore.values.put("bidsEnabled", false);
        StoredMail rfq = newRfq("cheapest", false);
        assertThat(gateway.sentTo(CUSTOMER)).hasSize(1);

        StoredMail followUp = gateway.store(sessionId, "in", CUSTOMER, "aeiou@hackloop.mail", "Re: RFQ",
                "Could you do it cheaper?", rfq.externalId());
        handler.handle(new RoutedMail(followUp, MailType.FOLLOW_UP, 0.9, 2, sessionId, null));

        assertThat(gateway.sentTo(CUSTOMER)).hasSize(1);
        assertThat(sessions.needsHumanReason).startsWith("Follow-up after quote: Could you do it cheaper?");
    }

    // helpers ----------------------------------------------------------------------------------------------------

    private StoredMail newRfq(String mode, boolean doorPickup) {
        StoredMail mail = gateway.store(sessionId, "in", CUSTOMER, "aeiou@hackloop.mail", "RFQ Darmstadt - Singapore",
                "250 kg, 2 cbm, " + mode, null);
        handler.handle(new RoutedMail(mail, MailType.NEW_RFQ, 0.95, 2, sessionId, extraction(
                new Extraction.Rfq("64289", "Darmstadt", null, "SIN", new BigDecimal("250"), new BigDecimal("2"), 3, mode,
                        doorPickup, "2026-11-23", "machine parts", null))));
        return mail;
    }

    private void reply(String from, Extraction.Bid bid) {
        Long inReplyTo = jdbc.sql("SELECT external_id FROM mail WHERE to_addr = ? AND direction = 'out' ORDER BY id LIMIT 1")
                .param(from).query(Long.class).optional().orElse(null);
        StoredMail mail = gateway.store(sessionId, "in", from, "aeiou@hackloop.mail", "Re: rate request", "our offer", inReplyTo);
        MailType type = from.equals(RATEDESK) ? MailType.RATEDESK_REPLY : MailType.CARRIER_BID;
        handler.handle(new RoutedMail(mail, type, 1.0, 1, sessionId,
                new Extraction(type, 1.0, null, "en", null, null, bid, null)));
    }

    private static Extraction.Bid bid(String price, int transitDays) {
        return new Extraction.Bid(new BigDecimal(price), "EUR", transitDays, "2026-12-01", null, false, null);
    }

    private static Extraction extraction(Extraction.Rfq rfq) {
        return new Extraction(MailType.NEW_RFQ, 0.95, null, "en", "Anna", rfq, null, null);
    }

    private void portalLane(String... codes) {
        when(portal.lanes()).thenReturn(List.of(new Lane("FRA", "SIN",
                java.util.Arrays.stream(codes).map(c -> new Airline(c, "Airline " + c)).toList())));
    }

    private void noPortalLane() {
        when(portal.lanes()).thenReturn(List.of(new Lane("FRA", "JFK", List.of(new Airline("CO", "Cobalt Skylift")))));
    }

    private static RateResult ok(String code, String total, String currency, String fx, int transit) {
        Rate rate = new Rate("rate-" + code, "Airline " + code, code, new BigDecimal(total), currency,
                fx == null ? null : new BigDecimal(fx), new BigDecimal("334"), transit, OffsetDateTime.now().plusMinutes(15), "v1");
        return new RateResult(200, rate, 30, 20, null, null);
    }

    private String chosen() {
        return jdbc.sql("SELECT airline_name FROM price_candidate WHERE session_id = ? AND chosen")
                .param(sessionId).query(String.class).single();
    }

    private static WrittenMail politeWriter(MailIntent intent) {
        Function<Object, String> f = o -> o == null ? "" : o.toString();
        if (intent.purpose() == Purpose.QUOTE) {
            return new WrittenMail(null, "Hi " + f.apply(intent.recipientName()) + ",\n\nwe can fly it for "
                    + intent.mustContain().getFirst() + " all in.\n\nBest, Lena", false);
        }
        return new WrittenMail("Rate request", "Could you quote " + intent.facts(), false);
    }
}
