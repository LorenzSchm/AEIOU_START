package ai.aeiou.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
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
 * <p>A desk connects once and gets a {@code snapshot}: the status line and the summary (Overview,
 * Shipments and Scoreboard figures plus the desk's frame: inbox size, attention badge and list, globe
 * routes). It does not carry messages; views fetch the rows they show (queue and shipment pages, a
 * case's thread). After that the desk only receives compact notices: {@code changed} with the ids of
 * new, changed and removed messages, {@code summary} with the parts of the summary that changed (see
 * {@link #patch}) and {@code status} when the status line changes. Every event carries an increasing {@code version}; a desk refetches what it
 * shows when the version moves. A reconnecting desk simply gets a fresh snapshot.
 *
 * <p>Each second, while a desk is connected, the feed asks {@link InboxCache} for the inbox, which
 * rebuilds it only when {@link LedgerWatch} sees the inbox tables change. Only then are messages
 * compared and the summary recomputed. Summaries also move with the clock (a request expires six
 * minutes after arrival), so the summary is also recomputed when a request crosses that mark. Every
 * writer (worker, webhook, read-only replica) is covered without touching their code. The feed runs on
 * its own thread: the app's scheduled jobs are switched off on a read-only desk, which still needs live
 * updates.
 */
@Component
class InboxFeed {
  private static final Logger log = LoggerFactory.getLogger(InboxFeed.class);
  private static final long STREAM_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

  private final InboxCache inbox;
  private final DeskStatus status;
  private final ObjectMapper json;
  private final java.time.Clock clock;
  private final ScheduledExecutorService ticks;
  private final List<SseEmitter> desks = new CopyOnWriteArrayList<>();

  // Guarded by "this": the state every connected desk has been brought up to.
  private InboxCache.Snapshot lastInbox;
  private long[] expiries = new long[0];
  private Map<Long, String> lastMessages = Map.of();
  private String lastStatus = "";
  private Map<String, Object> lastSummary;
  private String lastSummaryKey = "";
  private long lastSummaryAt;
  private long version;
  private long sinceHeartbeat;
  private long summaries;

  InboxFeed(InboxCache inbox, DeskStatus status, ObjectMapper json, java.time.Clock clock,
      @Value("${dispatch.events-ms:1000}") long intervalMs) {
    this.inbox = inbox;
    this.status = status;
    this.json = json;
    this.clock = clock;
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
        // With no desk connected the baseline is stale: bring it up to date first, so the next tick
        // only sends later changes. Otherwise the new desk starts from the baseline the others have.
        if (desks.isEmpty() || lastSummary == null) {
          var snapshot = inbox.current();
          long now = clock.millis();
          adopt(snapshot);
          lastMessages = InboxDiff.index(snapshot.messages(), this::write);
          lastStatus = write(status.current());
          lastSummary = summary(snapshot, now);
          lastSummaryKey = summaryKey(lastSummary);
          lastSummaryAt = now;
        }
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("version", version);
        snapshot.put("status", json.readTree(lastStatus));
        snapshot.put("summary", lastSummary);
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

  /** How many summaries the feed computed: on connect, after an inbox change or at an expiry. */
  synchronized long summaries() {
    return summaries;
  }

  synchronized void tick() {
    if (desks.isEmpty()) return;
    try {
      var snapshot = inbox.current();
      long now = clock.millis();
      boolean inboxChanged = snapshot != lastInbox;
      var changes = new InboxDiff.Changes(List.of(), List.of());
      Map<Long, String> next = lastMessages;
      if (inboxChanged) {
        next = InboxDiff.index(snapshot.messages(), this::write);
        changes = InboxDiff.between(lastMessages, next);
        adopt(snapshot);
      }
      String nextStatus = write(status.current());
      boolean statusChanged = !nextStatus.equals(lastStatus);
      // The summary depends only on the inbox and the clock, and on the clock only when a request
      // crosses its six-minute mark.
      Map<String, Object> summary = lastSummary;
      String nextSummaryKey = lastSummaryKey;
      if (!changes.isEmpty() || DeskSummary.expiresBetween(expiries, lastSummaryAt, now)) {
        summary = summary(snapshot, now);
        nextSummaryKey = summaryKey(summary);
        lastSummaryAt = now;
      }
      boolean summaryChanged = !nextSummaryKey.equals(lastSummaryKey);
      if (!changes.isEmpty() || statusChanged || summaryChanged) {
        version++;
        if (!changes.isEmpty()) {
          var notice = new LinkedHashMap<String, Object>();
          notice.put("version", version);
          notice.put("ids", changes.upserted());
          notice.put("removed", changes.removed());
          broadcast("changed", notice);
        }
        if (statusChanged) broadcast("status", Map.of("version", version, "status", json.readTree(nextStatus)));
        if (summaryChanged) broadcast("summary", Map.of("version", version, "summary", patch(lastSummary, summary)));
        lastMessages = next;
        lastStatus = nextStatus;
        lastSummary = summary;
        lastSummaryKey = nextSummaryKey;
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

  private void adopt(InboxCache.Snapshot snapshot) {
    lastInbox = snapshot;
    expiries = DeskSummary.expiries(snapshot.tree());
  }

  private Map<String, Object> summary(InboxCache.Snapshot snapshot, long now) {
    summaries++;
    return DeskSummary.of(snapshot.tree(), java.time.Instant.ofEpochMilli(now));
  }

  /**
   * The parts of {@code next} that differ from {@code previous}, two levels deep: each changed section
   * (overview, shipments, score, desk) with only its changed entries, plus {@code computed_at}. A new
   * message usually moves a few counters, so the globe's routes and the board's rounds are not resent
   * with them. The desk merges a patch into the summary it holds.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> patch(Map<String, Object> previous, Map<String, Object> next,
      java.util.function.Function<Object, String> write) {
    var out = new LinkedHashMap<String, Object>();
    for (var section : next.entrySet()) {
      Object before = previous == null ? null : previous.get(section.getKey());
      if (section.getKey().equals("computed_at")) out.put(section.getKey(), section.getValue());
      else if (section.getValue() instanceof Map<?, ?> entries && before instanceof Map<?, ?> old) {
        var changed = new LinkedHashMap<String, Object>();
        for (var entry : ((Map<String, Object>) entries).entrySet())
          if (!old.containsKey(entry.getKey()) || !write.apply(entry.getValue()).equals(write.apply(old.get(entry.getKey()))))
            changed.put(entry.getKey(), rows(old.get(entry.getKey()), entry.getValue(), write));
        if (!changed.isEmpty()) out.put(section.getKey(), changed);
      } else if (before == null || !write.apply(section.getValue()).equals(write.apply(before)))
        out.put(section.getKey(), section.getValue());
    }
    return out;
  }

  /**
   * A changed list of rows with unique ids (the globe's routes, the board's rounds) as
   * {@code {"$ids": [every id in order], "$rows": [new and changed rows]}}; any other value whole.
   */
  @SuppressWarnings("unchecked")
  static Object rows(Object previous, Object next, java.util.function.Function<Object, String> write) {
    if (!(previous instanceof List<?> before) || !(next instanceof List<?> after) || after.isEmpty()) return next;
    var old = new java.util.HashMap<Object, String>();
    for (var row : before) {
      if (!(row instanceof Map<?, ?> m) || m.get("id") == null || old.put(m.get("id"), write.apply(row)) != null) return next;
    }
    var ids = new ArrayList<Object>();
    var changed = new ArrayList<Object>();
    var seen = new java.util.HashSet<Object>();
    for (var row : after) {
      if (!(row instanceof Map<?, ?> m) || m.get("id") == null || !seen.add(m.get("id"))) return next;
      ids.add(m.get("id"));
      if (!write.apply(row).equals(old.get(m.get("id")))) changed.add(row);
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("$ids", ids);
    out.put("$rows", changed);
    return write.apply(out).length() < write.apply(next).length() ? out : next;
  }

  private Map<String, Object> patch(Map<String, Object> previous, Map<String, Object> next) {
    return patch(previous, next, this::write);
  }

  /** The summary without its timestamp, so an unchanged summary is not resent. */
  private String summaryKey(Map<String, Object> summary) {
    var copy = new LinkedHashMap<>(summary);
    copy.remove("computed_at");
    return write(copy);
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
