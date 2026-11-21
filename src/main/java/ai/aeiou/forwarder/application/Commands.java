package ai.aeiou.forwarder.application;

import static ai.aeiou.forwarder.application.Workflow.*;
import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.api.Problem;
import ai.aeiou.forwarder.domain.Pricing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Commands {
  private final Workflow flow;

  public Commands(Workflow flow) {
    this.flow = flow;
  }

  @Transactional
  public JsonNode execute(String operation, String sessionId, String key, ObjectNode input) {
    if (key == null || key.length() < 8 || key.length() > 100)
      throw Problem.invalid("Idempotency-Key must contain 8–100 characters");
    String scope = operation + "/" + sessionId;
    var canonical = new TreeMap<String, JsonNode>();
    input.properties().forEach(e -> canonical.put(e.getKey(), e.getValue()));
    String digest = hash(M.valueToTree(canonical).toString());
    flow.store.lock();
    var prior =
        flow.store.db.queryForList(
            "select digest,response from command_result where scope=? and key_id=?", scope, key);
    if (!prior.isEmpty()) {
      if (!digest.equals(prior.getFirst().get("digest")))
        throw Problem.conflict("Key already used with different command");
      return parse((String) prior.getFirst().get("response"));
    }
    var d = flow.store.shipment(sessionId);
    JsonNode result = operation.equals("control") ? control(d, input) : replay(d, input);
    flow.store.db.update(
        "insert into command_result values(?,?,?,?)", scope, key, digest, result.toString());
    return result;
  }

  private JsonNode control(ObjectNode d, ObjectNode in) {
    var s = session(d);
    String action = str(in, "action"), reason = str(in, "reason");
    Instant at = flow.clock.now();
    if (!in.path("expectedVersion").isIntegralNumber() || reason.isBlank() || reason.length() > 240)
      throw Problem.infeasible("Version and reason are required");
    if (in.path("expectedVersion").asLong() != s.get("version").asLong())
      throw Problem.conflict("Session version changed");
    if (!action.equals("SELECT_CANDIDATE") && in.has("candidateId"))
      throw Problem.infeasible("candidateId applies only to selection");
    switch (action) {
      case "PAUSE" -> {
        if (str(s, "control").equals("PAUSED")) throw Problem.conflict("Already paused");
        s.put("control", "PAUSED");
      }
      case "RESUME" -> {
        if (!str(s, "control").equals("PAUSED")) throw Problem.conflict("Already autonomous");
        s.put("control", "AUTO");
      }
      case "SELECT_CANDIDATE" -> {
        String id = str(in, "candidateId");
        if (!str(s, "phase").equals("SOURCING")
            || !str(s, "control").equals("PAUSED")
            || d.has("quote")
            || !at.isBefore(deadline(d)))
          throw Problem.conflict("Selection requires paused sourcing");
        if (id.isBlank()
            || Pricing.choose(
                    d.withArray("candidates").valueStream().toList(),
                    str(s, "objective"),
                    at,
                    d.get("requestRevision").asInt(),
                    id)
                .isEmpty()) throw Problem.infeasible("Candidate ineligible or stale");
        d.put("pin", id);
      }
      default -> throw Problem.infeasible("Unknown control action");
    }
    flow.store.changed(d, "CONTROL", action + " — " + reason, at);
    if (action.equals("RESUME")
        && (d.has("pin")
            || !at.isBefore(Instant.parse(str(s, "receivedAt")).plusSeconds(flow.decisionSeconds))))
      flow.decide(d, at);
    return s;
  }

  private JsonNode replay(ObjectNode d, ObjectNode in) {
    if (!d.has("recorded")) throw Problem.conflict("Session has no recorded quote decision");
    var original = d.get("recorded");
    String scenario = str(in, "scenario"), id = str(in, "candidateId"), source = str(in, "source");
    Set<String> fields =
        switch (scenario) {
          case "RECORDED" -> Set.of("sessionId", "scenario");
          case "EXCLUDE_SOURCE" -> Set.of("sessionId", "scenario", "source");
          case "DELAY_BID" -> Set.of("sessionId", "scenario", "candidateId", "delaySeconds");
          default -> throw Problem.infeasible("Unknown replay scenario");
        };
    if (!fields.equals(
        in.properties().stream()
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet())))
      throw Problem.infeasible("Scenario fields do not match");
    if (scenario.equals("EXCLUDE_SOURCE") && !Set.of("TARIFF", "PORTAL", "BID").contains(source))
      throw Problem.infeasible("Invalid source");
    int delay = in.path("delaySeconds").asInt();
    if (scenario.equals("DELAY_BID")
        && (!in.path("delaySeconds").isIntegralNumber()
            || delay < 1
            || delay > 600
            || d.withArray("candidates")
                .valueStream()
                .noneMatch(c -> str(c, "id").equals(id) && str(c, "source").equals("BID"))))
      throw Problem.infeasible("Select a recorded bid and delay of 1–600 seconds");
    JsonNode simulated = original.deepCopy(), total = d.at("/quote/total");
    if (!scenario.equals("RECORDED")) {
      Instant normal = Instant.parse(str(original, "at")),
          last = deadline(d).minusSeconds(flow.reserveSeconds);
      var wakes = new TreeSet<Instant>();
      wakes.add(normal);
      if (last.isAfter(normal)) wakes.add(last);
      Map<String, Instant> arrivals = new HashMap<>();
      var observed = new LinkedHashMap<String, JsonNode>();
      for (var c : d.path("decisionInputs").path("candidates")) observed.put(str(c, "id"), c);
      for (var c : d.withArray("candidates")) observed.putIfAbsent(str(c, "id"), c);
      for (var c : observed.values()) {
        String cid = str(c, "id");
        Instant arrived =
            Instant.parse(str(d.get("arrival"), cid)).plusSeconds(cid.equals(id) ? delay : 0);
        arrivals.put(cid, arrived);
        if (arrived.isAfter(normal) && arrived.isBefore(last)) wakes.add(arrived);
      }
      total = null;
      for (Instant at : wakes) {
        var visible =
            observed.values().stream()
                .filter(
                    c ->
                        !str(c, "source").equals(source) && !arrivals.get(str(c, "id")).isAfter(at))
                .toList();
        var selected =
            Pricing.choose(
                visible,
                d.at("/session/objective").asText(),
                at,
                d.at("/quote/requestRevision").asInt(),
                "");
        if (selected.isPresent()) {
          simulated =
              flow.decision(
                  "QUOTE",
                  at,
                  "Best feasible simulated offer",
                  str(original, "cutoff"),
                  str(selected.get(), "id"),
                  null);
          total = selected.get().get("quotedTotal");
          break;
        }
        simulated =
            flow.decision(
                "CLOSE",
                at,
                "No complete simulated offer before the dispatch deadline",
                str(original, "cutoff"),
                null,
                null);
      }
    }
    return obj(
        "id",
        "replay_" + UUID.randomUUID(),
        "sessionId",
        sid(d),
        "scenario",
        scenario,
        "historicalCutoff",
        original.get("cutoff"),
        "baseline",
        original,
        "simulated",
        simulated,
        "baselineTotal",
        d.at("/quote/total"),
        "simulatedTotal",
        total,
        "explanation",
        scenario.equals("RECORDED")
            ? "Original immutable quote decision."
            : "Recomputed from recorded arrivals with the requested change.",
        "events",
        arr(
            obj(
                "sequence",
                original.get("cutoff"),
                "at",
                simulated.get("at"),
                "kind",
                "DECISION",
                "summary",
                simulated.get("reason"),
                "sessionId",
                sid(d))));
  }
}
