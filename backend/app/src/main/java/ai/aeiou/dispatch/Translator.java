package ai.aeiou.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Translates mail for the operators on request. It calls OpenAI directly, never the scored 5U
 * gateway, and works on a read-only desk. Paragraphs go in and come back in the same order and
 * count so the desk can swap them one for one.
 *
 * <p>Translations are append-only and never change dispatch data, so every desk, including a
 * read-only one, stores them in the shared Postgres table {@code desk_translation}. The Postgres
 * mirror and the read-only replica only walk the dispatch SQLite tables, so they never touch it. A
 * local SQLite file next to the dispatch database backs it up when Postgres is unset or unreachable.
 */
@Component
class Translator implements AutoCloseable {
  static final int MAX_PARAGRAPHS = 120;
  static final int MAX_CHARS = 16_000;

  private final RestClient openai;
  private final JdbcTemplate cache;
  private final com.zaxxer.hikari.HikariDataSource cachePool;
  private final ObjectMapper json;
  private final String model;
  private final String key;
  private final String keyFile;
  private final Postgres postgres;
  private volatile boolean postgresReady;

  @org.springframework.beans.factory.annotation.Autowired
  Translator(
      @Value("${dispatch.db}") String db,
      @Value("${dispatch.openai-base-url:https://api.openai.com}") String base,
      @Value("${dispatch.openai-key:}") String key,
      @Value("${dispatch.transcription-key-file:backend/state/openai.key}") String keyFile,
      @Value("${dispatch.translation-model:gpt-4o-mini}") String model,
      @Value("${postgres.url:}") String postgresUrl,
      ObjectMapper json)
      throws Exception {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(45));
    this.openai = RestClient.builder().baseUrl(base).requestFactory(factory).build();
    this.key = key == null ? "" : key.strip();
    this.keyFile = keyFile;
    this.model = model;
    this.json = json;
    this.postgres = Postgres.of(postgresUrl);
    var file = Path.of(db).toAbsolutePath().resolveSibling("translations.sqlite3");
    Files.createDirectories(file.getParent());
    cachePool = SqlitePool.open(file, 5_000, 4, "translations");
    cache = new JdbcTemplate(cachePool);
    cache.execute("PRAGMA journal_mode=WAL");
    cache.execute(
        """
        CREATE TABLE IF NOT EXISTS translation(key TEXT PRIMARY KEY, target TEXT NOT NULL,
        source TEXT, paragraphs TEXT NOT NULL, model TEXT NOT NULL, created TEXT NOT NULL)
        """);
  }

  @Override
  public void close() {
    cachePool.close();
  }

  record Request(List<String> paragraphs, String target) {}

  record Result(String source, String target, List<String> paragraphs, boolean cached) {}

  Result translate(Request request) {
    var paragraphs = request == null || request.paragraphs() == null ? List.<String>of() : request.paragraphs();
    String target = request == null || request.target() == null || request.target().isBlank()
        ? "en"
        : request.target().strip().toLowerCase(Locale.ROOT);
    if (!target.matches("[a-z]{2}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target must be a two-letter language code.");
    if (paragraphs.isEmpty() || paragraphs.stream().anyMatch(Objects::isNull))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to translate.");
    if (paragraphs.size() > MAX_PARAGRAPHS
        || paragraphs.stream().mapToInt(String::length).sum() > MAX_CHARS)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "This message is too long to translate.");

    String cacheKey = hash(target, paragraphs);
    var hit = lookup(cacheKey, paragraphs.size());
    if (hit != null) return new Result(hit.source(), target, hit.paragraphs(), true);

    // Only paragraphs with letters need the model. Quoted history, numbers and blank blocks stay as they are.
    var indexes = new ArrayList<Integer>();
    var texts = new ArrayList<String>();
    for (int i = 0; i < paragraphs.size(); i++) {
      if (translatable(paragraphs.get(i))) {
        indexes.add(i);
        texts.add(paragraphs.get(i));
      }
    }
    var out = new ArrayList<>(paragraphs);
    String source = null;
    if (!texts.isEmpty()) {
      var answer = complete(texts, target);
      source = answer.source();
      for (int i = 0; i < indexes.size(); i++) out.set(indexes.get(i), answer.paragraphs().get(i));
    }
    store(cacheKey, target, source, out);
    return new Result(source, target, out, false);
  }

  private record Stored(String source, List<String> paragraphs) {}

  private Stored lookup(String key, int count) {
    if (postgres != null) {
      try (var c = postgres.open()) {
        ensurePostgres(c);
        try (var st = c.prepareStatement("SELECT source, paragraphs FROM desk_translation WHERE key=?")) {
          st.setString(1, key);
          try (var rs = st.executeQuery()) {
            if (rs.next()) {
              var stored = decode(rs.getString(1), rs.getString(2), count);
              if (stored != null) return stored;
            }
          }
        }
      } catch (Exception ignored) {
        // Fall back to the local cache.
      }
    }
    var rows = cache.queryForList("SELECT source, paragraphs FROM translation WHERE key=?", key);
    return rows.isEmpty() ? null
        : decode((String) rows.get(0).get("source"), (String) rows.get(0).get("paragraphs"), count);
  }

  private Stored decode(String source, String paragraphs, int count) {
    try {
      List<String> list = Arrays.asList(json.readValue(paragraphs, String[].class));
      return list.size() == count ? new Stored(source, list) : null;
    } catch (Exception e) {
      return null;
    }
  }

  /** Appends the translation. An existing row for the same text is kept, never overwritten. */
  private void store(String key, String target, String source, List<String> paragraphs) {
    String created = java.time.Instant.now().toString();
    String body;
    try {
      body = json.writeValueAsString(paragraphs);
    } catch (Exception e) {
      return;
    }
    try {
      cache.update(
          "INSERT OR IGNORE INTO translation(key,target,source,paragraphs,model,created) VALUES(?,?,?,?,?,?)",
          key, target, source, body, model, created);
    } catch (Exception ignored) {
      // The translation is still returned when the local cache cannot be written.
    }
    if (postgres == null) return;
    try (var c = postgres.open()) {
      ensurePostgres(c);
      try (var st = c.prepareStatement(
          "INSERT INTO desk_translation(key,target,source,paragraphs,model,created) VALUES(?,?,?,?,?,?)"
              + " ON CONFLICT (key) DO NOTHING")) {
        st.setString(1, key);
        st.setString(2, target);
        st.setString(3, source);
        st.setString(4, body);
        st.setString(5, model);
        st.setString(6, created);
        st.executeUpdate();
      }
    } catch (Exception ignored) {
      // Postgres is shared but optional for translations; the local copy remains.
    }
  }

  private void ensurePostgres(java.sql.Connection c) throws java.sql.SQLException {
    if (postgresReady) return;
    try (var st = c.createStatement()) {
      st.execute("CREATE TABLE IF NOT EXISTS desk_translation(key TEXT PRIMARY KEY, target TEXT NOT NULL,"
          + " source TEXT, paragraphs TEXT NOT NULL, model TEXT NOT NULL, created TEXT NOT NULL)");
    }
    postgresReady = true;
  }

  static boolean translatable(String paragraph) {
    String text = paragraph.strip();
    if (text.isEmpty()) return false;
    if (text.lines().allMatch(line -> line.strip().startsWith(">") || line.isBlank())) return false;
    return text.codePoints().filter(Character::isLetter).count() >= 2;
  }

  private record Answer(String source, List<String> paragraphs) {}

  private Answer complete(List<String> texts, String target) {
    String token = token();
    String language = Locale.forLanguageTag(target).getDisplayLanguage(Locale.ENGLISH);
    var schema = Map.of(
        "type", "object",
        "additionalProperties", false,
        "required", List.of("source_language", "paragraphs"),
        "properties", Map.of(
            "source_language", Map.of("type", "string",
                "description", "ISO 639-1 code of the main language of the input"),
            "paragraphs", Map.of("type", "array", "items", Map.of("type", "string"))));
    var payload = new LinkedHashMap<String, Object>();
    payload.put("model", model);
    payload.put("temperature", 0);
    payload.put("messages", List.of(
        Map.of("role", "system", "content",
            "You translate air freight emails for logistics operators into " + language + ". "
                + "The user sends a JSON array of paragraphs. Return exactly one translated string per input "
                + "paragraph, in the same order, keeping line breaks inside a paragraph. Keep airport and "
                + "postcode codes, references, numbers, units, prices, email addresses and names unchanged. "
                + "Return a paragraph that is already in " + language + " unchanged. Do not add, merge, "
                + "split or explain anything."),
        Map.of("role", "user", "content", write(Map.of("paragraphs", texts)))));
    payload.put("response_format", Map.of("type", "json_schema", "json_schema",
        Map.of("name", "translation", "strict", true, "schema", schema)));
    JsonNode response;
    try {
      response = openai.post()
          .uri("/v1/chat/completions")
          .header("Authorization", "Bearer " + token)
          .body(payload)
          .retrieve()
          .body(JsonNode.class);
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The translation service is unavailable. Try again.");
    }
    try {
      JsonNode parsed = json.readTree(response.path("choices").path(0).path("message").path("content").asText());
      var paragraphs = new ArrayList<String>();
      parsed.path("paragraphs").forEach(node -> paragraphs.add(node.asText()));
      if (paragraphs.size() != texts.size()) throw new IllegalStateException("paragraph count");
      String source = parsed.path("source_language").asText("").strip().toLowerCase(Locale.ROOT);
      return new Answer(source.matches("[a-z]{2}") ? source : null, paragraphs);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The translation came back incomplete. Try again.");
    }
  }

  private String token() {
    if (!key.isEmpty()) return key;
    try {
      if (keyFile != null && !keyFile.isBlank()) {
        String token = Files.readString(Path.of(keyFile)).strip();
        if (!token.isEmpty()) return token;
      }
    } catch (Exception ignored) {
      // Reported below.
    }
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Translation needs an OpenAI key. Set OPENAI_API_KEY in .env.");
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String hash(String target, List<String> paragraphs) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update((model + "\u0000" + target).getBytes(StandardCharsets.UTF_8));
      for (String paragraph : paragraphs) {
        digest.update((byte) 0);
        digest.update(paragraph.getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
