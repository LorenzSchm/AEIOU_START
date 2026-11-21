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
    verify(upstream, never()).parseRfq(anyMap());
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
  void explicitCustomerApprovalBooksOnceAndTrackingUsesTheCarrierConfirmation() throws Exception {
    request("");
    var offer =
        new Offer(
            "Fjordline Air Cargo",
            2,
            new java.math.BigDecimal("50.00"),
            new java.math.BigDecimal("100"),
            "portal",
            "R-TEST",
            START.plusSeconds(900));
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(offer), true));
    worker.work();
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    var bookings = new PortalBookings(ledger, upstream, clock, true);
    var confirmation =
        Map.<String, Object>of(
            "booking_reference",
            "BK-SMMUWG2S",
            "rate_id",
            "R-TEST",
            "team_reference",
            "AEIOU-7",
            "status",
            "booked",
            "airline",
            "Fjordline Air Cargo",
            "price",
            "50.00",
            "currency",
            "EUR");
    when(upstream.post(eq("/v1/portal/bookings"), anyMap())).thenReturn(confirmation);
    receive(2001, "stranger@hackloop.mail", "Re: RFQ", "Please book it.", 7L);
    receive(
        2002,
        "buyer@hackloop.mail",
        "Re: RFQ",
        "Your price is too high, so we will not go ahead.",
        7L);
    bookings.bookAccepted();
    verify(upstream, never()).post(eq("/v1/portal/bookings"), anyMap());
    receive(
        2003,
        "buyer@hackloop.mail",
        "Re: RFQ",
        "Thank you for your offer. That works for us, please go ahead and book it.\n\n"
            + "Thanks a lot and have a lovely day!\n"
            + "Petra Hofmann\n"
            + "Export Desk, Hofmann Textilwerk KG",
        7L);
    bookings.bookAccepted();
    restart();
    new PortalBookings(ledger, upstream, clock, true).bookAccepted();
    verify(upstream, times(1))
        .post("/v1/portal/bookings", Map.of("team_reference", "AEIOU-7", "rate_id", "R-TEST"));
    receive(
        2004,
        "fjordline-air-cargo@hackloop.mail",
        "Booking BK-SMMUWG2S confirmed",
        "Booking reference: BK-SMMUWG2S\nStatus: booked and ready to ship",
        null);
    receive(
        2005,
        "tracking@hackloop.mail",
        "Where is BK-SMMUWG2S?",
        "Which airline has our shipment?",
        null);
    receive(2006, "tracking@hackloop.mail", "Where is BK-UNKNOWN?", "Please check.", null);
    var tracking = new TrackingReplies(ledger, worker, catalog, true);
    tracking.answer();
    tracking.answer();
    var replies =
        outbound.stream().filter(m -> "tracking@hackloop.mail".equals(m.get("to"))).toList();
    assertEquals(1, replies.size());
    var reply = replies.getFirst();
    assertEquals(2005L, ((Number) reply.get("in_reply_to")).longValue());
    String text = reply.get("body").toString();
    for (String expected :
        List.of("Fjordline Air Cargo", "FRA to LAX", "BK-SMMUWG2S", "booked and ready to ship"))
      assertTrue(text.contains(expected));
    assertEquals("tracking review", ledger.mail(2006).get("stage"));
  }

  @Test
  void conditionalOrQuotedApprovalDoesNotBook() {
    assertTrue(
        PortalBookings.accepted(
            "Thank you for your offer. That works for us, please go ahead and book it.\n\n"
                + "Best greetings from Regensburg\n"
                + "Tobias Auer"));
    assertTrue(
        PortalBookings.accepted(
            "Thank you for your offer. That works for us, please go ahead and book it.\n\n"
                + "Viele Gruesse / Many greetings\n"
                + "Deniz Yilmaz\n"
                + "Rheintal Elektronik GmbH"));
    assertFalse(
        PortalBookings.accepted(
            "Please book it.\n\nBest regards,\nSara\nPS: Please hold until Monday."));
    assertTrue(
        PortalBookings.accepted(
            "Thank you for your offer. That works for us, please go ahead and book it.\n\n"
                + "Cheers,\n"
                + "Klaus Weidner\n"
                + "Weidner Spielwaren GmbH, Nuremberg"));
    assertFalse(PortalBookings.accepted("Please book it if the price falls."));
    assertFalse(PortalBookings.accepted("Please book it.\n\nOnly if the dates change."));
    assertFalse(PortalBookings.accepted("Can you book it?"));
    assertFalse(PortalBookings.accepted("Please hold.\n\n> Please book it."));
  }

  @Test
  void manualPricingAndSendingReuseTheOriginalExtraction() throws Exception {
    request("");
    var s = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    worker.price(7, s, null);
    restart();
    var draft = worker.price(7, s, null);
    worker.sendQuote(7, draft.revision());
    verify(upstream, never()).parseRfq(anyMap());
    assertEquals("sent", ledger.delivery("quote:7"));
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "Recorded wave {0}")
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4, 5, 6, 9})
  void replaysLiveRfqThroughAutomaticDispatch(int round) throws Exception {
    var fixture =
        round == 9
            ? mapper.readTree(getClass().getResource("/round-nine-rfq.json"))
            : mapper.readTree(getClass().getResource("/six-wave-rfqs.json")).get(round - 1);
    var offers = mapper.treeToValue(fixture.get("offers"), Offer[].class);
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(offers), true));
    receive(
        7,
        "buyer@example.invalid",
        fixture.get("subject").asText(),
        fixture.get("body").asText(),
        null);
    worker.work();
    var shipment = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    var expected = mapper.treeToValue(fixture.get("shipment"), Shipment.class);
    assertEquals(expected.origin(), shipment.origin());
    assertEquals(expected.destination(), shipment.destination());
    assertEquals(expected.preference(), shipment.preference());
    assertEquals(expected.pickupZone(), shipment.pickupZone());
    assertEquals(0, expected.weightKg().compareTo(shipment.weightKg()));
    assertEquals(0, expected.volumeCbm().compareTo(shipment.volumeCbm()));
    if (shipment.pickupZone() != null) {
      assertNull(ledger.delivery("quote:7"));
      long replyId = 10;
      for (var invite : List.copyOf(outbound)) {
        var address = invite.get("to").toString();
        var reply = fixture.get("replies").required(address);
        receive(
            replyId++,
            address,
            "Re: " + invite.get("subject"),
            reply.asText(),
            Ledger.id(invite.get("id")));
      }
      worker.work();
    } else {
      assertEquals(1, outbound.size());
    }
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals(
        1, outbound.stream().filter(m -> "buyer@example.invalid".equals(m.get("to"))).count());
    var quote = ledger.read(ledger.mail(7).get("proposal"), WaveWorker.Draft.class);
    assertEquals(0, fixture.get("euros").decimalValue().compareTo(quote.offer().totalEur()));
    if (round == 9) {
      assertEquals("429.80", quote.pickupEur().toPlainString());
      assertEquals("1952", quote.offer().billedKg().toPlainString());
      assertTrue(quote.body().contains("36627"));
      var bookings = new PortalBookings(ledger, upstream, clock, true);
      var receipt =
          Map.of(
              "booking_reference",
              "BK-R9-TEST",
              "team_reference",
              "AEIOU-7",
              "rate_id",
              quote.offer().rateId());
      when(upstream.post(eq("/v1/portal/bookings"), anyMap())).thenReturn(new HashMap<>(receipt));
      receive(
          2005,
          "buyer@example.invalid",
          "Re: " + fixture.get("subject").asText(),
          "Thank you for your offer. That works for us, please go ahead and book it.\n\n"
              + "Mira Petersen\n"
              + "Nordlicht Audio",
          Ledger.id(outbound.getLast().get("id")));
      bookings.bookAccepted();
      bookings.bookAccepted();
      verify(upstream, times(1))
          .post("/v1/portal/bookings", Map.of("rate_id", "TEST-R9-0", "team_reference", "AEIOU-7"));
    }
    verify(upstream, never()).parseRfq(anyMap());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false, R-TEST, 900, 50",
    "true, '', 900, 50",
    "true, R-TEST, 50, 50",
    "true, R-TEST, 900, 999999"
  })
  void continuesToBidsWhenPortalWinnerIsUncertain(
      boolean complete, String rateId, int validity, long price) {
    request("");
    var offer =
        new Offer(
            "Portal airline",
            3,
            java.math.BigDecimal.valueOf(price),
            java.math.BigDecimal.valueOf(100),
            "portal",
            rateId,
            START.plusSeconds(validity));
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(offer), complete));
    worker.work();
    assertNull(ledger.delivery("quote:7"));
    assertEquals(5, outbound.size());
  }

  @Test
  void ambiguousUnitsUseOneGatewayCallAcrossPolls() {
    receive(
        7,
        "buyer@example.invalid",
        "RFQ FRA to LAX",
        "Please quote the cheapest service for one tenth of a tonne and 0.2 cbm.",
        null);
    worker.work();
    worker.work();
    assertEquals("collecting", ledger.mail(7).get("stage"));
    verify(upstream, times(1)).parseRfq(anyMap());
  }

  @Test
  void correctlySumsTwoLabelledLotsForSelfDelivery() {
    var parsed =
        MailParser.request(
            "Fastest air freight MUC to LAX",
            "The cargo will be ready for pickup on Saturday, and we will deliver it to Munich"
                + " airport (MUC) ourselves.\n"
                + "Lot 1: 1 piece, gross weight 618 kg, volume 0.74 cbm\n"
                + "Lot 2: 1 piece, gross weight 380 kg, volume 1.65 cbm\n"
                + "Please send both lots together as one consignment.",
            false,
            catalog);
    assertNull(parsed.issue());
    assertNull(parsed.shipment().pickupZone());
    assertEquals("998", parsed.shipment().weightKg().toPlainString());
    assertEquals("2.39", parsed.shipment().volumeCbm().toPlainString());
  }

  @Test
  void roundEightUsesTheCheaperBookablePortalWeightBreakOnTheFirstPass() {
    // Round 8 portal observations. Only the HTTP boundary and rate IDs are fixtures.
    var rateReads = new ArrayList<String>();
    var portal =
        new FiveU("http://localhost", "fixture") {
          @Override
          Map<String, Object> get(String uri, Object... args) {
            if (uri.endsWith("/ports"))
              return Map.of(
                  "lanes",
                  List.of(
                      Map.of(
                          "origin",
                          "MUC",
                          "destination",
                          "CAI",
                          "airlines",
                          List.of(Map.of("code", "AU"), Map.of("code", "BX")))));
            if (uri.contains("/brackets"))
              return Map.of(
                  "breaks_kg",
                  List.of(0, 45, 100, args[0].equals("AU") ? 250 : 300, 500, 1000, 2000));
            assertEquals("MUC", args[0]);
            assertEquals("CAI", args[1]);
            assertEquals("2.62", args[4].toString());
            String key = args[2] + "-" + args[3];
            rateReads.add(key);
            String total =
                Map.of(
                        "AU-486", "1909.98", "BX-486", "1642.68", "AU-500", "1750.00", "BX-500",
                        "1475.00")
                    .get(key);
            return Map.of(
                "currency",
                "EUR",
                "total",
                total,
                "valid_until",
                START.plusSeconds(900).toString(),
                "rate_id",
                key,
                "chargeable_weight_kg",
                args[3],
                "airline",
                args[2].equals("AU") ? "Aurelia Air Cargo" : "Borealis Freight Airways",
                "transit_days",
                args[2].equals("AU") ? 5 : 4);
          }
        };
    when(upstream.rates(any(), any()))
        .thenAnswer(call -> portal.rates(call.getArgument(0), call.getArgument(1)));
    receive(
        7,
        "buyer@example.invalid",
        "Air freight quote: MUC to CAI",
        "Hi,\n\n"
            + "Could you quote the cheapest air freight option for textile samples from MUC to CAI"
            + " (Cairo, EG)? The cargo is ready on 2026-11-24, and we’ll deliver it to MUC"
            + " ourselves.\n\n"
            + "Lot 1: 9 pieces, 345 kg gross, 0.58 cbm\n"
            + "Lot 2: 2 pieces, 141 kg gross, 2.04 cbm\n\n"
            + "Please quote one all-in price in EUR for both lots shipping together as a single"
            + " consignment under one air waybill.\n\n"
            + "Mira Petersen\n"
            + "Nordlicht Audio",
        null);
    worker.work();
    var shipment = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    assertEquals(List.of("AU-486", "BX-486", "AU-500", "BX-500"), rateReads);
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals(1, outbound.size());
    var quote = ledger.read(ledger.mail(7).get("proposal"), WaveWorker.Draft.class);
    assertEquals("BX-500", quote.offer().rateId());
    assertEquals("1475.00", quote.offer().totalEur().toPlainString());
    assertEquals(4.0, quote.offer().days());
    assertTrue(quote.body().contains("486 kg shipment"));
    assertEquals("486", shipment.weightKg().toPlainString());
    assertEquals("2.62", shipment.volumeCbm().toPlainString());
    assertNull(shipment.pickupZone());
    verify(upstream, never()).parseRfq(anyMap());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "ten, xlsx, false",
    "ten, xlsx, true",
    "eleven, pdf, false",
    "eleven, pdf, true"
  })
  void dangerousPackingAttachmentsBlockQuotingEvenOnResume(
      String round, String extension, boolean resumeFirst) throws Exception {
    var fixture = mapper.readTree(getClass().getResource("/round-" + round + "-rfq.json"));
    try (var packing =
        getClass().getResourceAsStream("/round-" + round + "-packing." + extension)) {
      when(upstream.bytes(7, fixture.get("attachments").get(0).get("id").asLong()))
          .thenReturn(packing.readAllBytes());
    }
    ledger.ingest(
        Map.of(
            "messages",
            List.of(
                Map.of(
                    "id",
                    7,
                    "from",
                    "buyer@example.invalid",
                    "subject",
                    fixture.get("subject").asText(),
                    "body",
                    fixture.get("body").asText(),
                    "sent_at",
                    START.toString(),
                    "attachments",
                    mapper.convertValue(fixture.get("attachments"), List.class))),
            "next_since_id",
            7),
        catalog,
        clock.instant());
    var shipment = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    if (resumeFirst) assertThrows(WaveWorker.Conflict.class, () -> worker.resume(7, shipment));
    else worker.work();
    assertEquals("waiting for documents", ledger.mail(7).get("stage"));
    assertEquals("sent", ledger.delivery("dg-docs:7"));
    assertEquals(1, outbound.size());
    var reply = outbound.getFirst();
    assertEquals(7L, reply.get("in_reply_to"));
    assertEquals("buyer@example.invalid", reply.get("to"));
    assertTrue(reply.get("body").toString().contains("Section 14"));
    assertTrue(reply.get("body").toString().contains("dangerous goods declaration"));
    restart();
    assertThrows(WaveWorker.Conflict.class, () -> worker.resume(7, shipment));
    assertThrows(WaveWorker.Conflict.class, () -> worker.price(7, shipment, null));
    assertThrows(WaveWorker.Conflict.class, () -> worker.sendQuote(7, "old-draft"));
    worker.work();
    assertEquals(1, outbound.size());
    assertNull(ledger.delivery("quote:7"));
    verify(upstream, never()).rates(any(), any());
    verify(upstream, never()).parseRfq(anyMap());
  }

  void receiveDocument(long id, Long parent, String filename, String body, String text) {
    var email =
        new HashMap<String, Object>(
            Map.of(
                "id",
                id,
                "from",
                "buyer@example.invalid",
                "subject",
                "RFQ FRA to LAX",
                "body",
                body,
                "sent_at",
                clock.instant().toString(),
                "attachments",
                List.of(Map.of("id", 1, "filename", filename))));
    if (parent != null) email.put("in_reply_to", parent);
    when(upstream.bytes(id, 1)).thenReturn(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ledger.ingest(
        Map.of("messages", List.of(email), "next_since_id", id), catalog, clock.instant());
  }

  @Test
  void cleanPackingListSuppliesMissingTotalsAndQuotesAutomatically() {
    receiveDocument(
        7,
        null,
        "packing.txt",
        "Cheapest air freight from FRA to LAX. Please use the packing list.",
        "Packing List\n"
            + "Destination airport: LAX\n"
            + "Total gross weight (kg): 100\n"
            + "Total volume (cbm): 0.2\n"
            + "Dangerous goods: None");
    when(upstream.rates(any(), any()))
        .thenReturn(
            new FiveU.Rates(
                List.of(
                    new Offer(
                        "Fjordline Air Cargo",
                        3,
                        new java.math.BigDecimal("50"),
                        new java.math.BigDecimal("100"),
                        "portal",
                        "R-DOC",
                        START.plusSeconds(900))),
                true));
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals(1, outbound.size());
    assertTrue(outbound.getFirst().get("body").toString().contains("EUR 50.00"));
    verify(upstream, never()).parseRfq(anyMap());
  }

  @Test
  void automaticallyRequestsAndAppliesCorrectedPackingList() throws Exception {
    receiveDocument(
        7,
        null,
        "packing.txt",
        "Cheapest air freight FRA to LAX for 100 kg and 0.2 cbm.",
        "Packing List\n"
            + "Destination airport: LAX\n"
            + "Total gross weight (kg): 200\n"
            + "Total volume (cbm): 0.2\n"
            + "Dangerous goods: No");
    worker.work();
    assertEquals("sent", ledger.delivery("packing-correction:7"));
    assertEquals("waiting for documents", ledger.mail(7).get("stage"));
    verify(upstream, never()).rates(any(), any());
    restart();
    receiveDocument(
        1010,
        Ledger.id(outbound.getFirst().get("id")),
        "revised.txt",
        "Corrected packing list attached.",
        "Packing List\n"
            + "Destination airport: LAX\n"
            + "Total gross weight (kg): 100\n"
            + "Total volume (cbm): 0.2\n"
            + "Dangerous goods: None");
    worker.work();
    assertEquals("collecting", ledger.mail(7).get("stage"));
    verify(upstream, times(1)).rates(any(), any());
    verify(upstream, never()).parseRfq(anyMap());
  }

  @Test
  void followsDangerousGoodsDocumentsAcrossRepliesAndDeclinesOnce() throws Exception {
    receiveDocument(
        7,
        null,
        "packing.txt",
        "Cheapest air freight FRA to LAX for 100 kg and 0.2 cbm.",
        "Packing List\nDangerous goods: UN1950, Class 2.1");
    worker.work();
    assertEquals("waiting for documents", ledger.mail(7).get("stage"));
    assertEquals("sent", ledger.delivery("dg-docs:7"));
    receiveDocument(
        1010,
        Ledger.id(outbound.getFirst().get("id")),
        "sds.txt",
        "Here’s the safety data sheet.",
        "Safety Data Sheet\nSection 14 Transport Information\nUN1950");
    worker.work();
    assertEquals("sent", ledger.delivery("dg-incomplete:7"));
    restart();
    receiveDocument(
        1011,
        Ledger.id(outbound.getLast().get("id")),
        "declaration.txt",
        "Here’s the declaration.",
        "Shipper's Declaration for Dangerous Goods\nUN1950");
    worker.work();
    worker.work();
    assertEquals("sent", ledger.delivery("dg-unavailable:7"));
    assertEquals("declined", ledger.mail(7).get("stage"));
    assertEquals(3, outbound.size());
    assertNull(ledger.delivery("quote:7"));
    verify(upstream, never()).rates(any(), any());
  }

  @Test
  void missingGatewayAcknowledgmentHoldsQuoteAcrossRestart() throws Exception {
    receive(
        7,
        "buyer@hackloop.mail",
        "RFQ FRA to LAX",
        "Lowest cost for one tenth of a tonne and 0.2 cbm.",
        null);
    when(upstream.parseRfq(anyMap())).thenThrow(new ResourceAccessException("timeout"));
    worker.work();
    assertEquals("review", ledger.mail(7).get("stage"));
    assertTrue(outbound.isEmpty());
    restart();
    var s =
        new Shipment(
            "FRA",
            "LAX",
            java.math.BigDecimal.valueOf(100),
            java.math.BigDecimal.valueOf(0.2),
            "cheapest",
            null,
            null,
            0);
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
