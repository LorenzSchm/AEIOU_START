package ai.aeiou.forwarder.adapters;

import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.ports.Organizer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!live")
public class DemoOrganizer implements Organizer {
  @Override
  public JsonNode call(String method, String path, JsonNode body) {
    if (method.equals("POST") && path.equals("/v1/mailbox/messages"))
      return obj("id", "demo-" + hash(body.toString()).substring(0, 20));
    if (method.equals("POST") && path.equals("/v1/portal/bookings"))
      return obj(
          "booking_reference",
          "DEMO-" + str(body, "team_reference"),
          "team_reference",
          body.get("team_reference"));
    throw new IllegalArgumentException("Unknown demo operation");
  }
}
