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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
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
            new RfqGateway(upstream, ledger, catalog, mapper, clock, "gpt-4o-mini"),
            new MailboxWebhook(ledger, catalog, clock, mapper, ""),
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
              var schema = mapper.valueToTree(payload.get("response_format")).path("json_schema");
              if ("quote_check".equals(schema.path("name").asText())) {
                assertEquals(24, payload.get("max_completion_tokens"));
                assertEquals(mapper.createArrayNode().add("contradiction"),
                    schema.path("schema").path("required"));
                return gatewayResponse("{\"contradiction\":false}");
              }
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
              return gatewayResponse(fields.toString());
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

  private JsonNode gatewayResponse(String content) {
    return mapper.valueToTree(Map.of("choices", List.of(
        Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
  }

  private HttpClientErrorException rateLimited(String retryAfter) {
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
    return HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS, "Rate limited", headers, new byte[0], null);
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
    "220.11|31|Estimated transit time is 31 hours from actual pickup to airport delivery, excluding any wait before collection.",
    "268.45|32|Estimated transit time from actual pickup to airport delivery is 32 hours, excluding any wait before collection.",
    "499.56|11|Estimated transit time from actual pickup to airport delivery, excluding any wait before collection: 11 hours."
  })
  void distinguishesCollectionWaitFromAnExclusionInThePrice(String euros, int hours, String timing) {
    String body = "Our all-in rate for collection and delivery to CGN is " + euros + " EUR.\n" + timing;
    var bid = MailParser.bid(body, true).orElseThrow();
    assertEquals(euros, bid.euros().toPlainString());
    assertEquals(hours / 24.0, bid.days());
    assertTrue(MailParser.bid(body + " The rate is excluding fuel.", true).isEmpty());
  }

  @Test
  void rechecksRejectedTruckBidFromAnOpenThreadOnRestart() throws Exception {
    receive(7, "buyer@example.invalid", "RFQ CGN to SGN",
        "Fastest air freight for 100 kg and 0.2 cbm. Pickup from postcode 53088.", null);
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(new Offer("Test Cargo", 2,
        new java.math.BigDecimal("50.00"), new java.math.BigDecimal("100"), "portal", "TEST",
        START.plusSeconds(900))), true));
    worker.work();
    var truck = outbound.stream().filter(m -> m.get("subject").toString().contains("pickup from postcode 53088"))
        .findFirst().orElseThrow();
    receive(2001, truck.get("to").toString(), "Truck reply", "All-in EUR 499.56. Estimated transit time"
        + " from actual pickup to airport delivery, excluding any wait before collection: 11 hours.",
        Ledger.id(truck.get("id")));
    ledger.jdbc().update("UPDATE tender SET euros=NULL,days=NULL,issue='Unreadable' WHERE reply_id=2001");
    ledger.jdbc().update("UPDATE mail SET stage='bid unreadable' WHERE id=2001");
    restart();
    clock.current = START.plusSeconds(80);
    worker.recheckOpenExtractions();
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals("bid received", ledger.mail(2001).get("stage"));
    assertTrue(outbound.getLast().get("body").toString().contains("EUR 549.56 all in"));
    assertTrue(outbound.getLast().get("body").toString().contains("2 days and 11 hours"));
  }

  @Test
  void quotesCityPickupOnlyAfterTheCarrierConfirmsCitywideCoverage() {
    receive(7, "buyer@example.invalid", "RFQ to RUH", "Fastest air freight for 100 kg and 0.2 cbm."
        + " Please arrange pickup from our warehouse.\n\nRegards from Regensburg", null);
    worker.work();
    var question = outbound.getFirst();
    assertTrue(question.get("body").toString().contains("full five-digit pickup postcode"));
    receive(2001, "buyer@example.invalid", "Re: RFQ", "The pickup location is Augsburg, Germany."
        + "\n\nRegards from Regensburg", Ledger.id(question.get("id")));
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(new Offer("Test Cargo", 1,
        new java.math.BigDecimal("50.00"), new java.math.BigDecimal("100"), "portal", "TEST",
        START.plusSeconds(900))), true));
    worker.work();
    var shipment = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    assertEquals("86", shipment.pickupZone());
    assertEquals("MUC", shipment.origin());
    var trucks = outbound.stream()
        .filter(m -> m.get("subject").toString().contains("pickup from Augsburg to MUC")).toList();
    assertEquals(2, trucks.size());
    assertTrue(trucks.getFirst().get("body").toString().contains("Valid for all of Augsburg"));
    assertEquals(1, outbound.stream().filter(m -> "buyer@example.invalid".equals(m.get("to"))).count());
    long replyId = 2002;
    for (var truck : trucks) {
      receive(replyId++, truck.get("to").toString(), "Re: Truck quote",
          "All-in EUR 5.00. Transit from pickup to delivery: 2 hours. Only valid for postcode 86150.",
          Ledger.id(truck.get("id")));
    }
    clock.current = START.plusSeconds(80);
    worker.work();
    assertNull(ledger.delivery("quote:7"));
    assertEquals("sent", ledger.delivery("airport-delivery:7"));
    receive(replyId, trucks.getFirst().get("to").toString(), "Re: Truck quote",
        "All-in EUR 25.00. Transit time from pickup to delivery: 2 hours. Valid for all of Augsburg.",
        Ledger.id(trucks.getFirst().get("id")));
    clock.current = START.plusSeconds(156);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertTrue(outbound.getLast().get("body").toString().contains("EUR 75.00 all in"));
    assertTrue(outbound.getLast().get("body").toString().contains("collection from Augsburg"));
  }

  @Test
  void quotesAirportDeliveryOnlyWithExplicitCustomerApproval() throws Exception {
    receive(7, "buyer@example.invalid", "RFQ to RUH", "Fastest air freight for 100 kg and 0.2 cbm."
        + " Please arrange pickup from our warehouse.", null);
    worker.work();
    var question = outbound.getFirst();
    receive(2001, "buyer@example.invalid", "Re: RFQ", "Pickup is in Augsburg, Germany.",
        Ledger.id(question.get("id")));
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(new Offer("Test Cargo", 1,
        new java.math.BigDecimal("50.00"), new java.math.BigDecimal("100"), "portal", "TEST",
        START.plusSeconds(900))), true));
    worker.work();
    clock.current = START.plusSeconds(80);
    worker.work();
    var offer = outbound.getLast();
    assertTrue(offer.get("body").toString().contains("We will deliver to MUC ourselves."));
    long parent = Ledger.id(offer.get("id"));
    receive(2002, "unrelated@example.invalid", "Re: RFQ", "We will deliver to MUC ourselves.", parent);
    receive(2003, "buyer@example.invalid", "Re: RFQ", "We will deliver to MUC ourselves if needed.", parent);
    worker.work();
    assertNull(ledger.delivery("quote:7"));
    assertEquals("86", ledger.read(ledger.mail(7).get("fields"), Shipment.class).pickupZone());
    restart();
    receive(2004, "buyer@example.invalid", "Re: RFQ", "We will deliver to MUC ourselves.", parent);
    worker.work();
    var shipment = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    assertEquals("MUC", shipment.origin());
    assertNull(shipment.pickupZone());
    clock.current = START.plusSeconds(156);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    String quote = outbound.getLast().get("body").toString();
    assertTrue(quote.contains("EUR 50.00 all in"));
    assertTrue(quote.contains("airport to airport"));
    verify(upstream, times(1)).rates(any(), any());
  }

  @Test
  void doesNotRepeatTheSameCustomerQuestionIndefinitely() {
    request("Please arrange pickup from our warehouse.");
    worker.work();
    receive(2001, "buyer@hackloop.mail", "Re: RFQ", "I’m still checking the address.",
        Ledger.id(outbound.getLast().get("id")));
    worker.work();
    assertEquals(2, outbound.size());
    receive(2002, "buyer@hackloop.mail", "Re: RFQ", "I’m still checking the address.",
        Ledger.id(outbound.getLast().get("id")));
    worker.work();
    worker.work();
    assertEquals(2, outbound.size());
    assertEquals("waiting for customer", ledger.mail(7).get("stage"));
  }

  @Test
  void triesOtherTruckersWhenTheFirstBidsOnlyGiveCollectionWindows() throws Exception {
    receive(7, "buyer@example.invalid", "RFQ CGN to DFW",
        "Please quote the fastest service for 100 kg and 0.2 cbm. Pickup from postcode 51804.", null);
    worker.work();
    var invitations = outbound.stream()
        .filter(m -> m.get("subject").toString().contains("pickup from postcode 51804")).toList();
    assertEquals(2, invitations.size());
    long message = 2000;
    for (var invitation : invitations) {
      receive(message++, invitation.get("to").toString(), "Truck reply",
          "Our fixed all-in price is EUR 42.00. We can collect within 13 hours.",
          Ledger.id(invitation.get("id")));
    }
    clock.current = START.plusSeconds(80);
    worker.work();
    assertEquals(2, outbound.stream().filter(m -> m.containsKey("in_reply_to")).count());
    restart();
    clock.current = START.plusSeconds(116);
    worker.work();
    worker.work();
    var contacted = outbound.stream()
        .filter(m -> m.get("subject").toString().contains("pickup from postcode 51804"))
        .map(m -> m.get("to")).toList();
    assertEquals(4, contacted.size());
    assertEquals(4, new HashSet<>(contacted).size());
    assertNull(ledger.delivery("quote:7"));
  }

  @Test
  void skipsExcludedTruckersBeforeSpendingBackupSlots() {
    receive(7, "buyer@example.invalid", "RFQ FRA to LAX",
        "Please quote the fastest air freight from FRA to LAX for 100 kg and 0.2 cbm. "
            + "Pickup from postcode 60311. Please exclude Domblick Kurierdienst and Westfalenroute.",
        null);
    worker.work();
    var initialTrucks = outbound.stream()
        .filter(m -> m.get("subject").toString().contains("pickup from")).toList();
    assertEquals(List.of("taunusfracht@hackloop.mail", "isarwelle-spedition@hackloop.mail"),
        initialTrucks.stream().map(m -> m.get("to")).toList());
    long reply = 31;
    for (var invite : initialTrucks) {
      receive(reply++, invite.get("to").toString(), "Re: Rate request AEIOU-7",
          "Unfortunately we cannot quote this shipment.", Ledger.id(invite.get("id")));
    }
    clock.current = START.plusSeconds(76);
    worker.work();
    var backup = outbound.stream().filter(m -> "bodenseefracht@hackloop.mail".equals(m.get("to")))
        .findFirst().orElseThrow();
    assertNull(ledger.delivery("bid:7:domblick-kurierdienst@hackloop.mail"));
    assertNull(ledger.delivery("bid:7:westfalenroute@hackloop.mail"));
    receive(reply, backup.get("to").toString(), "Re: Rate request AEIOU-7",
        "All-in EUR 120. Road transit: 5 hours.", Ledger.id(backup.get("id")));
    clock.current = START.plusSeconds(152);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals("Bodenseefracht", ledger.read(ledger.mail(7).get("proposal"), WaveWorker.Draft.class)
        .trucker());
  }

  @Test
  void asksImmediatelyAndRequotesTruckPickupFromTheConfirmedPostcode() throws Exception {
    receive(7, "buyer@hackloop.mail", "RE: FW: Old RFQ to HKG",
        "Please quote the cheapest air freight to SGN for 100 kg and 0.2 cbm, including collection"
            + " from our warehouse.\n\nBest greetings from Regensburg"
            + "\n\n-----Original Message-----\nOld pickup postcode 93047 and destination HKG.", null);
    var prior = new Shipment("MUC", "SGN", new java.math.BigDecimal("100"),
        new java.math.BigDecimal("0.2"), "cheapest", "93", null, 0);
    var trucker = catalog.contact("isarwelle-spedition@hackloop.mail").orElseThrow();
    var stale = worker.invite(7, prior, trucker);
    worker.work();
    var question = outbound.getLast();
    assertEquals("buyer@hackloop.mail", question.get("to"));
    assertTrue(question.get("body").toString().contains("full five-digit pickup postcode"));
    assertEquals("waiting for customer", ledger.mail(7).get("stage"));
    verify(upstream, never()).rates(any(), any());

    long parent = Ledger.id(question.get("id"));
    receive(2001, "someone-else@hackloop.mail", "Re: RFQ", "Pickup postcode 60311.", parent);
    worker.work();
    assertEquals(2, outbound.size());
    restart();
    clock.current = START.plusSeconds(10);
    receive(2002, "buyer@hackloop.mail", "Re: RFQ", "Sorry, it is 80287 Munich, Germany.", parent);
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(new Offer("Test Cargo", 2,
        new java.math.BigDecimal("50.00"), new java.math.BigDecimal("100"), "portal", "TEST",
        START.plusSeconds(900))), true));
    worker.work();
    var retry = outbound.stream().filter(m -> trucker.email().equals(m.get("to"))).toList().getLast();
    assertTrue(retry.get("body").toString().contains("postcode 80287"));
    var updated = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    assertEquals("80", updated.pickupZone());
    assertEquals("MUC", updated.origin());
    assertEquals("SGN", updated.destination());
    int count = outbound.size();
    restart();
    worker.work();
    assertEquals(count, outbound.size());
    receive(2003, trucker.email(), "Re: Rate request AEIOU-7", "All-in EUR 1.00, transit 1 day.",
        Ledger.id(stale.get("id")));
    clock.current = START.plusSeconds(80);
    worker.work();
    assertNull(ledger.delivery("quote:7"));
    receive(2004, trucker.email(), "Re: Rate request AEIOU-7", "All-in EUR 25.00, transit 1 day.",
        Ledger.id(retry.get("id")));
    clock.current = START.plusSeconds(86);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertTrue(outbound.getLast().get("body").toString().contains("EUR 75.00 all in"));
  }

  @Test
  void asksForMissingFreightDetailsAndContinuesWithTheCustomerAnswer() {
    var missing = """
        {"origin":null,"destination":"LAX","weight_kg":null,"volume_cbm":null,
        "preference":"cheapest","pickup_required":false,"pickup_zone":null,"needs_review":false}
        """;
    when(upstream.parseRfq(anyMap())).thenReturn(mapper.valueToTree(Map.of("choices", List.of(
        Map.of("finish_reason", "stop", "message", Map.of("content", missing))))));
    receive(7, "buyer@hackloop.mail", "RFQ to LAX", "Please quote the cheapest air freight.", null);
    worker.work();
    var question = outbound.getFirst();
    assertTrue(question.get("body").toString().contains("departure airport"));
    assertTrue(question.get("body").toString().contains("total gross weight"));
    assertTrue(question.get("body").toString().contains("total volume"));
    worker.work();
    assertEquals(1, outbound.size());
    receive(2001, "buyer@hackloop.mail", "Re: RFQ", "Departure airport FRA. The shipment is 100 kg gross.",
        Ledger.id(question.get("id")));
    worker.work();
    var followUp = outbound.getLast();
    assertEquals(2001L, followUp.get("in_reply_to"));
    assertTrue(followUp.get("body").toString().contains("total volume"));
    assertFalse(followUp.get("body").toString().contains("departure airport"));
    assertFalse(followUp.get("body").toString().contains("gross weight"));
    worker.work();
    assertEquals(2, outbound.size());
    receive(2002, "buyer@hackloop.mail", "Re: RFQ", "The volume is 0.2 cbm.",
        Ledger.id(followUp.get("id")));
    worker.work();
    assertEquals(7, outbound.size());
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    verify(upstream, times(1)).parseRfq(anyMap());
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
    verify(upstream, times(1)).parseRfq(anyMap());
  }

  @Test
  void asksForMissingPostcodeAndLetsUnansweredRequestsExpire() {
    receive(
        7,
        "buyer@hackloop.mail",
        "RFQ FRA to LAX",
        "Cheapest for 100 kg, 0.2 cbm. Pickup needed.",
        null);
    worker.work();
    assertEquals("waiting for customer", ledger.mail(7).get("stage"));
    receive(8, "buyer@hackloop.mail", "RFQ FRA to LAX", "Cheapest for 100 kg, 0.2 cbm.", null);
    clock.current = START.plusSeconds(361);
    worker.work();
    assertEquals("expired", ledger.mail(7).get("stage"));
    assertEquals("expired", ledger.mail(8).get("stage"));
    assertEquals(1, outbound.size());
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
    var pickupWindow = MailParser.bid("Our all-in rate is 347.09 EUR. Estimated time from shipment"
        + " readiness to collection is 13 hours. This does not include road transit, so we can’t"
        + " confirm the total time to delivery at CGN airport.", true).orElseThrow();
    assertEquals("347.09", pickupWindow.euros().toPlainString());
    assertNull(pickupWindow.days());
    assertEquals(15 / 24.0, MailParser.bid("All-in rate EUR 169.92, with an estimated"
        + " collection-to-delivery time of 15 hours.", true).orElseThrow().days());
    assertTrue(MailParser.bid("The estimated rate is EUR 150, transit 2 hours.", true).isEmpty());
    assertTrue(MailParser.bid("EUR 25", false).isEmpty());
    assertEquals(158, catalog.size());
  }

  @Test
  void keepsFirmTruckPricesOnlyWhenUncertaintyIsLimitedToTiming() {
    assertTrue(MailParser.bid(
        "All-in EUR 100. We cannot confirm the rate or the transit time.", true).isEmpty());
    assertTrue(MailParser.bid(
        "All-in EUR 100. We cannot confirm the transit time or the rate.", true).isEmpty());
    assertTrue(MailParser.bid(
        "All-in EUR 100. The price is not yet confirmed. Transit: 3 hours.", true).isEmpty());
    var bid = MailParser.bid(
        "All-in EUR 100. We cannot confirm the transit time.", true).orElseThrow();
    assertEquals("100", bid.euros().toPlainString());
    assertNull(bid.days());
    assertNull(MailParser.bid("All-in EUR 100. The road transit time is unavailable.", true)
        .orElseThrow().days());
  }

  @Test
  void pickupRangeDoesNotInvalidateAirportTransit() {
    var bid = MailParser.bid("All-in EUR 100. Pickup in 2 to 4 hours."
        + " Transit from actual pickup to airport delivery: 3 hours.", true).orElseThrow();
    assertEquals(3 / 24.0, bid.days());
    assertNull(MailParser.bid("All-in EUR 100. Transit from actual pickup to airport delivery:"
        + " 2 to 4 hours. Road transit: 3 hours.", true).orElseThrow().days());
    assertNull(MailParser.bid("All-in EUR 100. Estimated 2–4 hours from actual pickup to airport"
        + " delivery.", true).orElseThrow().days());
  }

  @Test
  void invitesAirlinesThatDeclaredTheExactRouteAndUsesTheirBids() {
    receive(2, "elbflug-air-freight@hackloop.mail", "Prior carrier response",
        "We fly FRA-DFW, STR-PVG.", null);
    receive(3, "lumen-air-cargo@hackloop.mail", "Prior carrier response",
        "We fly FRA-LOS, STR-PVG.", null);
    receive(4, "nova-atlas-cargo@hackloop.mail", "Prior carrier response",
        "We fly FRA-PVG, STR-EZE.", null);
    receive(5, "helix-air-freight@hackloop.mail", "Prior carrier response",
        "We fly FRA-PVG, MUC-PVG.", null);
    receive(7, "buyer@example.invalid", "Air freight quote request to PVG",
        "Please quote the cheapest air freight to PVG for 1225 kg and 4.62 cbm."
            + " Please arrange pickup from postcode 63198.", null);
    worker.work();
    assertEquals(5, outbound.size());
    assertEquals("helix-air-freight@hackloop.mail", outbound.get(0).get("to"));
    assertEquals("nova-atlas-cargo@hackloop.mail", outbound.get(1).get("to"));
    assertTrue(outbound.stream().noneMatch(m -> Set.of("elbflug-air-freight@hackloop.mail",
        "lumen-air-cargo@hackloop.mail").contains(m.get("to"))));
    receive(10, "helix-air-freight@hackloop.mail", "Re: Rate request AEIOU-7",
        "All-in EUR 3981.25. Transit: 2 days.", Ledger.id(outbound.get(0).get("id")));
    receive(11, "nova-atlas-cargo@hackloop.mail", "Re: Rate request AEIOU-7",
        "All-in EUR 3500. Transit: 2 days.", Ledger.id(outbound.get(1).get("id")));
    var truck = outbound.stream().filter(m -> "westfalenroute@hackloop.mail".equals(m.get("to")))
        .findFirst().orElseThrow();
    receive(12, truck.get("to").toString(), "Re: Rate request AEIOU-7",
        "All-in EUR 137.50. Pickup in 30 hours.", Ledger.id(truck.get("id")));
    clock.current = START.plusSeconds(75);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertTrue(outbound.getLast().get("body").toString().contains("EUR 3637.50 all in"));
  }

  @Test
  void airportQuoteWaitsForEmailBidsFromInvitationTimeEvenWithACompletePortal() {
    var portal = new Offer("Portal Cargo", 2, new java.math.BigDecimal("150"),
        new java.math.BigDecimal("100"), "portal", "R-WAIT", START.plusSeconds(900));
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(portal), true));
    request("");
    clock.current = START.plusSeconds(20);
    worker.work();
    assertEquals(5, outbound.size());
    assertNull(ledger.delivery("quote:7"));
    clock.current = START.plusSeconds(94);
    worker.work();
    assertNull(ledger.delivery("quote:7"));
    var invitation = outbound.getFirst();
    receive(10, invitation.get("to").toString(), "Re: Rate request AEIOU-7",
        "All-in EUR 100. Transit: 2 days.", Ledger.id(invitation.get("id")));
    clock.current = START.plusSeconds(95);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertTrue(outbound.getLast().get("body").toString().contains("EUR 100.00 all in"));
  }

  @Test
  void incompletePortalGetsAnotherAttemptWhenCarriersReplyEarly() {
    when(upstream.rates(any(), any()))
        .thenReturn(new FiveU.Rates(List.of(), false), new FiveU.Rates(List.of(), true));
    request("");
    worker.work();
    long id = 10;
    for (var invite : List.copyOf(outbound))
      receive(id++, invite.get("to").toString(), "Re: Rate request AEIOU-7",
          "All-in EUR 100. Transit: 2 days.", Ledger.id(invite.get("id")));
    clock.current = START.plusSeconds(33);
    worker.work();
    assertNull(ledger.delivery("quote:7"));
    clock.current = START.plusSeconds(35);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    verify(upstream, times(2)).rates(any(), any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"15", "Sat, 21 Nov 2026 15:01:31 GMT"})
  void quoteGatewayRetriesOnceAfterRetryAfterEvenAcrossRestart(String retryAfter) throws Exception {
    request("");
    when(upstream.parseRfq(anyMap())).thenThrow(rateLimited(retryAfter))
        .thenReturn(gatewayResponse("{\"contradiction\":false}"));
    worker.work();
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("collecting", ledger.mail(7).get("stage"));
    assertNull(ledger.delivery("quote:7"));
    var draft = ledger.mail(7).get("proposal");
    restart();
    clock.current = START.plusSeconds(90);
    worker.work();
    assertEquals(draft, ledger.mail(7).get("proposal"));
    verify(upstream, times(1)).parseRfq(anyMap());
    clock.current = START.plusSeconds(91);
    worker.work();
    worker.work();
    verify(upstream, times(2)).parseRfq(anyMap());
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals(1, outbound.stream().filter(m -> m.get("to").equals("buyer@hackloop.mail")).count());
  }

  @Test
  void gatewayRetryAfterTheCustomerDeadlineDoesNotSend() {
    request("");
    when(upstream.parseRfq(anyMap())).thenThrow(rateLimited("300"));
    worker.work();
    clock.current = START.plusSeconds(76);
    worker.work();
    clock.current = START.plusSeconds(376);
    worker.work();
    assertEquals("expired", ledger.mail(7).get("stage"));
    assertNull(ledger.delivery("quote:7"));
    verify(upstream, times(1)).parseRfq(anyMap());
  }

  @Test
  void gatewayRetryDoesNotSendWithAnExpiredTruckRate() {
    request("Please collect from postcode 60311.");
    when(upstream.parseRfq(anyMap())).thenThrow(rateLimited("60"));
    worker.work();
    var truck = outbound.stream().filter(m -> m.get("subject").toString().contains("pickup from"))
        .findFirst().orElseThrow();
    receive(10, truck.get("to").toString(), "Re: Rate request AEIOU-7",
        "All-in EUR 25. Rate valid until 21 Nov 2026 15:02 UTC.", Ledger.id(truck.get("id")));
    clock.current = START.plusSeconds(76);
    worker.work();
    var draft = ledger.read(ledger.mail(7).get("proposal"), WaveWorker.Draft.class);
    assertEquals(START.plusSeconds(120), draft.offer().validUntil());
    clock.current = START.plusSeconds(136);
    worker.work();
    assertEquals("waiting for pickup", ledger.mail(7).get("stage"));
    assertNull(ledger.delivery("quote:7"));
    verify(upstream, times(1)).parseRfq(anyMap());
  }

  @Test
  void boardVerdictJoinsOnlyTheOriginalRfqAndUpdatesWhenPointsArrive() {
    var result = new HashMap<String, Object>(Map.of(
        "round_no", 29, "sent_at", "2026-11-21T15:00:00+00:00", "verdict", "rejected",
        "reason", "too_high", "points", Map.of()));
    var watcher = new BoardWatcher(ledger, upstream, "fixture");
    when(upstream.get("/v1/board/me")).thenReturn(Map.of("requests", List.of(result)));
    watcher.poll();
    request("");
    receive(8, "buyer@hackloop.mail", "Re: RFQ", "Thank you.", 7L);
    var rows = ledger.inbox();
    assertEquals(result, rows.getLast().get("board_result"));
    assertNull(rows.getFirst().get("board_result"));
    result.put("points", Map.of("time", 15));
    watcher.poll();
    assertEquals(result, ledger.inbox().getLast().get("board_result"));
    assertNull(ledger.delivery("quote:7"));
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

    ledger.jdbc().update("UPDATE mail SET fields=json_set(fields,'$.pickup_zone','60') WHERE id=7");
    receive(
        2007,
        "tracking@hackloop.mail",
        "Status of our shipment 60311 to LAX, 100 kg",
        "Hofmann Textilwerk KG, 60311 to LAX, 100 kg, ready on 2026-11-28? Where is it now?",
        null);
    receive(
        2008,
        "tracking@hackloop.mail",
        "Status of our shipment 60311 to LAX, 900 kg",
        "Hofmann Textilwerk KG, 60311 to LAX, 900 kg. Where is it now?",
        null);
    tracking.answer();
    var postcodeReplies =
        outbound.stream().filter(m -> "tracking@hackloop.mail".equals(m.get("to"))).toList();
    assertEquals(2, postcodeReplies.size());
    assertEquals(2007L, ((Number) postcodeReplies.getLast().get("in_reply_to")).longValue());
    assertTrue(postcodeReplies.getLast().get("body").toString().contains("BK-SMMUWG2S"));
    assertEquals("tracking review", ledger.mail(2008).get("stage"));

    // A second booking on the same lane: the stated weight decides which shipment is meant.
    ledger.jdbc().update("CREATE TABLE twin AS SELECT * FROM mail WHERE id=7");
    ledger.jdbc().update("""
        UPDATE twin SET id=9001,fields=json_set(fields,'$.weight_kg',250),
        booking=json_set(booking,'$.booking_reference','BK-TWINLANE')
        """);
    ledger.jdbc().update("INSERT INTO mail SELECT * FROM twin");
    ledger.jdbc().update("DROP TABLE twin");
    receive(
        2009,
        "tracking@hackloop.mail",
        "Status of our shipment FRA to LAX, 100 kg",
        "Hofmann Textilwerk KG, FRA to LAX, 100 kg, ready on 2026-11-28? Where is it now?",
        null);
    tracking.answer();
    var laneReplies =
        outbound.stream().filter(m -> "tracking@hackloop.mail".equals(m.get("to"))).toList();
    assertEquals(3, laneReplies.size());
    assertEquals(2009L, ((Number) laneReplies.getLast().get("in_reply_to")).longValue());
    assertTrue(laneReplies.getLast().get("body").toString().contains("BK-SMMUWG2S"));
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
  void manualPricingIsLocalAndSendingChecksTheShipmentOnce() throws Exception {
    request("");
    var s = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    worker.price(7, s, null);
    restart();
    var draft = worker.price(7, s, null);
    verify(upstream, never()).parseRfq(anyMap());
    worker.sendQuote(7, draft.revision());
    verify(upstream, times(1)).parseRfq(anyMap());
    assertEquals("sent", ledger.delivery("quote:7"));
  }

  @Test
  void gatewayCheckRecordsAContradictionAndStillSendsAnOtherwiseValidQuote() throws Exception {
    request("");
    when(upstream.parseRfq(anyMap())).thenReturn(gatewayResponse("{\"contradiction\":true}"));
    worker.work();
    verify(upstream, never()).parseRfq(anyMap());
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals("{\"contradiction\":true}", ledger.jdbc().queryForObject(
        "SELECT result FROM rfq_parse WHERE request_id=7", String.class));

    restart();
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    verify(upstream, times(1)).parseRfq(anyMap());
  }

  @Test
  void explicitAirportSelfDeliveryAndNegatedPickupQuoteAutomatically() {
    receive(7, "buyer@hackloop.mail", "Air freight quote: CGN to DFW",
        "Please quote the cheapest air freight from CGN to DFW. We’ll deliver the shipment"
            + " to Cologne airport (CGN) ourselves, so there’s no need to arrange pickup."
            + " The shipment consists of 4 pieces, with 85 kg gross and 0.70 cbm.",
        null);
    worker.work();
    assertNull(ledger.delivery("pickup-postcode:7"));
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    verify(upstream, times(1)).parseRfq(anyMap());
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
      if (round == 2) {
        assertNull(ledger.delivery("quote:7"));
        var transitQuestions = outbound.stream()
            .filter(m -> m.get("body").toString().contains("Thanks for the pickup quote"))
            .toList();
        assertEquals(2, transitQuestions.size());
        for (var question : transitQuestions) {
          String address = question.get("to").toString();
          String euros = address.startsWith("hansekogge") ? "245.02" : "266.03";
          receive(replyId++, address, "Re: Truck transit", "All-in EUR " + euros
              + ". Estimated transit time from pickup to delivery: 23 hours.",
              Ledger.id(question.get("id")));
        }
      }
    } else {
      assertNull(ledger.delivery("quote:7"));
      assertFalse(outbound.isEmpty());
      clock.current = START.plusSeconds(76);
    }
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals(
        1, outbound.stream().filter(m -> "buyer@example.invalid".equals(m.get("to"))).count());
    var quote = ledger.read(ledger.mail(7).get("proposal"), WaveWorker.Draft.class);
    var expectedTotal = round == 9 ? new java.math.BigDecimal("5202.20") : fixture.get("euros").decimalValue();
    assertEquals(0, expectedTotal.compareTo(quote.offer().totalEur()));
    if (round == 9) {
      assertEquals("361.24", quote.pickupEur().toPlainString());
      assertTrue(quote.body().contains("confirm pickup timing"));
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
    verify(upstream, times(1)).parseRfq(anyMap());
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
  void resumesPortalSearchFromVerifiedRatesAfterRestart() throws Exception {
    var successfulReads = new ArrayList<String>();
    var names = Map.of("AU", "Aurelia Air Cargo", "BX", "Borealis Freight Airways",
        "JP", "Juniper Cargo Lines");
    var prices = Map.of("AU", 40, "BX", 50, "JP", 20);
    var portal = new FiveU("http://localhost", "fixture", new MarketMemory(ledger)) {
      private int readsThisAttempt;

      @Override
      Map<String, Object> get(String uri, Object... args) {
        if (uri.endsWith("/ports")) {
          readsThisAttempt = 0;
          return Map.of("lanes", List.of(Map.of("origin", "MUC", "destination", "CAI",
              "airlines", List.of("AU", "BX", "JP").stream()
                  .map(code -> Map.of("code", code, "name", names.get(code))).toList())));
        }
        if (uri.contains("/brackets")) return Map.of("breaks_kg", List.of(0, 45, 100));
        if (readsThisAttempt++ >= 2) throw rateLimited("35");
        String code = args[2].toString();
        successfulReads.add(code);
        return Map.of("currency", "EUR", "total", prices.get(code), "dataset_version", "v1",
            "valid_until", START.plusSeconds(900).toString(), "rate_id", "rate-" + code,
            "chargeable_weight_kg", args[3], "airline", names.get(code), "transit_days", "3");
      }
    };
    when(upstream.rates(any(), any()))
        .thenAnswer(call -> portal.rates(call.getArgument(0), call.getArgument(1)));
    when(upstream.rates(any(), any(), anyList(), nullable(String.class)))
        .thenAnswer(call -> portal.rates(call.getArgument(0), call.getArgument(1),
            call.getArgument(2), call.getArgument(3)));
    receive(7, "buyer@example.invalid", "RFQ MUC to CAI",
        "Please quote the cheapest air freight from MUC to CAI for 100 kg and 0.2 cbm.", null);
    worker.work();
    assertEquals(List.of("AU", "BX"), successfulReads);
    restart();
    clock.current = START.plusSeconds(35);
    worker.work();
    assertEquals(List.of("AU", "BX", "JP"), successfulReads);
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals("rate-JP",
        ledger.read(ledger.mail(7).get("proposal"), WaveWorker.Draft.class).offer().rateId());
  }

  @Test
  void roundEightUsesTheCheaperBookablePortalWeightBreakOnTheFirstPass() {
    // Round 8 portal observations. Only the HTTP boundary and rate IDs are fixtures.
    var rateReads = new ArrayList<String>();
    var portal =
        new FiveU("http://localhost", "fixture", new MarketMemory(ledger)) {
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
    assertNull(ledger.delivery("quote:7"));
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    var quote = ledger.read(ledger.mail(7).get("proposal"), WaveWorker.Draft.class);
    assertEquals("BX-500", quote.offer().rateId());
    assertEquals("1475.00", quote.offer().totalEur().toPlainString());
    assertEquals(4.0, quote.offer().days());
    assertTrue(quote.body().contains("486 kg shipment"));
    assertEquals("486", shipment.weightKg().toPlainString());
    assertEquals("2.62", shipment.volumeCbm().toPlainString());
    assertNull(shipment.pickupZone());
    verify(upstream, times(1)).parseRfq(anyMap());
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
    verify(upstream, times(1)).parseRfq(anyMap());
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
  void gatewayRecoversCompetitiveTruckTransitWithExactEvidence() throws Exception {
    String price = "Our all-in rate is EUR 100.";
    String wait = "We can collect within 13 hours.";
    String road = "Once loaded, our driver reaches the airport in 3 hours.";
    var calls = new AtomicInteger();
    when(upstream.parseRfq(anyMap())).thenAnswer(call -> {
      Map<String, Object> payload = call.getArgument(0);
      String schema = mapper.valueToTree(payload.get("response_format"))
          .path("json_schema").path("name").asText();
      if ("quote_check".equals(schema)) return gatewayResponse("{\"contradiction\":false}");
      assertEquals("truck_bid", schema);
      calls.incrementAndGet();
      return gatewayResponse(mapper.writeValueAsString(Map.of(
          "price_confirmed", true, "price_evidence", price, "collection_wait_hours", 13,
          "collection_wait_evidence", wait, "road_transit_evidence", road)));
    });
    receive(7, "buyer@example.invalid", "RFQ FRA to LAX",
        "Please quote the fastest freight for 100 kg and 0.2 cbm. Pickup from postcode 60311.", null);
    worker.work();
    var truck = outbound.stream().filter(m -> m.get("subject").toString().contains("pickup from"))
        .findFirst().orElseThrow();
    receive(2001, truck.get("to").toString(), "Re: Rate request AEIOU-7",
        price + " " + wait + " " + road, Ledger.id(truck.get("id")));
    clock.current = START.plusSeconds(76);
    worker.work();
    worker.work();
    assertEquals(1, calls.get());
    assertEquals("sent", ledger.delivery("quote:7"));
    var draft = ledger.read(ledger.mail(7).get("proposal"), WaveWorker.Draft.class);
    assertEquals(3 / 24.0, draft.pickupDays());
    assertEquals(3 / 24.0, ledger.jdbc().queryForObject(
        "SELECT verified_days FROM truck_bid_parse WHERE reply_id=2001", Double.class));
    assertTrue(MailParser.roadTransitEvidence(
        "Estimated time from shipment readiness to collection is 13 hours.").isEmpty());
    assertTrue(MailParser.roadTransitEvidence("Once loaded, we reach the airport in 2 to 4 hours.").isEmpty());
  }

  @Test
  void gatewayCannotPromoteCollectionWaitToTruckTransit() {
    String price = "Our all-in rate is EUR 100.";
    String wait = "We can collect within 13 hours.";
    String road = "Once loaded, our driver reaches the airport in 3 hours.";
    when(upstream.parseRfq(anyMap())).thenReturn(gatewayResponse("""
        {"price_confirmed":true,"price_evidence":"Our all-in rate is EUR 100.",
        "collection_wait_hours":13,"collection_wait_evidence":"We can collect within 13 hours.",
        "road_transit_evidence":"We can collect within 13 hours."}
        """));
    receive(7, "buyer@example.invalid", "RFQ FRA to LAX",
        "Please quote the fastest freight for 100 kg and 0.2 cbm. Pickup from postcode 60311.", null);
    worker.work();
    var truck = outbound.stream().filter(m -> m.get("subject").toString().contains("pickup from"))
        .findFirst().orElseThrow();
    receive(2001, truck.get("to").toString(), "Re: Rate request AEIOU-7",
        price + " " + wait + " " + road, Ledger.id(truck.get("id")));
    clock.current = START.plusSeconds(76);
    worker.work();
    worker.work();
    verify(upstream, times(1)).parseRfq(anyMap());
    assertNull(ledger.delivery("quote:7"));
    assertEquals("waiting for pickup", ledger.mail(7).get("stage"));
  }

  @Test
  void archivedPackingHoldReopensForAnExplicitMatchingVolumeCorrection() {
    receiveDocument(7, null, "packing.txt",
        "Cheapest air freight from FRA to LAX. The shipment weighs 292 kg and measures 11.69 cbm.",
        "Packing List\nDestination airport: LAX\nTotal gross weight (kg): 292\n"
            + "Total volume (cbm): 7.49\nDangerous goods: No");
    worker.work();
    var request = outbound.getLast();
    receive(2001, "buyer@example.invalid", "Re: RFQ",
        "The packing list already attached is correct; the volume in my email was wrong. Total volume: 7.49 cbm.",
        Ledger.id(request.get("id")));
    ledger.jdbc().update("UPDATE document_check SET checked_through=2001 WHERE request_id=7");
    worker.recheckOpenExtractions();
    worker.work();
    assertEquals("7.49", ledger.read(ledger.mail(7).get("fields"), Shipment.class).volumeCbm().toPlainString());
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
  }

  @Test
  void appliesMatchingWeightAndVolumeTogetherFromOnePackingReply() {
    receiveDocument(7, null, "packing.txt",
        "Cheapest air freight from FRA to LAX. The shipment weighs 2644 kg and measures 14.90 cbm.",
        "Packing Specification\nDestination airport: LAX\nTotal gross weight (kg): 1751\n"
            + "Total volume (cbm): 11.97\nDangerous goods: Not restricted");
    worker.work();
    long parent = Ledger.id(outbound.getLast().get("id"));
    receive(2001, "buyer@example.invalid", "Re: RFQ",
        "The attached packing specification is correct. Total gross weight: 1,751 kg."
            + " Total volume: 11.970 cbm.", parent);
    worker.work();
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    var shipment = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    assertEquals("1751", shipment.weightKg().toPlainString());
    assertEquals("11.97", shipment.volumeCbm().toPlainString());
    verify(upstream).rates(eq(shipment), any());
  }

  @Test
  void reconcilesRouteAndBothTotalsAcrossPackingRepliesAndHoldsConflictingValues() {
    receiveDocument(7, null, "packing.txt",
        "Fastest air freight from FRA to LAX. The shipment weighs 2644 kg and measures 14.90 cbm.",
        "Packing Specification\nDestination airport: SGN\nTotal gross weight (kg): 1751\n"
            + "Total volume (cbm): 11.97\nDangerous goods: Not restricted");
    worker.work();
    long parent = Ledger.id(outbound.getLast().get("id"));
    receive(2001, "buyer@example.invalid", "Re: RFQ",
        "Please use the attached packing list. Destination airport: SGN. Gross weight: 1,751 kg (1751 kg).",
        parent);
    worker.work();
    assertEquals("waiting for documents", ledger.mail(7).get("stage"));
    receive(2002, "buyer@example.invalid", "Re: RFQ",
        "The attached document is correct. Total volume: 11.97 cbm or 12.50 cbm.", parent);
    worker.work();
    assertEquals("waiting for documents", ledger.mail(7).get("stage"));
    assertNull(ledger.delivery("quote:7"));
    verify(upstream, never()).rates(any(), any());

    receive(2003, "buyer@example.invalid", "Re: RFQ",
        "The attached document is correct. Total volume: 11.97 cbm.", parent);
    worker.work();
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    var shipment = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    assertEquals("SGN", shipment.destination());
    assertEquals("1751", shipment.weightKg().toPlainString());
    assertEquals("11.97", shipment.volumeCbm().toPlainString());
    verify(upstream).rates(eq(shipment), any());
  }

  @Test
  void onlyAppliesGrossWeightCorrectionSupportedByThePackingList() {
    receiveDocument(7, null, "packing.txt",
        "Fastest air freight from FRA to LAX. The shipment weighs 2644 kg and measures 11.97 cbm.",
        "Packing Specification\nDestination airport: LAX\nTotal gross weight (kg): 1751\n"
            + "Total volume (cbm): 11.97\nDangerous goods: Not restricted");
    worker.work();
    long parent = Ledger.id(outbound.getLast().get("id"));
    receive(2001, "buyer@example.invalid", "Re: RFQ",
        "Sorry for the confusion: the attached document is right. The gross weight is 1800 kg.", parent);
    worker.work();
    assertEquals("waiting for documents", ledger.mail(7).get("stage"));
    assertNull(ledger.delivery("quote:7"));
    receive(2002, "buyer@example.invalid", "Re: RFQ",
        "Sorry for the confusion: the attached document is right. The gross weight is 1751 kg.", parent);
    worker.work();
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    var shipment = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    assertEquals(0, shipment.weightKg().compareTo(new java.math.BigDecimal("1751")));
    assertEquals("11.97", shipment.volumeCbm().toPlainString());
  }

  @Test
  void acceptsNotRestrictedPackingVolumeConfirmedInTheCustomerThread() {
    receiveDocument(7, null, "packing.txt",
        "Cheapest air freight from FRA to LAX. The shipment weighs 292 kg and measures 2.78 cbm.",
        "Packing Specification\nDestination airport: LAX\nTotal gross weight (kg): 292\n"
            + "Total volume (cbm): 1.92\nDangerous goods: Not restricted");
    worker.work();
    assertNull(ledger.delivery("quote:7"));
    var correctionRequest = outbound.getLast();
    assertTrue(correctionRequest.get("body").toString().contains("couldn’t reconcile"));
    receive(2001, "buyer@example.invalid", "Re: RFQ",
        "Sorry for the confusion: the attached document is right. The pieces measure 120 x 40 x 80 cm each, 1.92 cbm in total.",
        Ledger.id(correctionRequest.get("id")));
    worker.work();
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    var shipment = ledger.read(ledger.mail(7).get("fields"), Shipment.class);
    assertEquals("1.92", shipment.volumeCbm().toPlainString());
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
    assertNull(ledger.delivery("quote:7"));
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertTrue(outbound.getLast().get("body").toString().contains("EUR 50.00"));
    verify(upstream, times(1)).parseRfq(anyMap());
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
  void excludesRequestedAirlineFromInvitationsPortalRatesAndFutureQuotesForTheSameSender() {
    String airway = "Please quote the cheapest air freight from FRA to ULN for 911 kg and 3.27 cbm."
        + " Include pickup from postcode 65797.";
    var blockedPortal = new Offer("ZS", 1, new java.math.BigDecimal("1000"),
        new java.math.BigDecimal("911"), "portal", "BLOCKED-RATE", START.plusSeconds(900));
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(blockedPortal), true));
    receive(7, "buyer@example.invalid", "RFQ FRA to ULN",
        airway + " Please do not offer Zephyr Steppe Cargo (ZS) for this shipment or for any of"
            + " our future inquiries.", null);
    worker.work();
    var invites = List.copyOf(outbound);
    assertTrue(invites.stream().noneMatch(m -> m.get("to").equals("zephyr-steppe-cargo@hackloop.mail")));
    var lagoon = invites.stream().filter(m -> m.get("to").equals("lagoon-isles-airways@hackloop.mail"))
        .findFirst().orElseThrow();
    receive(31, "lagoon-isles-airways@hackloop.mail", "Re: Rate request AEIOU-7",
        "Our all-in rate is EUR 3210. Transit: 3 days.", Ledger.id(lagoon.get("id")));
    var truck = invites.stream().filter(m -> m.get("subject").toString().contains("pickup from"))
        .findFirst().orElseThrow();
    receive(32, truck.get("to").toString(), "Re: Rate request AEIOU-7",
        "All-in EUR 118.66. Road transit: 31 hours.", Ledger.id(truck.get("id")));
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    var draft = ledger.read(ledger.mail(7).get("proposal"), WaveWorker.Draft.class);
    assertEquals("Lagoon Isles Airways", draft.offer().airline());
    assertEquals("3328.66", draft.offer().totalEur().toPlainString());

    receive(41, "buyer@example.invalid", "RFQ FRA to ULN", airway, null);
    receive(42, "another@example.invalid", "RFQ FRA to ULN", airway, null);
    worker.work();
    assertNull(ledger.delivery("bid:41:zephyr-steppe-cargo@hackloop.mail"));
    assertEquals("sent", ledger.delivery("bid:42:zephyr-steppe-cargo@hackloop.mail"));
  }

  @Test
  void airlineCodeBlocksNamedInvitationsAndOffersOnlyForThisShipment() {
    var blocked = new Offer("Zephyr Steppe Cargo", 1, new java.math.BigDecimal("100"),
        new java.math.BigDecimal("911"), "portal", "BLOCKED-RATE", START.plusSeconds(900));
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(blocked), true));
    receive(7, "buyer@example.invalid", "RFQ FRA to ULN",
        "Cheapest FRA to ULN for 911 kg and 3.27 cbm. Please exclude ZS for this shipment.", null);
    receive(8, "buyer@example.invalid", "RFQ FRA to ULN",
        "Cheapest FRA to ULN for 911 kg and 3.27 cbm.", null);
    worker.work();
    assertNull(ledger.delivery("bid:7:zephyr-steppe-cargo@hackloop.mail"));
    assertEquals("sent", ledger.delivery("bid:8:zephyr-steppe-cargo@hackloop.mail"));
    var lagoon = outbound.stream().filter(m -> "lagoon-isles-airways@hackloop.mail".equals(m.get("to"))
        && m.get("subject").toString().contains("AEIOU-7")).findFirst().orElseThrow();
    receive(31, "lagoon-isles-airways@hackloop.mail", "Re: Rate request AEIOU-7",
        "Our all-in rate is EUR 3210. Transit: 3 days.", Ledger.id(lagoon.get("id")));
    clock.current = START.plusSeconds(76);
    worker.work();
    assertEquals("sent", ledger.delivery("quote:7"));
    assertEquals("Lagoon Isles Airways",
        ledger.read(ledger.mail(7).get("proposal"), WaveWorker.Draft.class).offer().airline());
    assertEquals("sent", ledger.delivery("quote:8"));
    assertEquals("BLOCKED-RATE",
        ledger.read(ledger.mail(8).get("proposal"), WaveWorker.Draft.class).offer().rateId());
  }

  @Test
  void resolvesAdditionalAirlineCodesFromThePortalDirectory() {
    var directory = new FiveU("http://localhost", "fixture", new MarketMemory(ledger)) {
      @Override
      Map<String, Object> get(String uri, Object... args) {
        assertEquals("/v1/portal/ports", uri);
        return Map.of("lanes", List.of(Map.of("airlines",
            List.of(Map.of("code", "L8", "name", "Lagoon Isles Airways")))));
      }
    };
    when(upstream.airlineName(anyString(), any()))
        .thenAnswer(call -> directory.airlineName(call.getArgument(0), call.getArgument(1)));
    receive(7, "buyer@example.invalid", "RFQ FRA to ULN",
        "Cheapest FRA to ULN for 911 kg and 3.27 cbm. Please exclude L8 for this shipment.", null);
    worker.work();
    assertNull(ledger.delivery("bid:7:lagoon-isles-airways@hackloop.mail"));
    assertEquals("sent", ledger.delivery("bid:7:zephyr-steppe-cargo@hackloop.mail"));
  }

  @Test
  void verifiesDangerousGoodsBeforeRequestAndKeepsTheJavaHoldOnDisagreement() {
    when(upstream.parseRfq(anyMap())).thenAnswer(call -> {
      var payload = mapper.valueToTree(call.<Map<String, Object>>getArgument(0));
      assertEquals("dangerous_goods_documents",
          payload.path("response_format").path("json_schema").path("name").asText());
      assertTrue(payload.path("messages").path(1).path("content").asText().contains("UN3481"));
      assertNull(ledger.delivery("dg-docs:7"));
      return gatewayResponse("""
          {"un_numbers":["3481"],"missing_sds":false,"missing_declaration":false}
          """);
    });
    receiveDocument(7, null, "packing.txt",
        "Cheapest air freight from FRA to LAX. See the packing specification.",
        "Packing Specification\nDangerous goods: UN3481, Class 9");
    worker.work();
    worker.work();
    assertEquals("disagreed", ledger.jdbc().queryForObject(
        "SELECT state FROM dg_gateway_check WHERE request_id=7", String.class));
    assertEquals("waiting for documents", ledger.mail(7).get("stage"));
    assertEquals("sent", ledger.delivery("dg-docs:7"));
    assertNull(ledger.delivery("quote:7"));
    verify(upstream).parseRfq(anyMap());
    verify(upstream, never()).rates(any(), any());
  }

  @Test
  void sendsDangerousGoodsDocumentRequestAfterGatewayFailure() {
    when(upstream.parseRfq(anyMap())).thenThrow(new ResourceAccessException("Temporary gateway failure"));
    receiveDocument(7, null, "packing.txt",
        "Cheapest air freight from FRA to LAX. See the packing specification.",
        "Packing Specification\nDangerous goods: UN3481, Class 9");
    worker.work();
    worker.work();
    assertEquals("unavailable", ledger.jdbc().queryForObject(
        "SELECT state FROM dg_gateway_check WHERE request_id=7", String.class));
    assertEquals("waiting for documents", ledger.mail(7).get("stage"));
    assertEquals("sent", ledger.delivery("dg-docs:7"));
    assertNull(ledger.delivery("quote:7"));
    verify(upstream).parseRfq(anyMap());
  }

  @Test
  void followsDangerousGoodsDocumentsAcrossRepliesAndDeclinesOnce() throws Exception {
    when(upstream.parseRfq(anyMap())).thenReturn(gatewayResponse("""
        {"un_numbers":["1950"],"missing_sds":true,"missing_declaration":true}
        """));
    receiveDocument(
        7,
        null,
        "packing.txt",
        "Cheapest air freight FRA to LAX for 100 kg and 0.2 cbm.",
        "Packing List\nDangerous goods: UN1950, Class 2.1");
    worker.work();
    assertEquals("waiting for documents", ledger.mail(7).get("stage"));
    assertEquals("sent", ledger.delivery("dg-docs:7"));
    assertEquals("agreed", ledger.jdbc().queryForObject(
        "SELECT state FROM dg_gateway_check WHERE request_id=7", String.class));
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
    verify(upstream).parseRfq(anyMap());
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
