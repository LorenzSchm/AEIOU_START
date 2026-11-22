package ai.aeiou.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live desk data over Server-Sent Events ({@code GET /api/v1/events}).
 *
 * <p>A desk connects once and gets a {@code snapshot} (status and every message). After that it only
 * receives what changed: {@code message} for a new or changed message, {@code removed} for one that is
 * gone and {@code status} when the status line changes. Every event carries an increasing
 * {@code version}. A reconnecting desk simply gets a fresh snapshot.
 *
 * <p>Changes are found by comparing inbox snapshots once a second, so every writer in the worker,
 * the webhook and the read-only replica is covered without touching their code. The comparison runs
 * only while at least one desk is connected, on its own thread: the app's scheduled jobs are switched
 * off on a read-only desk, which still needs live updates.
 */
@Component
class InboxFeed {
  private static final Logger log = LoggerFactory.getLogger(InboxFeed.class);
  private static final long STREAM_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

  private final Ledger ledger;
  private final DeskStatus status;
  private final ObjectMapper json;
  private final ScheduledExecutorService ticks;
  private final List<SseEmitter> desks = new CopyOnWriteArrayList<>();

  // Guarded by "this": the last snapshot every connected desk has been brought up to.
  private Map<Long, String> lastMessages = Map.of();
  private String lastStatus = "";
  private long version;
  private long sinceHeartbeat;

  InboxFeed(Ledger ledger, DeskStatus status, ObjectMapper json,
      @Value("${dispatch.events-ms:1000}") long intervalMs) {
    this.ledger = ledger;
    this.status = status;
    this.json = json;
    this.ticks = Executors.newSingleThreadScheduledExecutor(r -> {
      var thread = new Thread(r, "desk-events");
      thread.setDaemon(true);
      return thread;
    });
    ticks.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  /** A new desk connection: sends the current snapshot, then keeps the desk up to date. */
  SseEmitter subscribe() {
    var desk = new SseEmitter(STREAM_TIMEOUT_MS);
    desk.onCompletion(() -> desks.remove(desk));
    desk.onTimeout(() -> desks.remove(desk));
    desk.onError(error -> desks.remove(desk));
    synchronized (this) {
      try {
        var messages = ledger.inbox();
        var current = status.current();
        // Bring the shared baseline up to date first, so the next tick only sends later changes.
        if (desks.isEmpty()) {
          lastMessages = InboxDiff.index(messages, this::write);
          lastStatus = write(current);
        }
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("version", version);
        snapshot.put("status", current);
        snapshot.put("messages", messages);
        send(desk, "snapshot", snapshot);
        desks.add(desk);
      } catch (IOException | RuntimeException failed) {
        desk.completeWithError(failed);
      }
    }
    return desk;
  }

  int connectedDesks() {
    return desks.size();
  }

  synchronized void tick() {
    if (desks.isEmpty()) return;
    try {
      var messages = ledger.inbox();
      var next = InboxDiff.index(messages, this::write);
      var changes = InboxDiff.between(lastMessages, next);
      String nextStatus = write(status.current());
      boolean statusChanged = !nextStatus.equals(lastStatus);
      if (!changes.isEmpty() || statusChanged) {
        version++;
        var byId = new java.util.HashMap<Long, Map<String, Object>>();
        for (var m : messages) byId.put(Ledger.id(m.get("id")), m);
        for (long id : changes.upserted()) broadcast("message", Map.of("version", version, "message", byId.get(id)));
        for (long id : changes.removed()) broadcast("removed", Map.of("version", version, "id", id));
        if (statusChanged) broadcast("status", Map.of("version", version, "status", json.readTree(nextStatus)));
        lastMessages = next;
        lastStatus = nextStatus;
      }
      // Proxies and browsers drop idle streams; a comment every 15 s keeps them open.
      if (++sinceHeartbeat >= 15) {
        sinceHeartbeat = 0;
        for (var desk : desks) {
          try {
            desk.send(SseEmitter.event().comment("heartbeat"));
          } catch (IOException | IllegalStateException gone) {
            desks.remove(desk);
          }
        }
      }
    } catch (RuntimeException | JsonProcessingException failed) {
      log.warn("Desk event update failed: {}", failed.getMessage());
    }
  }

  private void broadcast(String name, Object payload) {
    for (var desk : desks) {
      try {
        send(desk, name, payload);
      } catch (IOException | IllegalStateException gone) {
        desks.remove(desk);
      }
    }
  }

  private void send(SseEmitter desk, String name, Object payload) throws IOException {
    desk.send(SseEmitter.event().name(name).id(Long.toString(version))
        .data(write(payload), MediaType.APPLICATION_JSON));
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @PreDestroy
  void stop() {
    ticks.shutdownNow();
    desks.forEach(SseEmitter::complete);
  }
}
