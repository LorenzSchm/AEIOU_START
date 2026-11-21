package ai.aeiou.forwarder.ports;

import com.fasterxml.jackson.databind.JsonNode;

public interface Organizer {
  JsonNode call(String method, String path, JsonNode body);

  class Failure extends RuntimeException {
    public final int status;
    public final int retryAfter;

    public Failure(int status, int retryAfter) {
      super("Organizer request failed (" + status + ")");
      this.status = status;
      this.retryAfter = retryAfter;
    }
  }
}
