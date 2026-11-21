package ai.aeiou.forwarder.domain;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class Json {
  public static final ObjectMapper M = new ObjectMapper();

  private Json() {}

  public static ObjectNode obj(Object... pairs) {
    ObjectNode out = M.createObjectNode();
    for (int i = 0; i < pairs.length; i += 2) {
      Object value = pairs[i + 1];
      if (value != null)
        out.set((String) pairs[i], value instanceof JsonNode n ? n : M.valueToTree(value));
    }
    return out;
  }

  public static ArrayNode arr(Object... values) {
    ArrayNode a = M.createArrayNode();
    for (Object v : values) a.add(M.valueToTree(v));
    return a;
  }

  public static JsonNode parse(String json) {
    try {
      return M.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hash(String text) {
    return hash(text.getBytes(StandardCharsets.UTF_8));
  }

  public static String str(JsonNode n, String field) {
    return n.path(field).asText("");
  }

  public static ObjectNode evidence(
      String source, String id, String locator, String excerpt, String at) {
    return obj(
        "source", source, "sourceId", id, "locator", locator, "excerpt", excerpt, "observedAt", at);
  }
}
