package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MailboxIntegrationTest {
  @TempDir static Path temp;
  static final ObjectMapper json = new ObjectMapper();
  static HttpServer fixture;
  static final List<Map<String, Object>> out = new ArrayList<>(), in = new ArrayList<>();
  static int gatewayCalls;
  @LocalServerPort int port;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry props) throws IOException {
    in.add(
        Map.of(
            "id",
            1,
            "subject",
            "RFQ to LAX",
            "from",
            "buyer@hackloop.mail",
            "body",
            "Cheapest all-in freight option for one hundred kilograms, 0.2 cbm, pickup from"
                + " postcode 60311.",
            "sent_at",
            Instant.now().toString()));
    fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fixture.createContext(
        "/",
        ex -> {
          Object response;
          int status = 200;
          synchronized (out) {
            if (ex.getRequestURI().getPath().equals("/v1/gateway/chat/completions")) {
              var request = json.readTree(ex.getRequestBody());
              assertEquals("fixture", ex.getRequestHeaders().getFirst("X-API-Key"));
              assertEquals("gpt-4o-mini", request.path("model").asText());
              assertEquals("json_schema", request.path("response_format").path("type").asText());
              assertTrue(
                  request
                      .path("messages")
                      .get(1)
                      .path("content")
                      .asText()
                      .contains("one hundred kilograms"));
              gatewayCalls++;
              response =
                  Map.of(
                      "choices",
                      List.of(
                          Map.of(
                              "finish_reason",
                              "stop",
                              "message",
                              Map.of(
                                  "content",
                                  """
                                  {"origin":null,"destination":"LAX","weight_kg":100,"volume_cbm":0.2,"preference":"cheapest all-in freight option",
                                   "pickup_required":true,"pickup_zone":"11","needs_review":false}
                                  """))));
            } else if (ex.getRequestMethod().equals("POST")) {
              @SuppressWarnings("unchecked")
              var msg = (Map<String, Object>) json.readValue(ex.getRequestBody(), Map.class);
              out.add(msg);
              int id = 100 + 2 * out.size();
              if (msg.get("subject").toString().startsWith("Rate request")) {
                boolean truck = msg.get("subject").toString().contains("pickup");
                in.add(
                    Map.of(
                        "id",
                        id + 1,
                        "from",
                        msg.get("to"),
                        "subject",
                        "Carrier response",
                        "body",
                        truck
                            ? "Our all-in rate is 25.00 EUR. Transit time from pickup to delivery is 16 hours, and the rate"
                                + " is valid until "
                                + DateTimeFormatter.ofPattern("d MMM uuuu HH:mm 'UTC'", Locale.ENGLISH)
                                    .withZone(ZoneOffset.UTC).format(Instant.now().plusSeconds(900))
                                + "."
                            : "All-in EUR 70.00, transit 2 days.",
                        "in_reply_to",
                        id,
                        "sent_at",
                        Instant.now().toString()));
              }
              response = Map.of("id", id);
              status = 201;
            } else if (ex.getRequestURI().getPath().endsWith("/ports")) {
              response = Map.of("lanes", List.of());
            } else {
              int cursor = Integer.parseInt(ex.getRequestURI().getQuery().split("[=&]")[1]);
              var items =
                  in.stream().filter(m -> ((Number) m.get("id")).intValue() > cursor).toList();
              response =
                  Map.of(
                      "messages",
                      items,
                      "next_since_id",
                      items.isEmpty() ? cursor : items.getLast().get("id"));
            }
            byte[] bytes = json.writeValueAsBytes(response);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
          }
        });
    fixture.start();
    props.add("dispatch.db", () -> temp.resolve("dispatch.sqlite3").toString());
    props.add("dispatch.base-url", () -> "http://127.0.0.1:" + fixture.getAddress().getPort());
    props.add("dispatch.key", () -> "fixture");
    props.add("dispatch.start-at", () -> "2000-01-01T00:00:00Z");
    props.add("dispatch.poll-ms", () -> "200");
    props.add("dispatch.auto", () -> "true");
  }

  @AfterAll
  static void close() {
    if (fixture != null) fixture.stop(0);
  }

  @Test
  void scheduledWorkerSendsCompleteQuoteThroughHttp() throws Exception {
    var client = HttpClient.newHttpClient();
    var url = URI.create("http://127.0.0.1:" + port + "/api/v1/messages");
    var until = Instant.now().plusSeconds(15);
    while (Instant.now().isBefore(until)) {
      var res =
          client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString());
      for (var m : json.readTree(res.body())) {
        if (m.path("id").asInt() == 1
            && m.path("stage").asText().equals("sent")
            && m.path("delivery").asText().equals("sent")) {
          assertEquals(95, m.path("proposal").path("offer").path("total_eur").asDouble());
          assertTrue(m.path("proposal").path("body").asText().contains("2 days and 16 hours"));
          assertEquals(25, m.path("proposal").path("pickup_eur").asDouble());
          synchronized (out) {
            assertEquals(1, gatewayCalls);
            assertEquals(6, out.size());
            var reply = out.getLast();
            assertEquals(1, reply.get("in_reply_to"));
            assertEquals("Re: RFQ to LAX", reply.get("subject"));
            String body = reply.get("body").toString();
            assertEquals(m.path("proposal").path("body").asText(), body);
            assertEquals(1, body.split("EUR ", -1).length - 1);
            assertTrue(body.contains("EUR 95.00 all in"));
            assertTrue(body.contains("collection from postcode 60311"));
          }
          return;
        }
      }
      Thread.sleep(100);
    }
    fail("Scheduled Spring worker did not send the threaded quote");
  }
}
