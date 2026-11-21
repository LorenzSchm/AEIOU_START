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
    switch (path) {
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
                          arr(obj("code", "YT", "name", "Yellowtail Air Freight")))));
      case "/v1/portal/brackets" ->
          result = obj("airline_code", "YT", "breaks_kg", arr(0, 45, 100));
      case "/v1/gateway/chat/completions" -> {
        gateway.incrementAndGet();
        result =
            obj(
                "choices",
                arr(obj("message", obj("content", "{}"))),
                "usage",
                obj("prompt_tokens", 75, "completion_tokens", 10));
      }
      case "/v1/portal/rates" ->
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
      case "/v1/board/me" -> result = obj("rounds", arr());
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
            "Booking confirmed for AE-1: BK-ABC12345.",
            null));
    waitFor(() -> store.shipment("s_1").at("/session/phase").asText().equals("BOOKED"));
    offset.set(50);
    inbox.add(
        inbound(
            202,
            "yellowtail-air-freight@hackloop.mail",
            "BK-ABC12345\nDeparted from MUC at 2026-11-21T15:00:49Z",
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
    assertEquals(1, bookings.get());
    assertEquals(1, gateway.get());
    assertEquals(List.of(), List.copyOf(violations));
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
