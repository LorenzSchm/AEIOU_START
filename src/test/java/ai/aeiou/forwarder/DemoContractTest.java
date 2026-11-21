package ai.aeiou.forwarder;

import static ai.aeiou.forwarder.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.aeiou.forwarder.application.Worker;
import ai.aeiou.forwarder.infrastructure.Ledger;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "forwarder.demo.tick-ms=600000"
    })
@AutoConfigureMockMvc
class DemoContractTest {
  @Autowired MockMvc http;
  @Autowired Worker worker;
  @Autowired Ledger store;
  private static final String BASE = "/api/v1";

  JsonNode getJson(String path) throws Exception {
    return parse(
        http.perform(get(BASE + path).header("Authorization", "Bearer demo"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  JsonNode postJson(String path, String key, JsonNode body, int status) throws Exception {
    return parse(
        http.perform(
                post(BASE + path)
                    .header("Authorization", "Bearer demo")
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body.toString()))
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void contractThroughTheNormalRoutes() throws Exception {
    assertArrayEquals(
        Files.readAllBytes(Path.of("docs/forwarder.openapi.json")),
        http.perform(get("/openapi.json")).andReturn().getResponse().getContentAsByteArray());
    http.perform(get(BASE + "/dashboard")).andExpect(status().isUnauthorized());
    http.perform(get(BASE + "/sessions").header("Authorization", "Bearer wrong"))
        .andExpect(status().isForbidden());
    var tariffs = getJson("/tariffs?limit=2");
    assertEquals(2, tariffs.path("items").size());
    assertEquals(8, getJson("/tariffs?cursor=" + str(tariffs, "nextCursor")).path("items").size());

    String tid =
        getJson("/tariffs")
            .path("items")
            .valueStream()
            .filter(t -> str(t, "carrier").equals("Yellowtail Air Freight"))
            .findFirst()
            .orElseThrow()
            .get("id")
            .asText();
    assertEquals(
        1, getJson("/tariffs/" + tid + "/lanes?origin=MUC&destination=LAX").path("items").size());
    assertTrue(getJson("/scores").path("items").isEmpty());
    assertEquals(1, getJson("/sessions?phase=SOURCING").path("items").size());
    String path = "/sessions/s_100", control = path + "/control";
    var d = getJson(path);
    var pause =
        obj(
            "expectedVersion",
            d.at("/session/version"),
            "action",
            "PAUSE",
            "reason",
            "Inspect the rate");
    var paused = postJson(control, "pause-key", pause, 200);
    assertEquals(paused, postJson(control, "pause-key", pause, 200));
    postJson(control, "stale-key", pause, 409);
    var yellow =
        d.path("candidates")
            .valueStream()
            .filter(c -> str(c, "provider").equals("Yellowtail Air Freight"))
            .findFirst()
            .orElseThrow();
    var pin =
        obj(
            "expectedVersion",
            paused.get("version"),
            "action",
            "SELECT_CANDIDATE",
            "candidateId",
            yellow.get("id"),
            "reason",
            "Select evidenced tariff");
    var selected = postJson(control, "select-key", pin, 200);
    postJson(
        control,
        "resume-key",
        obj(
            "expectedVersion",
            selected.get("version"),
            "action",
            "RESUME",
            "reason",
            "Resume worker"),
        200);
    for (int i = 0; i < 65; i++) worker.tick();
    d = getJson(path);
    assertEquals("BOOKED", d.at("/session/phase").asText(), d.toString());
    assertEquals("304.00", d.at("/quote/total/amount").asText());
    assertEquals("330.00", getJson("/sessions/s_101").at("/quote/total/amount").asText());
    assertEquals(2, getJson("/dashboard?window=EVENT").get("trackingAnswered").asInt());
    assertEquals(
        1,
        getJson(path + "/messages")
            .path("items")
            .valueStream()
            .filter(m -> str(m, "category").equals("QUOTE"))
            .count());
    assertFalse(getJson(path + "/events").path("items").isEmpty());
    http.perform(
            get(BASE + path + "/attachments/s_100_manifest").header("Authorization", "Bearer demo"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    http.perform(
            get(BASE + "/sessions/s_101/attachments/s_100_manifest")
                .header("Authorization", "Bearer demo"))
        .andExpect(status().isNotFound());
    var recorded =
        postJson("/replays", "record-key", obj("sessionId", "s_100", "scenario", "RECORDED"), 200);
    assertEquals(recorded.get("baseline"), recorded.get("simulated"));
    postJson(
        "/replays",
        "exclude-key",
        obj("sessionId", "s_100", "scenario", "EXCLUDE_SOURCE", "source", "TARIFF"),
        200);
    postJson(
        "/replays",
        "delay-key",
        obj(
            "sessionId",
            "s_101",
            "scenario",
            "DELAY_BID",
            "candidateId",
            "s_101_complete",
            "delaySeconds",
            180),
        200);
    postJson(
        "/replays",
        "bad-replay",
        obj("sessionId", "s_100", "scenario", "RECORDED", "source", "TARIFF"),
        422);
    http.perform(get(BASE + "/sessions?limit=0").header("Authorization", "Bearer demo"))
        .andExpect(status().isBadRequest());
    http.perform(get(BASE + "/events/stream?after=0").header("Authorization", "Bearer demo"))
        .andExpect(request().asyncStarted());
  }
}
