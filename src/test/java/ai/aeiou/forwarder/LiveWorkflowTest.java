package ai.aeiou.forwarder;

import static ai.aeiou.forwarder.domain.Json.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.forwarder.application.*;
import ai.aeiou.forwarder.infrastructure.Ledger;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ActiveProfiles("live")
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:livestub;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "forwarder.ui-token=test-ui",
      "WEBHOOK_SIGNING_SECRET=local-fixture-secret",
      "forwarder.team-key=test-team",
      "forwarder.model=test-model",
      "forwarder.demo.tick-ms=250"
    })
class LiveWorkflowTest {
  private static final Instant START = Instant.parse("2026-11-21T15:00:00Z");
  private static final AtomicLong offset = new AtomicLong(), nextId = new AtomicLong(100);
  private static final List<JsonNode> inbox = new CopyOnWriteArrayList<>(),
      sent = new CopyOnWriteArrayList<>();
  private static final AtomicInteger bookings = new AtomicInteger(), gateway = new AtomicInteger();
  private static final Queue<String> violations = new ConcurrentLinkedQueue<>();
  private static final AtomicReference<JsonNode> board =
      new AtomicReference<>(obj("requests", arr()));
  private static final Map<String, String> packingFiles = new ConcurrentHashMap<>();
  private static final HttpServer upstream = start();
  @Autowired Worker worker;
  @Autowired LiveIntake intake;
  @Autowired Ledger store;
  @Autowired ai.aeiou.forwarder.api.MailboxWebhook webhook;

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) {
    registry.add("forwarder.api-root", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    Clock testClock() {
      return new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return this;
        }

        public Instant instant() {
          return START.plusSeconds(offset.get());
        }
      };
    }
  }

  static HttpServer start() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", LiveWorkflowTest::respond);
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void respond(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
    var headers = ex.getRequestHeaders();
    if (path.startsWith("/v1/gateway/") && !path.endsWith("openai-key")) {
      if (!"Bearer test-team".equals(headers.getFirst("Authorization")))
        violations.add("Missing gateway bearer");
    } else if (!"test-team".equals(headers.getFirst("X-API-Key")))
      violations.add("Missing API header");
    JsonNode result = obj();
    int status = 200;
    if (packingFiles.containsKey(path)) {
      byte[] data = packingFiles.get(path).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, data.length);
      try (var out = ex.getResponseBody()) {
        out.write(data);
      }
      return;
    }
    switch (path) {
      case "/v1/mailbox/messages/700/attachments/1" -> {
        byte[] file =
            "Packing specification\nDangerous goods: UN1133, Class 3, Adhesives"
                .getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, file.length);
        try (var out = ex.getResponseBody()) {
          out.write(file);
        }
        return;
      }
      case "/v1/me" -> result = obj("mailbox", "aeiou@hackloop.mail");
      case "/v1/gateway/openai-key" -> {
        if (!method.equals("GET")) violations.add("Provisioning attempted");
        result = obj("registered", true);
      }
      case "/v1/gateway/models" -> result = obj("data", arr(obj("id", "test-model")));
      case "/v1/portal/ports" ->
          result =
              obj(
                  "lanes",
                  arr(
                      obj(
                          "origin",
                          "MUC",
                          "destination",
                          "LAX",
                          "airlines",
                          arr(obj("code", "YT", "name", "Yellowtail Air Freight"))),
                      obj(
                          "origin",
                          "MUC",
                          "destination",
                          "CAI",
                          "airlines",
                          arr(obj("code", "BX", "name", "Borealis Freight Airways")))));
      case "/v1/portal/brackets" ->
          result =
              ex.getRequestURI().getQuery().contains("airline=BX")
                  ? obj("airline_code", "BX", "breaks_kg", arr(1000, 300, 500))
                  : obj("airline_code", "YT", "breaks_kg", arr(0, 45, 100));
      case "/v1/gateway/chat/completions" -> {
        gateway.incrementAndGet();
        String prompt = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (prompt.contains("simulate gateway timeout")) status = 504;
        result =
            obj(
                "choices",
                arr(obj("message", obj("content", "{}"))),
                "usage",
                obj("prompt_tokens", 75, "completion_tokens", 10));
      }
      case "/v1/portal/rates" -> {
        if (ex.getRequestURI().getQuery().contains("destination=CAI")) {
          if (!ex.getRequestURI().getQuery().contains("weight_kg=486&"))
            violations.add("Incorrect gross weight in portal");
          result =
              obj(
                  "origin",
                  "MUC",
                  "destination",
                  "CAI",
                  "airline",
                  "Borealis Freight Airways",
                  "airline_code",
                  "BX",
                  "weight_kg",
                  "486",
                  "chargeable_weight_kg",
                  "486",
                  "currency",
                  "EUR",
                  "total",
                  "1642.68",
                  "transit_days",
                  4,
                  "rate_id",
                  "R-cai486",
                  "valid_until",
                  START.plusSeconds(1500).toString());
        } else
          result =
              obj(
                  "origin",
                  "MUC",
                  "destination",
                  "LAX",
                  "weight_kg",
                  "100",
                  "airline",
                  "Yellowtail Air Freight",
                  "airline_code",
                  "YT",
                  "chargeable_weight_kg",
                  "100",
                  "total",
                  "300.00",
                  "currency",
                  "EUR",
                  "transit_days",
                  2,
                  "rate_id",
                  "R-test12345",
                  "valid_until",
                  START.plusSeconds(900).toString(),
                  "dataset_version",
                  "stub");
      }
      case "/v1/portal/bookings" -> {
        var b = parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!str(b, "rate_id").equals("R-test12345") || !str(b, "team_reference").equals("AE-1"))
          violations.add("Booking identity changed");
        bookings.incrementAndGet();
        status = 201;
        result =
            obj(
                "booking_reference",
                "BK-ABC12345",
                "rate_id",
                "R-test12345",
                "team_reference",
                "AE-1",
                "price",
                "300.00",
                "currency",
                "EUR");
      }
      case "/v1/mailbox/messages" -> {
        if (method.equals("POST")) {
          var m =
              (com.fasterxml.jackson.databind.node.ObjectNode)
                  parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          m.put("id", nextId.incrementAndGet());
          sent.add(m);
          result = m;
          status = 201;
        } else {
          long cursor = Long.parseLong(ex.getRequestURI().getQuery().split("[=&]")[1]);
          var messages = inbox.stream().filter(m -> m.get("id").asLong() > cursor).toList();
          result =
              obj(
                  "messages",
                  messages,
                  "next_since_id",
                  messages.isEmpty() ? cursor : messages.getLast().get("id").asLong());
        }
      }
      case "/v1/board/me" -> result = board.get();
      default -> {
        violations.add("Unexpected path " + path);
        status = 404;
      }
    }
    byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (var out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  @AfterAll
  static void stop() {
    upstream.stop(0);
  }

  static JsonNode inbound(long id, String sender, String body, Long parent) {
    return obj(
        "id",
        id,
        "from",
        sender,
        "to",
        "aeiou@hackloop.mail",
        "subject",
        "RFQ AE-1",
        "body",
        body,
        "sent_at",
        START.plusSeconds(offset.get()).toString(),
        "in_reply_to",
        parent,
        "attachments",
        arr());
  }

  void waitFor(java.util.function.BooleanSupplier check) {
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () -> {
              intake.poll();
              intake.routePending();
              worker.tick();
              return check.getAsBoolean();
            });
  }

  @Test
  @Order(1)
  void readQuoteBookAndTrackThroughHttpUpstream() {
    long startNanos = System.nanoTime();
    inbox.add(
        inbound(
            1,
            "buyer@hackloop.mail",
            "Please quote cheapest airport-to-airport MUC to LAX. 100 kg total, 0.2 cbm.",
            null));
    await()
        .atMost(Duration.ofSeconds(12))
        .until(() -> sent.stream().anyMatch(m -> str(m, "to").equals("buyer@hackloop.mail")));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    System.out.println("Local scheduled mailbox-to-first-quote: " + elapsedMs + " ms");
    assertEquals(0, offset.get(), "No advance to the 45-second fallback");
    assertEquals(1, gateway.get());
    var quote =
        sent.stream()
            .filter(m -> str(m, "to").equals("buyer@hackloop.mail"))
            .findFirst()
            .orElseThrow();
    assertEquals(1, quote.path("in_reply_to").asInt());
    assertTrue(str(quote, "body").contains("300.00 EUR"));
    inbox.add(
        inbound(
            200,
            "buyer@hackloop.mail",
            "Thank you for your offer. That works for us, please go ahead and book it.",
            quote.get("id").asLong()));
    waitFor(() -> store.shipment("s_1").at("/booking/state").asText().equals("ACKNOWLEDGED"));
    inbox.add(
        inbound(
            201,
            "yellowtail-air-freight@hackloop.mail",
            "Your booking BK-ABC12345 is confirmed. Your reference: AE-1.",
            null));
    waitFor(() -> store.shipment("s_1").at("/session/phase").asText().equals("BOOKED"));
    offset.set(50);
    inbox.add(
        inbound(
            202,
            "yellowtail-air-freight@hackloop.mail",
            "BK-ABC12345\n"
                + "Departed from MUC at 2026-11-21T15:00:49Z\n"
                + "Flight: YT123\n"
                + "ETA: 2026-11-23T06:00Z",
            null));
    inbox.add(
        inbound(
            203,
            "buyer@hackloop.mail",
            "What is the latest tracking status?",
            quote.get("id").asLong()));
    waitFor(
        () ->
            sent.stream()
                .anyMatch(
                    m ->
                        m.path("in_reply_to").asLong() == 203
                            && str(m, "body").contains("Departed from MUC")
                            && str(m, "body").contains("BK-ABC12345")));
    var desk = "tracking@hackloop.mail";
    var q =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            inbound(
                204,
                desk,
                "What is the gross weight, airline booking price, flight number and ETA for"
                    + " bk-abc12345?",
                null);
    q.put("subject", "Shipment details");
    inbox.add(q);
    inbox.add(inbound(205, desk, "Where is BK-ABC123450?", quote.get("id").asLong()));
    var unrelated =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            inbound(206, desk, "Please quote a shipment.", null);
    unrelated.put("subject", "General question");
    inbox.add(unrelated);
    waitFor(() -> sent.stream().anyMatch(m -> m.path("in_reply_to").asLong() == 204));
    var answer =
        sent.stream().filter(m -> m.path("in_reply_to").asLong() == 204).findFirst().orElseThrow();
    assertEquals(desk, str(answer, "to"));
    assertEquals("Re: Shipment details", str(answer, "subject"));
    for (var fact :
        List.of("100 kg", "300.00 EUR", "YT123", "2026-11-23T06:00Z", "Departed from MUC"))
      assertTrue(str(answer, "body").contains(fact), fact);
    intake.receivePush(q);
    intake.routePending();
    worker.tick();
    assertEquals(1, sent.stream().filter(m -> m.path("in_reply_to").asLong() == 204).count());
    assertFalse(
        sent.stream().anyMatch(m -> Set.of(205L, 206L).contains(m.path("in_reply_to").asLong())));
    assertEquals(1, store.db.queryForObject("select count(*) from shipment", Integer.class));
    assertEquals(1, bookings.get());
    assertEquals(1, gateway.get());
    assertEquals(List.of(), List.copyOf(violations));
  }

  @Test
  @Order(4)
  void expiredRfqConsumesNoGatewayCall() {
    int before = gateway.get();
    var old =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            inbound(
                500,
                "late-buyer@hackloop.mail",
                "Quote cheapest airport-to-airport MUC to LAX, 100 kg and 0.2 cbm.",
                null);
    old.put("subject", "Expired RFQ").put("sent_at", START.minusSeconds(400).toString());
    inbox.add(old);
    waitFor(
        () ->
            "CANCELLED"
                .equals(
                    store
                        .db
                        .queryForList(
                            "select state from job where id='s_500:extract:1'", String.class)
                        .stream()
                        .findFirst()
                        .orElse("")));
    assertEquals("CLOSED", store.shipment("s_500").at("/session/phase").asText());
    assertEquals(before, gateway.get());
    assertFalse(sent.stream().anyMatch(m -> str(m, "to").equals("late-buyer@hackloop.mail")));
  }

  @Test
  @Order(5)
  void importsOrganizerRoundsAndRefreshesDelayedCost() {
    var row =
        obj(
            "round_no",
            10,
            "verdict",
            "accepted",
            "sent_at",
            START.toString(),
            "points",
            obj("accuracy", 42.5, "time", 15, "tracking", 0),
            "total",
            57.5);
    board.set(obj("requests", arr(row)));
    intake.board();
    var score = store.read("select data from score_row where round_id='10'").getFirst();
    assertEquals("42.5", str(score, "accuracyPoints"));
    assertEquals("0", str(score, "trackingPoints"));
    assertFalse(score.has("costPoints"));
    row.withObject("/points").put("cost", 32).put("adjustment", -1);
    row.put("total", 88.5);
    board.set(obj("requests", arr(row)));
    intake.board();
    var scores = store.read("select data from score_row where round_id='10'");
    assertEquals(1, scores.size());
    assertEquals("32", str(scores.getFirst(), "costPoints"));
    assertEquals("-1", str(scores.getFirst(), "adjustmentPoints"));
    assertEquals("88.5", str(scores.getFirst(), "totalPoints"));
  }

  @Test
  @Order(6)
  void replacesAnAirlineThatRefusesTheLane() {
    inbox.add(
        inbound(
            600,
            "lane-buyer@hackloop.mail",
            "Fastest freight option to JFK. Ready for pickup on 2026-11-25 from 61018. 6 pieces,"
                + " 1233 kg and 10.78 cbm.",
            null));
    waitFor(
        () ->
            sent.stream()
                    .filter(
                        m ->
                            str(m, "body").contains("AE-600")
                                && str(m, "body").contains("booking instructions for"))
                    .count()
                == 5);
    assertTrue(
        sent.stream()
            .anyMatch(
                m ->
                    str(m, "to").equals("wayzata-air-cargo@hackloop.mail")
                        && str(m, "body").contains("AE-600")
                        && str(m, "body").contains("booking instructions for")),
        "Published FRA-JFK tariff should place Wayzata in the first invitation group");
    var original =
        sent.stream()
            .filter(
                m ->
                    str(m, "to").equals("cobalt-skylift@hackloop.mail")
                        && str(m, "body").contains("AE-600"))
            .findFirst()
            .orElseThrow();
    inbox.add(
        inbound(
            601,
            str(original, "to"),
            "We do not serve this lane/zone: FRA-JFK. We fly DUS-JFK.",
            original.path("id").asLong()));
    waitFor(
        () ->
            sent.stream()
                    .filter(
                        m ->
                            str(m, "body").contains("AE-600")
                                && str(m, "body").contains("booking instructions for"))
                    .count()
                == 6);
    assertEquals(
        1,
        sent.stream()
            .filter(
                m -> str(m, "to").equals(str(original, "to")) && str(m, "body").contains("AE-600"))
            .count());
  }

  @Test
  @Order(7)
  void asksForDocumentsWhenPackingListContradictsEmail() {
    var mail =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            inbound(
                700,
                "docs-buyer@hackloop.mail",
                "Cheapest airport-to-airport MUC to LAX. 100 kg gross, 0.2 cbm. General cargo,"
                    + " non-dangerous. Packing list attached.",
                null);
    mail.withArray("attachments")
        .add(obj("id", 1, "filename", "packing.txt", "size", 67, "content_type", "text/plain"));
    inbox.add(mail);
    waitFor(() -> sent.stream().anyMatch(m -> str(m, "to").equals("docs-buyer@hackloop.mail")));
    var answer =
        sent.stream()
            .filter(m -> str(m, "to").equals("docs-buyer@hackloop.mail"))
            .findFirst()
            .orElseThrow();
    for (String phrase : List.of("UN1133", "declaration", "safety data sheet"))
      assertTrue(str(answer, "body").contains(phrase));
    assertEquals(700, answer.path("in_reply_to").asLong());
    offset.addAndGet(46);
    worker.tick();
    assertFalse(store.shipment("s_700").has("quote"));
    assertEquals(
        1, sent.stream().filter(m -> str(m, "to").equals("docs-buyer@hackloop.mail")).count());
  }

  @Test
  @Order(8)
  void postalTrackingRequiresAUniqueBookedShipmentWithTheReportedWeight() {
    for (int kg : List.of(1952, 730)) {
      var shipment = store.shipment("s_1").deepCopy();
      shipment
          .withObject("/session")
          .put("id", "s_postal_" + kg)
          .put("origin", "FRA")
          .put("destination", "LOS");
      shipment.withObject("/inputs").put("pickupPostcode", "36627").put("grossKg", kg);
      shipment.withObject("/booking").put("bookingReference", "BK-LOCAL" + kg);
      store.save(shipment);
    }
    var weights = List.of("1952 kg", "731 kg", "weight unknown");
    for (int i = 0; i < weights.size(); i++) {
      var question =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              inbound(
                  800 + i,
                  "tracking@hackloop.mail",
                  "Please give us the latest status. " + weights.get(i),
                  null);
      question.put("subject", "Status of shipment 36627 to LOS");
      inbox.add(question);
    }
    waitFor(() -> sent.stream().anyMatch(m -> m.path("in_reply_to").asLong() == 800));
    assertEquals(
        "s_postal_1952",
        store.db.queryForObject("select shipment_id from mail where id='m_800'", String.class));
    assertEquals(
        0,
        store.db.queryForObject(
            "select count(*) from mail where id in ('m_801','m_802')", Integer.class));
  }

  @Test
  @Order(9)
  void buysConfirmedBillingBreakForRealManifestAndBooksOnceByReference() {
    int calls = gateway.get(), portalBookings = bookings.get();
    String buyer = "break-buyer@hackloop.mail", airline = "borealis-freight-airways@hackloop.mail";
    inbox.add(
        inbound(
            900,
            buyer,
            "Cheapest airport-to-airport MUC to CAI. We will deliver it to MUC ourselves.\n"
                + "Lot 1: 9 pieces, 345 kg gross, 0.58 cbm\n"
                + "Lot 2: 2 pieces, 141 kg gross, 2.04 cbm\n"
                + "One all-in price for both lots under one air waybill.",
            null));
    waitFor(
        () ->
            sent.stream()
                .anyMatch(
                    m ->
                        str(m, "to").equals(airline)
                            && str(m, "body").contains("billing at 500 kg")));
    var inquiry =
        sent.stream()
            .filter(m -> str(m, "body").contains("billing at 500 kg"))
            .findFirst()
            .orElseThrow();
    long thread = inquiry.path("id").asLong();
    assertTrue(str(inquiry, "body").contains("gross weight 486 kg"));
    inbox.add(
        inbound(
            901,
            airline,
            "500 kg gross, 486 kg billed. All-in 1200 EUR, 4 days. R-WRONG.",
            thread));
    inbox.add(
        inbound(
            902,
            airline,
            "Confirmed: 486 kg gross, 500 kg billed. All-in 1475.00 EUR, 4 days. Book with"
                + " R-BUY500.",
            thread));
    waitFor(
        () ->
            store
                .shipment("s_900")
                .path("candidates")
                .valueStream()
                .anyMatch(c -> str(c, "carrierReference").equals("R-BUY500")));
    assertFalse(
        store
            .shipment("s_900")
            .path("candidates")
            .valueStream()
            .anyMatch(c -> str(c, "carrierReference").equals("R-WRONG")));
    waitFor(() -> sent.stream().anyMatch(m -> str(m, "to").equals(buyer)));
    var quote = sent.stream().filter(m -> str(m, "to").equals(buyer)).findFirst().orElseThrow();
    assertTrue(str(quote, "body").contains("1475.00 EUR"));
    inbox.add(inbound(903, buyer, "Please go ahead and book.", quote.path("id").asLong()));
    waitFor(() -> sent.stream().anyMatch(m -> str(m, "body").contains("Please book R-BUY500")));
    var booked =
        sent.stream().filter(m -> str(m, "body").contains("Please book R-BUY500")).toList();
    assertEquals(1, booked.size());
    assertEquals(902, booked.getFirst().path("in_reply_to").asInt());
    assertTrue(str(booked.getFirst(), "body").contains("gross 486 kg, billed 500 kg"));
    assertEquals(portalBookings, bookings.get());
    assertEquals("486", store.shipment("s_900").at("/inputs/grossKg").asText());
    assertEquals(calls + 1, gateway.get());
    assertTrue(violations.isEmpty(), violations.toString());
  }

  @Test
  @Order(10)
  void gatewayTimeoutDoesNotRepeatPaidExtractionAndStandardPriceStillWorks() {
    int calls = gateway.get();
    String buyer = "fallback-buyer@hackloop.mail";
    inbox.add(
        inbound(
            950,
            buyer,
            "Cheapest airport-to-airport MUC to CAI. 486 kg gross, 2.62 cbm. Please simulate"
                + " gateway timeout.",
            null));
    waitFor(
        () ->
            "UNCERTAIN"
                .equals(
                    store
                        .db
                        .queryForList(
                            "select state from job where id='s_950:extract:1'", String.class)
                        .stream()
                        .findFirst()
                        .orElse("")));
    waitFor(() -> store.shipment("s_950").at("/upstreamRates/R-cai486").isObject());
    offset.addAndGet(46);
    waitFor(() -> sent.stream().anyMatch(m -> str(m, "to").equals(buyer)));
    assertEquals("1642.68", store.shipment("s_950").at("/quote/total/amount").asText());
    assertEquals(calls + 1, gateway.get());
    assertTrue(violations.isEmpty(), violations.toString());
  }

  private void customerFile(long id, String buyer, String body, Long parent, String document) {
    packingFiles.put("/v1/mailbox/messages/" + id + "/attachments/1", document);
    var mail = (com.fasterxml.jackson.databind.node.ObjectNode) inbound(id, buyer, body, parent);
    mail.withArray("attachments")
        .add(
            obj(
                "id",
                1,
                "filename",
                "shipment.txt",
                "content_type",
                "text/plain",
                "size",
                document.length()));
    inbox.add(mail);
  }

  private JsonNode replyTo(long parent) {
    return sent.stream()
        .filter(m -> m.path("in_reply_to").asLong() == parent)
        .findFirst()
        .orElse(null);
  }

  @Test
  @Order(11)
  void correctsDisputedPackingBeforePricingAndUsesLabelledTotals() {
    String buyer = "packing-buyer@hackloop.mail";
    String hundred =
        "Packing List\n"
            + "Destination airport: LAX\n"
            + "Total gross weight (kg): 100\n"
            + "Total volume (cbm): 0.2\n"
            + "Dangerous goods: None";
    customerFile(
        1100,
        buyer,
        "Cheapest airport-to-airport MUC to LAX. 100 kg, 0.2 cbm.",
        null,
        hundred.replace("100", "200"));
    waitFor(() -> replyTo(1100) != null);
    assertTrue(str(replyTo(1100), "body").contains("corrected packing list"));
    assertFalse(store.shipment("s_1100").has("quote"));
    assertEquals(
        0,
        store.db.queryForObject(
            "select count(*) from job where shipment_id='s_1100' and action='PORTAL'",
            Integer.class));
    customerFile(
        1101,
        buyer,
        "Corrected packing list attached.",
        replyTo(1100).path("id").asLong(),
        hundred);
    waitFor(() -> store.shipment("s_1100").at("/upstreamRates/R-test12345").isObject());
    offset.addAndGet(46);
    waitFor(() -> store.shipment("s_1100").at("/quote/sendState").asText().equals("SENT"));
    assertEquals("100", store.shipment("s_1100").at("/inputs/grossKg").asText());
    customerFile(
        1102,
        "totals-buyer@hackloop.mail",
        "Cheapest air freight from MUC to LAX. Please use the packing list.",
        null,
        hundred);
    waitFor(() -> store.shipment("s_1102").at("/upstreamRates/R-test12345").isObject());
    offset.addAndGet(46);
    waitFor(() -> store.shipment("s_1102").at("/quote/sendState").asText().equals("SENT"));
    assertEquals("0.2", store.shipment("s_1102").at("/inputs/volumeCbm").asText());
  }

  @Test
  @Order(12)
  void followsMatchingCustomerDocumentsAndClosesOnlyOnce() {
    String buyer = "decl-buyer@hackloop.mail";
    customerFile(
        1200,
        buyer,
        "Cheapest airport-to-airport MUC to LAX. 100 kg, 0.2 cbm.",
        null,
        "Packing list\nDangerous goods: UN1950");
    waitFor(() -> replyTo(1200) != null);
    customerFile(
        1201,
        buyer,
        "Here is the safety data sheet.",
        replyTo(1200).path("id").asLong(),
        "Safety Data Sheet\nSection 14 Transport Information\nUN1950");
    waitFor(() -> replyTo(1201) != null);
    assertTrue(str(replyTo(1201), "body").contains("Section 14"));
    customerFile(
        1202,
        buyer,
        "Here is the declaration.",
        replyTo(1201).path("id").asLong(),
        "Shipper's Declaration for Dangerous Goods\nUN1950");
    waitFor(() -> replyTo(1202) != null);
    assertTrue(str(replyTo(1202), "body").contains("unable to offer"));
    assertEquals("CLOSED", store.shipment("s_1200").at("/session/phase").asText());
    assertFalse(store.shipment("s_1200").has("quote"));
    assertEquals(3, sent.stream().filter(m -> str(m, "to").equals(buyer)).count());
  }

  @Test
  @Order(13)
  void recoversMissingPickupForBothMissedRoundPatternsWithOneGatewayCallEach() {
    for (boolean forwarded : List.of(true, false)) {
      int id = forwarded ? 1500 : 2000;
      String dest = forwarded ? "SGN" : "RUH", buyer = "pickup-" + id + "@hackloop.mail";
      String body =
          forwarded
              ? "Cheapest air freight to SGN. Ready for pickup from our warehouse, 572 kg gross and"
                  + " 2.29 cbm. Please arrange the truck.\n\n"
                  + "-----Original Message-----\n"
                  + "Old shipment to HKG, postcode 93047, 165 kg and 11.8 cbm."
              : "Fastest air freight to RUH. 970 kg and 9.06 cbm. Ready for pickup. Arrange the"
                  + " truck from our warehouse.\n\n"
                  + "Cheers,\n"
                  + "Example, Nuremberg";
      var rfq = (com.fasterxml.jackson.databind.node.ObjectNode) inbound(id, buyer, body, null);
      rfq.put(
          "subject",
          forwarded ? "RE: FW: AMSTERDAM - HKG / 128 PCS 165 KGS" : "Air freight quote to RUH");
      int calls = gateway.get();
      inbox.add(rfq);
      waitFor(() -> replyTo(id) != null && gateway.get() == calls + 1);
      var question = replyTo(id);
      assertTrue(str(question, "body").contains("five-digit pickup postcode"));
      assertFalse(str(question, "body").contains("departure airport"));
      assertFalse(store.shipment("s_" + id).has("quote"));
      long parent = question.path("id").asLong();
      inbox.add(inbound(id + 1, "other@hackloop.mail", "Pickup postcode 60311.", parent));
      inbox.add(inbound(id + 2, buyer, forwarded ? "Sorry, it is 80287 Munich." : "80287", parent));
      String ref = "AE-" + id;
      var sentForCase = (java.util.function.Predicate<JsonNode>) m -> str(m, "body").contains(ref);
      waitFor(
          () ->
              sent.stream()
                      .filter(sentForCase)
                      .anyMatch(m -> str(m, "body").contains("trucking from postcode 80287"))
                  && sent.stream()
                      .filter(sentForCase)
                      .anyMatch(m -> str(m, "body").contains("booking instructions for")));
      var air =
          sent.stream()
              .filter(sentForCase)
              .filter(m -> str(m, "body").contains("booking instructions for"))
              .findFirst()
              .orElseThrow();
      var truck =
          sent.stream()
              .filter(sentForCase)
              .filter(m -> str(m, "body").contains("trucking from postcode"))
              .findFirst()
              .orElseThrow();
      assertTrue(str(air, "subject").contains("MUC to " + dest));
      assertFalse(str(air, "subject").contains("HKG"));
      assertEquals(
          forwarded ? "572" : "970", store.shipment("s_" + id).at("/inputs/grossKg").asText());
      inbox.add(
          inbound(
              id + 3,
              str(air, "to"),
              "All-in EUR 900. Estimated transit 2 days. Reference R-air.",
              air.path("id").asLong()));
      inbox.add(
          inbound(
              id + 4,
              str(truck, "to"),
              "All-in EUR 100. Transit from collection to airport 8 hours.",
              truck.path("id").asLong()));
      waitFor(
          () ->
              store
                  .shipment("s_" + id)
                  .path("candidates")
                  .valueStream()
                  .anyMatch(c -> c.has("airTotal")));
      offset.addAndGet(46);
      waitFor(() -> store.shipment("s_" + id).at("/quote/sendState").asText().equals("SENT"));
      assertEquals("1000.00", store.shipment("s_" + id).at("/quote/total/amount").asText());
      assertEquals(calls + 1, gateway.get());
      assertEquals(
          1,
          sent.stream()
              .filter(m -> str(m, "to").equals(buyer) && str(m, "body").contains("five-digit"))
              .count());
    }
  }

  @Test
  @Order(14)
  void asksOnlyForRemainingMissingFieldsAfterPartialCustomerAnswer() {
    String buyer = "partial@hackloop.mail";
    inbox.add(inbound(2100, buyer, "Please quote cheapest airport-to-airport MUC to LAX.", null));
    waitFor(() -> replyTo(2100) != null);
    var first = replyTo(2100);
    assertTrue(
        str(first, "body").contains("gross weight") && str(first, "body").contains("volume"));
    inbox.add(inbound(2101, buyer, "100 kg gross.", first.path("id").asLong()));
    waitFor(() -> replyTo(2101) != null);
    var remaining = replyTo(2101);
    assertTrue(str(remaining, "body").contains("total volume"));
    assertFalse(str(remaining, "body").contains("gross weight"));
    inbox.add(inbound(2102, buyer, "0.2 cbm.", remaining.path("id").asLong()));
    waitFor(() -> store.shipment("s_2100").at("/quote/sendState").asText().equals("SENT"));
    assertEquals("300.00", store.shipment("s_2100").at("/quote/total/amount").asText());
  }

  JsonNode solicitation(String to) {
    return sent.stream()
        .filter(m -> str(m, "to").equals(to) && str(m, "body").contains("AE-300"))
        .findFirst()
        .orElse(null);
  }

  @Test
  @Order(2)
  void pricesCompletePickupAndHandlesDecline() {
    String buyer = "sender@hackloop.mail",
        truck = "maintal-transport-logistik@hackloop.mail",
        air = "aurelia-air-cargo@hackloop.mail";
    inbox.add(
        inbound(
            300,
            buyer,
            "Quote the fastest air freight option to ORD. Arrange trucking from 51447 Bergisch"
                + " Gladbach. Ready for pickup on 2026-11-25. 12 pieces, 676 kg gross and 5.79"
                + " cbm.",
            null));
    waitFor(() -> solicitation(truck) != null && solicitation(air) != null && gateway.get() == 2);
    assertTrue(str(solicitation(truck), "body").contains("postcode 51447"));
    assertTrue(str(solicitation(air), "body").contains("CGN to ORD"));
    assertTrue(str(solicitation(air), "body").contains("ready on 2026-11-25"));
    inbox.add(
        inbound(
            301,
            air,
            "All-in 2388.32 EUR, estimated transit 2 days. Valid until 21 Nov 2026 15:16 UTC."
                + " Please quote reference R-local-A when booking.",
            solicitation(air).get("id").asLong()));
    inbox.add(
        inbound(
            302,
            truck,
            "All-in 100 EUR. Estimated time to pickup: 23 hours.",
            solicitation(truck).get("id").asLong()));
    waitFor(() -> sent.stream().anyMatch(m -> m.path("in_reply_to").asLong() == 302));
    assertTrue(store.shipment("s_300").path("pickupLegs").isEmpty());
    inbox.add(
        inbound(
            303,
            truck,
            "All-in trucking rate: 222.36 EUR. Transit time from pickup to delivery: 13 hours. Rate"
                + " valid until: 21 Nov 2026 15:14 UTC. No booking is required for pickup.",
            solicitation(truck).get("id").asLong()));
    waitFor(
        () ->
            store
                .shipment("s_300")
                .path("candidates")
                .valueStream()
                .anyMatch(c -> c.has("carrierReference") && c.has("airTotal")));
    var combined =
        store
            .shipment("s_300")
            .path("candidates")
            .valueStream()
            .filter(c -> c.has("carrierReference") && c.has("airTotal"))
            .findFirst()
            .orElseThrow();
    assertEquals("2610.68", combined.at("/quotedTotal/amount").asText());
    assertEquals(61, combined.get("transitHours").asInt());
    assertEquals("2026-11-21T15:14:00Z", str(combined, "validUntil"));
    assertFalse(
        store.shipment("s_300").at("/pickupLegs/pickup_m_303/bookingRequired").asBoolean(true));
    assertFalse(store.shipment("s_300").has("quote"));
    offset.addAndGet(2);
    waitFor(() -> sent.stream().anyMatch(m -> str(m, "to").equals(buyer)));
    var quote = sent.stream().filter(m -> str(m, "to").equals(buyer)).findFirst().orElseThrow();
    assertTrue(str(quote, "body").contains("including collection in 51447"));
    inbox.add(
        inbound(
            304,
            buyer,
            "Your price is too high for us, so we will not go ahead with it.",
            quote.get("id").asLong()));
    waitFor(() -> store.shipment("s_300").at("/session/phase").asText().equals("CLOSED"));
    assertFalse(store.shipment("s_300").has("booking"));
    assertEquals(List.of(), List.copyOf(violations));
  }

  @Test
  @Order(3)
  void signedPushAndPollShareInboxButOnlyPollAdvancesCursor() throws Exception {
    long cursor =
        store.db.queryForObject("select mail_cursor from stream_clock where id=1", Long.class);
    var mail = inbound(401, "westfalenroute@hackloop.mail", "Service notice", null);
    var bytes = M.writeValueAsBytes(obj("event", "mail.received", "message", mail));
    var headers = new org.springframework.http.HttpHeaders();
    headers.set("X-Webhook-Event", "mail.received");
    headers.set("X-Webhook-Id", "401");
    String stamp = Long.toString(START.plusSeconds(offset.get()).getEpochSecond());
    headers.set("X-Webhook-Timestamp", stamp);
    assertEquals(401, webhook.receive(headers, bytes).getStatusCode().value());
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(
        new javax.crypto.spec.SecretKeySpec(
            "local-fixture-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((stamp + ".").getBytes(StandardCharsets.UTF_8));
    headers.set("X-Webhook-Signature", "sha256=" + HexFormat.of().formatHex(mac.doFinal(bytes)));
    assertEquals(401, webhook.receive(headers, new byte[] {0}).getStatusCode().value());
    assertEquals(204, webhook.receive(headers, bytes).getStatusCode().value());
    assertEquals(204, webhook.receive(headers, bytes).getStatusCode().value());
    assertEquals(
        cursor,
        store.db.queryForObject("select mail_cursor from stream_clock where id=1", Long.class));
    inbox.addAll(
        List.of(inbound(400, "westfalenroute@hackloop.mail", "Earlier notice", null), mail));
    intake.poll();
    assertEquals(
        2,
        store.db.queryForObject(
            "select count(*) from inbox where upstream_id in (400,401)", Integer.class));
    assertEquals(
        401L,
        store.db.queryForObject("select mail_cursor from stream_clock where id=1", Long.class));
  }
}
