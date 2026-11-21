package ai.aeiou.forwarder;

import static ai.aeiou.forwarder.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.forwarder.adapters.LiveOrganizer;
import ai.aeiou.forwarder.ports.Organizer;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LiveOrganizerTest {
  @Test
  void scopedCredentialsAndRetryAfter() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var auth = new AtomicReference<String>();
    var route = new AtomicReference<String>();
    server.createContext(
        "/",
        ex -> {
          route.set(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
          var headers = ex.getRequestHeaders();
          auth.set(headers.getFirst("Authorization") + "|" + headers.getFirst("X-API-Key"));
          byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Retry-After", "7");
          ex.sendResponseHeaders(
              ex.getRequestURI().getPath().endsWith("rates") ? 429 : 200, bytes.length);
          try (var out = ex.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    try {
      var client = new LiveOrganizer("http://127.0.0.1:" + server.getAddress().getPort(), "dummy");
      client.call("POST", "/v1/gateway/chat/completions", obj());
      assertEquals("POST /v1/gateway/chat/completions", route.get());
      assertEquals("Bearer dummy|null", auth.get());
      var failure =
          assertThrows(Organizer.Failure.class, () -> client.call("GET", "/v1/portal/rates", null));
      assertEquals(429, failure.status);
      assertEquals(7, failure.retryAfter);
      assertEquals("null|dummy", auth.get());
      assertThrows(
          IllegalArgumentException.class,
          () -> client.call("GET", "https://elsewhere.invalid", null));
    } finally {
      server.stop(0);
    }
  }
}
