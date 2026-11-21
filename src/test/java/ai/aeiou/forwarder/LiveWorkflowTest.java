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

@ActiveProfiles("live")
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:livestub;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "forwarder.ui-token=test-ui",
      "forwarder.team-key=test-team",
      "forwarder.model=test-model",
      "forwarder.demo.tick-ms=120000"
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
  void readQuoteBookAndTrackThroughHttpUpstream() {
    inbox.add(
        inbound(
            1,
            "buyer@hackloop.mail",
            "Please quote cheapest airport-to-airport MUC to LAX. 100 kg total, 0.2 cbm.",
            null));
    waitFor(
        () ->
            store
                    .shipment("s_1")
                    .path("candidates")
                    .valueStream()
                    .anyMatch(c -> str(c, "source").equals("PORTAL"))
                && gateway.get() == 1);
    offset.set(91);
    waitFor(() -> store.shipment("s_1").at("/session/phase").asText().equals("QUOTED"));
    var quote =
        sent.stream()
            .filter(m -> str(m, "to").equals("buyer@hackloop.mail"))
            .findFirst()
            .orElseThrow();
    assertEquals(1, quote.path("in_reply_to").asInt());
    assertTrue(str(quote, "body").contains("300.00 EUR"));
    inbox.add(
        inbound(200, "buyer@hackloop.mail", "We accept your offer.", quote.get("id").asLong()));
    waitFor(() -> store.shipment("s_1").at("/booking/state").asText().equals("ACKNOWLEDGED"));
    inbox.add(
        inbound(
            201,
            "yellowtail-air-freight@hackloop.mail",
            "Booking confirmed for AE-1: BK-ABC12345.",
            null));
    waitFor(() -> store.shipment("s_1").at("/session/phase").asText().equals("BOOKED"));
    inbox.add(
        inbound(
            202,
            "buyer@hackloop.mail",
            "What is the latest tracking status?",
            quote.get("id").asLong()));
    waitFor(() -> sent.stream().anyMatch(m -> m.path("in_reply_to").asLong() == 202));
    assertEquals(1, bookings.get());
    assertEquals(1, gateway.get());
    assertEquals(List.of(), List.copyOf(violations));
  }
}
