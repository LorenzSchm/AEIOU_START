package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class TranslatorTest {
  @TempDir Path temp;
  private HttpServer server;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private volatile String content;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      calls.incrementAndGet();
      lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] reply = new ObjectMapper()
          .writeValueAsBytes(java.util.Map.of("choices", List.of(java.util.Map.of("message", java.util.Map.of("content", content)))));
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, reply.length);
      exchange.getResponseBody().write(reply);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private Translator translator(String key) throws Exception {
    return new Translator(temp.resolve("dispatch.sqlite3").toString(),
        "http://127.0.0.1:" + server.getAddress().getPort(), key, "", "gpt-4o-mini", "", new ObjectMapper());
  }

  @Test
  void swapsParagraphsOneForOneAndKeepsQuotedHistory() throws Exception {
    content = "{\"source_language\":\"de\",\"paragraphs\":[\"Hello team,\",\"Please quote 120 kg MUC to JFK.\"]}";
    var result = translator("key").translate(new Translator.Request(
        List.of("Hallo Team,", "> Earlier mail", "Bitte 120 kg MUC nach JFK anbieten."), "en"));
    assertEquals(List.of("Hello team,", "> Earlier mail", "Please quote 120 kg MUC to JFK."), result.paragraphs());
    assertEquals("de", result.source());
    assertFalse(result.cached());
    assertFalse(lastBody.get().contains("Earlier mail"), "quoted history is not sent to the model");
  }

  @Test
  void answersRepeatRequestsFromTheCache() throws Exception {
    content = "{\"source_language\":\"fr\",\"paragraphs\":[\"Good morning\"]}";
    var request = new Translator.Request(List.of("Bonjour"), "en");
    translator("key").translate(request);
    var again = translator("key").translate(request);
    assertTrue(again.cached());
    assertEquals(1, calls.get());
    assertTrue(Files.exists(temp.resolve("translations.sqlite3")));
    assertFalse(Files.exists(temp.resolve("dispatch.sqlite3")), "never touches the dispatch database");
  }

  @Test
  void rejectsAnswersWithADifferentParagraphCount() throws Exception {
    content = "{\"source_language\":\"de\",\"paragraphs\":[\"Hello team, please quote.\"]}";
    var error = assertThrows(ResponseStatusException.class, () -> translator("key")
        .translate(new Translator.Request(List.of("Hallo Team,", "bitte anbieten."), "en")));
    assertEquals(502, error.getStatusCode().value());
  }

  @Test
  void explainsAMissingKey() throws Exception {
    var error = assertThrows(ResponseStatusException.class, () -> translator("")
        .translate(new Translator.Request(List.of("Hallo"), "en")));
    assertEquals(503, error.getStatusCode().value());
    assertEquals(0, calls.get());
  }
}
