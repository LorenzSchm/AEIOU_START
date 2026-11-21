package ai.aeiou.forwarder.api;

import static ai.aeiou.forwarder.application.Workflow.*;
import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.application.*;
import ai.aeiou.forwarder.infrastructure.Ledger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ForwarderApi {
  final Ledger store;
  final TimelineClock clock;
  final Commands commands;
  final Worker worker;
  final LiveIntake live;
  final Pages pages;
  final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

  public ForwarderApi(
      Ledger store,
      TimelineClock clock,
      Commands commands,
      Worker worker,
      LiveIntake live,
      Pages pages) {
    this.store = store;
    this.clock = clock;
    this.commands = commands;
    this.worker = worker;
    this.live = live;
    this.pages = pages;
  }

  @PreDestroy
  void close() {
    streams.shutdownNow();
  }

  @GetMapping(value = "/openapi.json", produces = "application/json")
  public ClassPathResource contract() {
    return new ClassPathResource("forwarder.openapi.json");
  }

  @GetMapping("/health")
  public ResponseEntity<JsonNode> health() {
    boolean db = store.db.queryForObject("select 1", Integer.class) == 1;
    return ResponseEntity.status(worker.ready && db ? 200 : 503)
        .body(
            obj(
                "database",
                db ? "READY" : "OFFLINE",
                "worker",
                live.degraded() ? "DEGRADED" : worker.health,
                "upstream",
                clock.live
                    ? (live.degraded() ? "UNAVAILABLE" : "VERIFIED_AT_STARTUP")
                    : "DEMO_OFFLINE"));
  }

  @GetMapping("/api/v1/dashboard")
  @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
  public JsonNode dashboard(@RequestParam(defaultValue = "CURRENT_HOUR") String window) {
    if (!Set.of("CURRENT_HOUR", "EVENT").contains(window))
      throw Problem.invalid("Unknown dashboard window");
    var all = store.read("select data from shipment");
    Instant now = clock.now();
    Instant start =
        window.equals("EVENT")
            ? all.stream()
                .map(d -> Instant.parse(d.at("/session/receivedAt").asText()))
                .min(Comparator.naturalOrder())
                .orElse(now)
            : now.truncatedTo(ChronoUnit.HOURS);
    var rows =
        all.stream()
            .filter(d -> !Instant.parse(d.at("/session/receivedAt").asText()).isBefore(start))
            .toList();
    long tracked =
        store.read("select data from mail").stream()
            .filter(
                m ->
                    str(m, "category").equals("TRACKING")
                        && str(m, "direction").equals("OUT")
                        && !Instant.parse(str(m, "at")).isBefore(start))
            .count();
    var latencies =
        rows.stream()
            .filter(d -> d.at("/quote/sendState").asText().equals("SENT"))
            .map(
                d ->
                    Duration.between(
                            Instant.parse(d.at("/session/receivedAt").asText()),
                            Instant.parse(d.at("/quote/sentAt").asText(d.at("/quote/at").asText())))
                        .toSeconds())
            .sorted()
            .toList();
    var u = usage();
    long calls = 0, input = 0, output = 0;
    java.math.BigDecimal spend = java.math.BigDecimal.ZERO;
    int knownCost = 0;
    for (var d : all)
      for (var entry : d.path("gatewayLog"))
        if (!Instant.parse(str(entry, "at")).isBefore(start)) {
          calls++;
          input += entry.at("/usage/prompt_tokens").asLong();
          output += entry.at("/usage/completion_tokens").asLong();
          if (entry.path("spendUsd").asText("").matches("\\d+(?:\\.\\d+)?")) {
            spend = spend.add(new java.math.BigDecimal(str(entry, "spendUsd")));
            knownCost++;
          }
        }
    u.put("calls", calls).put("inputTokens", input).put("outputTokens", output);
    if (calls > 0 && calls == knownCost)
      u.put("spendUsd", spend.toPlainString()).put("costBasis", "REPORTED");
    long officiallyAccepted =
        rows.stream().filter(d -> d.at("/session/verdict").asText().equals("ACCEPTED")).count();
    String spendPer =
        officiallyAccepted > 0 && u.has("spendUsd")
            ? spend
                .divide(
                    java.math.BigDecimal.valueOf(officiallyAccepted),
                    6,
                    java.math.RoundingMode.HALF_UP)
                .toPlainString()
            : null;
    return obj(
        "mode",
        clock.live ? "LIVE" : "DEMO",
        "asOf",
        now.toString(),
        "snapshotCursor",
        Long.toString(store.cursor()),
        "windowStart",
        start.toString(),
        "received",
        rows.size(),
        "answered",
        latencies.size(),
        "accepted",
        rows.stream().filter(d -> d.has("booking")).count(),
        "booked",
        rows.stream().filter(d -> d.at("/booking/state").asText().equals("CONFIRMED")).count(),
        "expired",
        rows.stream()
            .filter(d -> d.at("/session/phase").asText().equals("CLOSED") && !d.has("quote"))
            .count(),
        "trackingAnswered",
        tracked,
        "usage",
        u,
        "spendPerAcceptedUsd",
        spendPer,
        "worker",
        live.degraded() ? "DEGRADED" : worker.health,
        "boardUpdatedAt",
        live.boardAt == null ? null : live.boardAt.toString(),
        "p95QuoteSeconds",
        latencies.isEmpty() ? null : latencies.get((int) Math.ceil(latencies.size() * .95) - 1));
  }

  private static void enumFilter(Map<String, String> p, String name, String values) {
    if (p.containsKey(name) && !Arrays.asList(values.split(" ")).contains(p.get(name)))
      throw Problem.invalid("Invalid " + name);
  }

  private static boolean match(JsonNode row, Map<String, String> p, String key) {
    return !p.containsKey(key) || p.get(key).equalsIgnoreCase(str(row, key));
  }

  @GetMapping("/api/v1/sessions")
  public JsonNode sessions(@RequestParam Map<String, String> p) {
    enumFilter(p, "phase", "RECEIVED SOURCING QUOTED ACCEPTED BOOKING BOOKED CLOSED");
    enumFilter(p, "control", "AUTO PAUSED");
    enumFilter(p, "verdict", "OPEN PENDING ACCEPTED REJECTED NO_ANSWER VOIDED UNKNOWN");
    String q = p.getOrDefault("q", "").toLowerCase(Locale.ROOT);
    return pages.page(
        "sessions",
        p,
        () ->
            store.read("select data from shipment").stream()
                .filter(
                    d -> {
                      var s = d.get("session");
                      return (str(s, "reference")
                              + " "
                              + str(s, "subject")
                              + " "
                              + str(s, "origin")
                              + " "
                              + str(s, "destination")
                              + " "
                              + d.at("/booking/bookingReference").asText(""))
                          .toLowerCase(Locale.ROOT)
                          .contains(q);
                    })
                .map(d -> d.get("session"))
                .filter(
                    s -> match(s, p, "phase") && match(s, p, "control") && match(s, p, "verdict"))
                .sorted(
                    Comparator.comparing((JsonNode s) -> str(s, "receivedAt"))
                        .thenComparing(s -> str(s, "id"))
                        .reversed())
                .toList());
  }

  @GetMapping("/api/v1/sessions/{id}")
  @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
  public JsonNode detail(@PathVariable String id) {
    var d = store.shipment(id);
    var out = obj("snapshotCursor", Long.toString(store.cursor()));
    for (var key :
        List.of(
            "session",
            "requestRevision",
            "facts",
            "candidates",
            "latestDecision",
            "quote",
            "booking",
            "milestones",
            "usage")) if (d.has(key)) out.set(key, d.get(key));
    return out;
  }

  @GetMapping("/api/v1/sessions/{id}/messages")
  public JsonNode messages(@PathVariable String id, @RequestParam Map<String, String> p) {
    store.shipment(id);
    return pages.page(
        id + "/messages",
        p,
        () ->
            store.read("select data from mail where shipment_id=?", id).stream()
                .sorted(
                    Comparator.comparing((JsonNode m) -> Instant.parse(str(m, "at")))
                        .thenComparing(m -> str(m, "id")))
                .toList());
  }

  @GetMapping("/api/v1/sessions/{id}/events")
  public JsonNode events(@PathVariable String id, @RequestParam Map<String, String> p) {
    store.shipment(id);
    return pages.page(
        id + "/events",
        p,
        () -> store.read("select data from change_event where shipment_id=? order by seq", id));
  }

  @GetMapping("/api/v1/sessions/{id}/attachments/{aid}")
  public ResponseEntity<byte[]> attachment(@PathVariable String id, @PathVariable String aid) {
    return store
        .db
        .query(
            "select filename,bytes from attachment where id=? and shipment_id=?",
            (r, i) ->
                ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(
                        "Content-Disposition",
                        ContentDisposition.attachment()
                            .filename(r.getString(1), StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(r.getBytes(2)),
            aid,
            id)
        .stream()
        .findFirst()
        .orElseThrow(Problem::missing);
  }

  @PostMapping("/api/v1/sessions/{id}/control")
  public JsonNode control(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody ObjectNode body) {
    return commands.execute("control", id, key, body);
  }

  @PostMapping("/api/v1/replays")
  public JsonNode replay(
      @RequestHeader("Idempotency-Key") String key, @RequestBody ObjectNode body) {
    if (str(body, "sessionId").isBlank()) throw Problem.infeasible("sessionId required");
    return commands.execute("replay", str(body, "sessionId"), key, body);
  }

  @GetMapping("/api/v1/tariffs")
  public JsonNode tariffs(@RequestParam Map<String, String> p) {
    return pages.page(
        "tariffs",
        p,
        () ->
            store.read("select data from tariff").stream()
                .filter(t -> match(t, p, "carrier"))
                .sorted(
                    Comparator.comparing((JsonNode t) -> str(t, "ingestedAt"))
                        .thenComparing(t -> str(t, "id"))
                        .reversed())
                .toList());
  }

  @GetMapping("/api/v1/tariffs/{id}/lanes")
  public JsonNode lanes(@PathVariable String id, @RequestParam Map<String, String> p) {
    for (var key : List.of("origin", "destination"))
      if (p.containsKey(key) && !p.get(key).matches("[A-Z]{3}"))
        throw Problem.invalid("Invalid airport");
    return pages.page(
        id + "/lanes",
        p,
        () ->
            store.read("select lanes from tariff where id=?", id).stream()
                .findFirst()
                .orElseThrow(Problem::missing)
                .valueStream()
                .filter(l -> match(l, p, "origin") && match(l, p, "destination"))
                .map(
                    l -> {
                      var publicLane = (ObjectNode) l.deepCopy();
                      publicLane.remove(List.of("volumeFactor", "permitHigherBreak"));
                      return (JsonNode) publicLane;
                    })
                .toList());
  }

  @GetMapping("/api/v1/scores")
  public JsonNode scores(@RequestParam Map<String, String> p) {
    return pages.page(
        "scores", p, () -> store.read("select data from score_row order by round_id desc"));
  }

  @GetMapping(value = "/api/v1/events/stream", produces = "text/event-stream")
  public SseEmitter stream(
      @RequestHeader(value = "Last-Event-ID", required = false) String last,
      @RequestParam(required = false) String after) {
    long latest = store.cursor(), start = latest;
    String input = last == null ? after : last;
    if (input != null) {
      if (!input.matches("0|[1-9]\\d{0,18}")) throw Problem.invalid("Invalid stream cursor");
      try {
        start = Long.parseLong(input);
      } catch (NumberFormatException e) {
        throw Problem.invalid("Invalid stream cursor");
      }
      if (start > latest) throw Problem.invalid("Cursor is ahead of stream");
      Long oldest = store.db.queryForObject("select min(seq) from change_event", Long.class);
      if (oldest != null && start < oldest - 1)
        throw new Problem(410, "CURSOR_EXPIRED", "Fetch a new snapshot");
    }
    var emitter = new SseEmitter(0L);
    final long begin = start;
    var active = new java.util.concurrent.atomic.AtomicBoolean(true);
    emitter.onCompletion(() -> active.set(false));
    emitter.onTimeout(() -> active.set(false));
    emitter.onError(e -> active.set(false));
    streams.execute(
        () -> {
          long cursor = begin, nextBeat = System.nanoTime();
          try {
            while (active.get() && !Thread.currentThread().isInterrupted()) {
              var batch =
                  store.read(
                      "select data from change_event where seq>? order by seq limit 100", cursor);
              for (var event : batch) {
                emitter.send(
                    SseEmitter.event()
                        .id(str(event, "sequence"))
                        .name("change")
                        .data(event, MediaType.APPLICATION_JSON));
                cursor = event.get("sequence").asLong();
              }
              if (System.nanoTime() >= nextBeat) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
                nextBeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
              }
              if (batch.size() < 100) Thread.sleep(300);
            }
          } catch (Exception e) {
            emitter.complete();
          }
        });
    return emitter;
  }
}
