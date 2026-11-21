package ai.aeiou.forwarder.adapters;

import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.ports.Organizer;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("live")
public class LiveOrganizer implements Organizer {
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final URI root;
  private final String key;

  public LiveOrganizer(
      @Value("${forwarder.api-root}") String root, @Value("${forwarder.team-key}") String key) {
    this.root = URI.create(root);
    this.key = key;
    if (key.isBlank()) throw new IllegalArgumentException("TEAM_API_KEY is required in live mode");
  }

  @Override
  public JsonNode call(String method, String path, JsonNode body) {
    if (!path.startsWith("/v1/") || path.contains("..") || path.contains("#"))
      throw new IllegalArgumentException("Invalid organizer path");
    var request =
        HttpRequest.newBuilder(root.resolve(path))
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "application/json");
    if (path.startsWith("/v1/gateway/") && !path.equals("/v1/gateway/openai-key"))
      request.header("Authorization", "Bearer " + key);
    else request.header("X-API-Key", key);
    if (method.equals("POST"))
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    else request.GET();
    try {
      var res = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() / 100 != 2)
        throw new Failure(
            res.statusCode(),
            res.headers()
                .firstValue("Retry-After")
                .filter(x -> x.matches("\\d{1,6}"))
                .map(Integer::parseInt)
                .orElse(3));
      if (path.contains("/attachments/")) return M.getNodeFactory().binaryNode(res.body());
      var json = M.readTree(res.body());
      if (path.startsWith("/v1/portal/rates?")
          && json instanceof com.fasterxml.jackson.databind.node.ObjectNode rate) {
        res.headers().firstValue("X-RateLimit-Limit").ifPresent(v -> rate.put("_quotaLimit", v));
        res.headers()
            .firstValue("X-RateLimit-Remaining")
            .ifPresent(v -> rate.put("_quotaRemaining", v));
      }
      return json;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failure(0, 3);
    } catch (java.io.IOException e) {
      throw new Failure(0, 3);
    }
  }
}
