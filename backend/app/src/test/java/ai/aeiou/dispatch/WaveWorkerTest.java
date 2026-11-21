package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.aeiou.freight.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

class WaveWorkerTest {
  static final Instant START = Instant.parse("2026-11-21T15:00:00Z");

  static class TestClock extends Clock {
    Instant current = START;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return current;
    }
  }

  @TempDir Path temp;
  final TestClock clock = new TestClock();
  final ObjectMapper mapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  final Catalog catalog = new DispatchApplication().catalog();
  final FiveU upstream = mock(FiveU.class);
  final List<Map<String, Object>> outbound = new ArrayList<>();
  final AtomicInteger next = new AtomicInteger(1000);
  Ledger ledger;
  WaveWorker worker;

  void restart() throws Exception {
    restart(false);
  }

  void restart(boolean counteroffers) throws Exception {
    ledger = new Ledger(temp.resolve("test.sqlite").toString(), mapper);
    worker =
        new WaveWorker(
            ledger,
            catalog,
            upstream,
            clock,
            new RfqGateway(upstream, ledger, catalog, mapper, "gpt-4o-mini"),
            true,
            START,
            75,
            counteroffers);
  }

  @BeforeEach
  void setup() throws Exception {
    restart();
    when(upstream.parseRfq(anyMap()))
        .thenAnswer(
            call -> {
              Map<String, Object> payload = call.getArgument(0);
              boolean pickup = payload.toString().contains("60311");
              var fields =
                  mapper
                      .createObjectNode()
                      .put("origin", "FRA")
                      .put("destination", "LAX")
                      .put("weight_kg", 100)
                      .put("volume_cbm", 0.2)
                      .put("preference", "cheapest")
                      .put("pickup_required", pickup)
                      .put("needs_review", false);
              if (pickup) fields.put("pickup_zone", "60");
              else fields.putNull("pickup_zone");
              return mapper.valueToTree(
                  Map.of(
                      "choices",
                      List.of(
                          Map.of(
                              "finish_reason",
                              "stop",
                              "message",
                              Map.of("content", fields.toString())))));
            });
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(), true));
    when(upstream.post(eq("/v1/mailbox/messages"), anyMap()))
        .thenAnswer(
            call -> {
              Map<String, Object> msg = call.getArgument(1);
              var receipt = new HashMap<>(msg);
              receipt.put("id", next.incrementAndGet());
              outbound.add(receipt);
              return receipt;
            });
  }

  void receive(long id, String from, String subject, String body, Long parent) {
    var mail =
        new HashMap<String, Object>(
            Map.of(
                "id",
                id,
                "from",
                from,
                "subject",
                subject,
                "body",
                body,
                "sent_at",
                START.toString()));
    if (parent != null) mail.put("in_reply_to", parent);
    ledger.ingest(Map.of("messages", List.of(mail), "next_since_id", id), catalog, clock.instant());
  }

  void request(String extra) {
    receive(
        7,
        "buyer@hackloop.mail",
        "RFQ FRA to LAX",
        "Please quote the cheapest service for 100 kg and 0.2 cbm. " + extra,
        null);
  }

  Map<String, Object> startCounterofferRound() throws Exception {
    restart(true);
    request("");
    worker.work();
    var invitation = outbound.getFirst();
    receive(
        8,
        invitation.get("to").toString(),
        "Carrier response",
        "All-in EUR 70, 2 days.",
        Ledger.id(invitation.get("id")));
    clock.current = START.plusSeconds(75);
    worker.work();
    var counter = outbound.getLast();
    assertEquals(8L, counter.get("in_reply_to"));
    assertTrue(counter.get("body").toString().contains("EUR 66.50 all in"));
    assertNull(ledger.delivery("quote:7"));
    return counter;
  }

  @Test
  void rechecksStoredFastestRequestWithParenthesizedCityWithoutPayingForAnotherParse() {
    receive(
        419,
        "buyer@example.invalid",
        "Air freight quote request to JFK",
        "Could you please quote the fastest air freight option to JFK? The shipment will be ready"
            + " for pickup at our site in 61018 (Bad Homburg), Germany. 1233 kg gross and 10.78"
            + " cbm.",
        null);
    ledger
        .jdbc()
        .update(
            "INSERT INTO rfq_parse(request_id,state,model,result) VALUES(419,'done','test',?)",
            """
            {"origin":null,"destination":"JFK","weight_kg":1233,"volume_cbm":10.78,"preference":null,
            "pickup_required":true,"pickup_zone":"18","needs_review":false}
            """);
    ledger.stage(
        419, "review", "Gateway extraction conflicts with shipment details. Confirm the fields.");
    worker.recheckOpenExtractions();
    worker.work();
    var trucks =
        outbound.stream().filter(m -> m.get("subject").toString().contains("pickup")).toList();
    assertEquals(2, trucks.size());
    for (var truck : trucks) {
      assertTrue(truck.get("subject").toString().contains("postcode 61018"));
      assertTrue(truck.get("body").toString().contains("postcode 61018"));
    }
    verify(upstream, never()).parseRfq(anyMap());
  }

  @Test
  void senderDeliveringToTheAirportCanProceedWithStringNullPickupZone() {
    receive(
        729,
        "buyer@example.invalid",
        "Air freight quote: DUS to JFK",
        "Please quote the fastest air freight option from DUS to JFK. We will deliver it to DUS"
            + " ourselves. It is 1 piece, 1540 kg gross and 5.90 cbm.",
        null);
    ledger
        .jdbc()
        .update(
            "INSERT INTO rfq_parse(request_id,state,model,result) VALUES(729,'done','test',?)",
            """
            {"origin":"DUS","destination":"JFK","weight_kg":1540,"volume_cbm":5.9,"preference":"fastest",
            "pickup_required":false,"pickup_zone":"null","needs_review":false}
            """);
    ledger.stage(729, "review", "Confirm the pickup requirement and postcode.");
    worker.recheckOpenExtractions();
    worker.work();
    assertEquals(5, outbound.size());
    assertTrue(outbound.stream().allMatch(m -> m.get("subject").toString().contains("DUS to JFK")));
    verify(upstream, never()).parseRfq(anyMap());
  }

  @Test
  void findsUnlabelledGermanAddressButDoesNotGuessBetweenTwoPickupSites() {
    assertEquals(
        Optional.of("51447"),
        MailParser.pickupPostcode(
            "Please arrange trucking from our site in 51447 Bergisch Gladbach, Germany, to the"
                + " departure airport.",
            "51"));
    assertTrue(MailParser.pickupPostcode("51447 Bergisch Gladbach or 51103 Köln", "51").isEmpty());
    assertTrue(
        MailParser.pickupPostcode("Reference 51447. Gross weight 51447 kg.", "51").isEmpty());
  }

  @Test
  void acceptsAnImprovedCounterofferAndQuotesOnceAcrossRestart() throws Exception {
    var counter = startCounterofferRound();
    restart(true);
    clock.current = START.plusSeconds(82);
    worker.work();
    assertEquals(counter, outbound.getLast());
    long parent = Ledger.id(counter.get("id"));
    receive(
        9,
        "uninvited@hackloop.mail",
        "Re: AEIOU-7 | counteroffer",
        "Revised all-in rate: EUR 1. Transit: 2 days.",
        parent);
    worker.work();
    assertNull(ledger.delivery("quote:7"));
    receive(
        10,
        counter.get("to").toString(),
        "Re: Carrier response",
        "Revised all-in rate: EUR 64. Transit: 2 days.",
        parent);
    worker.work();
    worker.work();
    assertEquals(7, outbound.size());
    assertEquals(7L, outbound.getLast().get("in_reply_to"));
    assertTrue(outbound.getLast().get("body").toString().contains("EUR 64.00"));
  }

  @Test
  void keepsTheOriginalBidWhenCounterofferTimesOut() throws Exception {
    var counter = startCounterofferRound();
    clock.current = START.plusSeconds(95);
    receive(
        9,
        counter.get("to").toString(),
        "Re: Carrier response",
        "Revised all-in rate: EUR 64. Transit: 2 days.",
        Ledger.id(counter.get("id")));
    worker.work();
    assertEquals(7L, outbound.getLast().get("in_reply_to"));
    assertTrue(outbound.getLast().get("body").toString().contains("EUR 70.00"));
  }

  @Test
  void keepsTheOriginalBidWhenTheRevisedRateIsSlower() throws Exception {
    var counter = startCounterofferRound();
    clock.current = START.plusSeconds(76);
    receive(
        9,
        counter.get("to").toString(),
        "Re: Carrier response",
        "Revised all-in rate: EUR 64. Transit: 3 days.",
        Ledger.id(counter.get("id")));
    worker.work();
    assertTrue(outbound.getLast().get("body").toString().contains("EUR 70.00"));
  }

  @Test
  void doorToPortRecoversAcrossRestartAndSendsOnce() throws Exception {
    request("Pickup from postcode 60311.");
    worker.work();
    assertEquals(5, outbound.size());
    var invitations = List.copyOf(outbound);
    var airline =
        invitations.stream()
            .filter(m -> !catalog.contact((String) m.get("to")).orElseThrow().truck())
            .findFirst()
            .orElseThrow();
    receive(
        8,
        (String) airline.get("to"),
        "Re: rate request",
        "All-in EUR 70.00, transit 2 days.",
        Ledger.id(airline.get("id")));
    var trucks =
        invitations.stream()
            .filter(m -> catalog.contact((String) m.get("to")).orElseThrow().truck())
            .toList();
    receive(9, "uninvited@hackloop.mail", "AEIOU-7", "EUR 1, same day", null);
    clock.current = START.plusSeconds(80);
    worker.work();
    assertEquals("waiting for pickup", ledger.mail(7).get("stage"));
    restart();
    receive(
        10,
        (String) trucks.getFirst().get("to"),
        "Re: AEIOU-7",
        "Pickup flat EUR 25.00, transit 1 day.",
        null);
    worker.work();
    worker.work();
    restart();
    worker.work();
    assertEquals(6, outbound.size());
    assertEquals(7L, outbound.getLast().get("in_reply_to"));
    assertEquals("buyer@hackloop.mail", outbound.getLast().get("to"));
    assertTrue(outbound.getLast().get("body").toString().contains("EUR 95.00"));
    assertTrue(outbound.getLast().get("body").toString().contains("3 days"));
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals(10, ledger.cursor());
    verify(upstream, times(1)).parseRfq(anyMap());
  }

  @Test
  void conversationKeepsConfirmedMailAcrossRestartAndFollowsReplyParents() throws Exception {
    request("");
    worker.work();
    var invitation = outbound.getFirst();
    receive(
        18,
        invitation.get("to").toString(),
        "Carrier response",
        "All-in EUR 70, 2 days.",
        Ledger.id(invitation.get("id")));
    clock.current = START.plusSeconds(80);
    worker.work();
    var quote = outbound.getLast();
    receive(
        19, "buyer@hackloop.mail", "Re: RFQ", "Thanks, please book.", Ledger.id(quote.get("id")));
    var reply = worker.reply(19, "We received your booking request.");
    receive(20, "buyer@hackloop.mail", "Re: RFQ", "Thank you.", Ledger.id(reply.get("id")));
    receive(21, "another@hackloop.mail", "Unrelated RFQ", "New request", null);
    ledger.jdbc().update("UPDATE mail SET proposal=NULL WHERE id=7");
    restart();

    var inbox = ledger.inbox();
    assertEquals(
        List.of(20L, 19L, 18L, 7L),
        inbox.stream()
            .filter(m -> Ledger.id(m.get("thread_id")) == 7)
            .map(m -> Ledger.id(m.get("id")))
            .toList());
    var original = inbox.getLast();
    var events = mapper.valueToTree(original.get("outgoing"));
    assertEquals(outbound.size() - 1, events.size());
    var sent = events.get(events.size() - 1);
    assertEquals("sent", sent.path("state").asText());
    assertEquals("quote", sent.path("kind").asText());
    assertEquals("buyer@hackloop.mail", sent.path("recipient").asText());
    assertEquals(quote.get("body"), sent.path("body").asText());
    assertEquals(quote.get("subject"), sent.path("subject").asText());
    assertEquals(quote.get("id"), sent.path("message_id").asInt());
  }

  @Test
  void uncertainQuoteIsNeverReplayed() throws Exception {
    request("");
    worker.work();
    assertEquals(5, outbound.size());
    when(upstream.post(eq("/v1/mailbox/messages"), anyMap()))
        .thenThrow(new ResourceAccessException("lost receipt"));
    clock.current = START.plusSeconds(76);
    worker.work();
    restart();
    worker.work();
    var uncertain = mapper.valueToTree(ledger.inbox().getFirst().get("outgoing"));
    assertEquals("uncertain", uncertain.get(uncertain.size() - 1).path("state").asText());
    assertTrue(uncertain.get(uncertain.size() - 1).path("message_id").isNull());
    assertEquals("uncertain", ledger.delivery("quote:7"));
    assertEquals("review", ledger.mail(7).get("stage"));
    verify(upstream, times(6)).post(eq("/v1/mailbox/messages"), anyMap());
  }

  @Test
  void failClosedForUncertainOrExpiredRequests() {
    receive(
        7,
        "buyer@hackloop.mail",
        "RFQ FRA to LAX",
        "Cheapest for 100 kg, 0.2 cbm. Pickup needed.",
        null);
    worker.work();
    assertEquals("review", ledger.mail(7).get("stage"));
    receive(8, "buyer@hackloop.mail", "RFQ FRA to LAX", "Cheapest for 100 kg, 0.2 cbm.", null);
    clock.current = START.plusSeconds(361);
    worker.work();
    assertEquals("expired", ledger.mail(8).get("stage"));
    assertTrue(outbound.isEmpty());
  }

  @Test
  void ambiguousBidsAndUnpricedLegsStayOut() {
    assertTrue(MailParser.bid("EUR 2.50 per kg, 2 days", false).isEmpty());
    assertTrue(MailParser.bid("USD 100, 2 days", false).isEmpty());
    assertTrue(MailParser.bid("EUR 15 plus fuel, 1 day", true).isEmpty());
    assertTrue(MailParser.bid("All-in EUR 150.00, 2–5 days", false).isEmpty());
    assertTrue(MailParser.bid("EUR 15.00 and EUR 20.00, 1 day", true).isEmpty());
    assertNull(MailParser.bid("Pickup all-in EUR 25.", true).orElseThrow().days());
    assertNull(
        MailParser.bid("Pickup all-in EUR 25, valid for 16 hours.", true).orElseThrow().days());
    assertNull(
        MailParser.bid("All-in EUR 25. Please reply in 12 hours.", true).orElseThrow().days());
    assertTrue(MailParser.bid("EUR 25", false).isEmpty());
    assertEquals(158, catalog.size());
  }

  @Test
  void manualPricingAndSendingReuseTheOriginalExtraction() throws Exception {
    request("");
    var s = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    worker.price(7, s, null);
    restart();
    var draft = worker.price(7, s, null);
    worker.sendQuote(7, draft.revision());
    verify(upstream, times(1)).parseRfq(anyMap());
    assertEquals("sent", ledger.delivery("quote:7"));
  }

  @Test
  void missingGatewayAcknowledgmentHoldsQuoteAcrossRestart() throws Exception {
    request("");
    when(upstream.parseRfq(anyMap())).thenThrow(new ResourceAccessException("timeout"));
    worker.work();
    assertEquals("review", ledger.mail(7).get("stage"));
    assertTrue(outbound.isEmpty());
    restart();
    var s = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    assertThrows(WaveWorker.Conflict.class, () -> worker.price(7, s, null));
    verify(upstream, times(1)).parseRfq(anyMap());
  }

  @Test
  void waitingUntilWaveStartDoesNotSendEarly() {
    request("");
    clock.current = START.minusSeconds(1);
    worker.work();
    assertTrue(outbound.isEmpty());
    clock.current = START;
    worker.work();
    assertEquals(5, outbound.size());
  }
}
