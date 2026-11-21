package ai.aeiou.forwarder.application;

import static ai.aeiou.forwarder.application.Workflow.*;
import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.adapters.Catalog;
import ai.aeiou.forwarder.ports.Organizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Worker implements ApplicationRunner {
  final Workflow flow;
  final Catalog catalog;
  final Organizer organizer;
  final TransactionTemplate tx;
  final DemoScenario demo;
  final LiveIntake live;
  final int step;
  private final ExecutorService pool = Executors.newFixedThreadPool(4);
  private final Semaphore capacity = new Semaphore(4);
  public volatile boolean ready = false;
  public volatile String health = "OFFLINE";

  record Job(String key, String id, String action, ObjectNode payload, int attempt) {}

  public Worker(
      Workflow flow,
      Catalog catalog,
      Organizer organizer,
      TransactionTemplate tx,
      DemoScenario demo,
      LiveIntake live,
      @Value("${forwarder.demo.step-seconds}") int step) {
    this.flow = flow;
    this.catalog = catalog;
    this.organizer = organizer;
    this.tx = tx;
    this.demo = demo;
    this.live = live;
    this.step = step;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    var at = flow.clock.now();
    catalog.load(at);
    if (flow.clock.live) live.verify();
    tx.executeWithoutResult(
        t -> {
          flow.store.lock();
          if (!flow.clock.live
              && flow.store.db.queryForObject("select count(*) from shipment", Integer.class)
                  == 0) {
            demo.start(at, false);
            flow.store.schedule("demo:second", "s_101", "DEMO_INTAKE", at.plusSeconds(95), obj());
          }
        });
    health = "HEALTHY";
    ready = true;
  }

  @PreDestroy
  public void stop() {
    pool.shutdownNow();
  }

  @Scheduled(fixedDelayString = "${forwarder.demo.tick-ms}")
  public void tick() {
    if (!ready) return;
    tx.executeWithoutResult(
        t -> {
          flow.store.lock();
          if (!flow.clock.live)
            flow.store.db.update(
                "update stream_clock set demo_seconds=demo_seconds+? where id=1", step);
          Instant at = flow.clock.now();
          var expired =
              flow.store.db.query(
                  "select id,shipment_id,action,payload,attempts from job where state='RUNNING' and"
                      + " due_at<=?",
                  (r, i) ->
                      new Job(
                          r.getString(1),
                          r.getString(2),
                          r.getString(3),
                          (ObjectNode) parse(r.getString(4)),
                          r.getInt(5)),
                  java.sql.Timestamp.from(at));
          for (var j : expired) fail(j, new Organizer.Failure(0, 1), at);
        });
    for (int i = 0; i < 40; i++) {
      if (!capacity.tryAcquire()) {
        var timer = tx.execute(t -> claim(flow.clock.now(), true));
        if (timer == null) break;
        perform(timer);
        continue;
      }
      Job j;
      try {
        j = tx.execute(t -> claim(flow.clock.now(), false));
      } catch (RuntimeException e) {
        capacity.release();
        throw e;
      }
      if (j == null) {
        capacity.release();
        break;
      }
      var external =
          Set.of("SEND", "BOOK", "EXTRACT", "PORTAL", "REFRESH", "ATTACH", "TARIFF")
              .contains(j.action);
      if (external && flow.clock.live)
        pool.execute(
            () -> {
              try {
                perform(j);
              } finally {
                capacity.release();
              }
            });
      else {
        try {
          perform(j);
        } finally {
          capacity.release();
        }
      }
    }
  }

  private Job claim(Instant at, boolean localOnly) {
    flow.store.lock();
    var jobs =
        flow.store.db.query(
            "select id,shipment_id,action,payload,attempts from job where state='READY' and"
                + " due_at<=? "
                + (localOnly
                    ? "and action in"
                          + " ('DECIDE','FAST_DECIDE','SHADOW','OFFER','TRACK','ACCEPT','CONFIRM','DEMO_INTAKE')"
                          + " "
                    : "")
                + "order by case when action in ('DECIDE','FAST_DECIDE') or (action='SEND' and id"
                + " like '%:quote') then 0 when action in ('BOOK','EXTRACT','PORTAL') then 1 when"
                + " action='SEND' then 2 else 3 end,due_at,id limit 1",
            (r, i) ->
                new Job(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    (ObjectNode) parse(r.getString(4)),
                    r.getInt(5) + 1),
            java.sql.Timestamp.from(at));
    if (jobs.isEmpty()) return null;
    var j = jobs.getFirst();
    if (j.action.equals("EXTRACT") || j.action.equals("PORTAL")) {
      var shipment = flow.store.shipment(j.id);
      if (!at.isBefore(deadline(shipment))
          || !shipment.path("requestRevision").equals(j.payload.path("revision"))
          || (j.action.equals("PORTAL") && shipment.has("quote"))) {
        state(j, "CANCELLED", at);
        return j;
      }
    }
    if (j.action.equals("SEND") || j.action.equals("BOOK")) {
      var d = flow.store.shipment(j.id);
      boolean quote = str(j.payload, "category").equals("QUOTE");
      if (str(j.payload, "category").equals("BID_REQUEST")
          && (!at.isBefore(deadline(d)) || d.has("quote"))) {
        state(j, "CANCELLED", at);
        return j;
      }
      if (j.key.equals(j.id + ":dg-documents") && !at.isBefore(deadline(d))) {
        state(j, "CANCELLED", at);
        return j;
      }
      boolean stale =
          quote
              && (flow.chosen(d).has("validUntil")
                  && !Instant.parse(str(flow.chosen(d), "validUntil")).isAfter(at));
      if (quote
          && (stale
              || !at.isBefore(deadline(d))
              || d.at("/quote/requestRevision").asInt() != d.get("requestRevision").asInt())) {
        state(j, "CANCELLED", at);
        if (d.has("quote")) ((ObjectNode) d.get("quote")).put("sendState", "FAILED");
        session(d).put("phase", "CLOSED").put("outcomeReason", "Quote expired before dispatch");
        flow.store.changed(d, "QUOTE", "Dispatch deadline passed", at);
        return j;
      }
      if (str(session(d), "control").equals("PAUSED")) {
        state(j, "READY", at.plusSeconds(2));
        return j;
      }
      if (quote
          && (!str(j.payload, "to").equals(str(d, "customer"))
              || !str(j.payload, "parentId").equals(str(d, "parent"))
              || !str(j.payload, "bodyText").equals(d.at("/quote/body").asText())
              || !d.at("/quote/total").equals(flow.chosen(d).get("quotedTotal")))) {
        state(j, "FAILED", at);
        ((ObjectNode) d.get("quote")).put("sendState", "FAILED");
        flow.store.changed(
            d, "QUOTE", "Outbound quote failed recipient, parent or price validation", at);
        return j;
      }
    }
    if (flow.clock.live && j.action.equals("SEND")) {
      var attempts =
          flow.store.db.queryForObject(
              "select count(*) from effect_attempt where kind='SEND' and started_at>?",
              Integer.class,
              java.sql.Timestamp.from(at.minusSeconds(60)));
      if (attempts >= 60) {
        state(j, "READY", at.plusSeconds(1));
        return j;
      }
    }
    flow.store.db.update(
        "insert into effect_attempt values(?,?,?,?)",
        j.key,
        j.attempt,
        j.action,
        java.sql.Timestamp.from(at));
    flow.store.db.update(
        "update job set state='RUNNING',attempts=?,due_at=? where id=?",
        j.attempt,
        java.sql.Timestamp.from(at.plusSeconds(45)),
        j.key);
    return j;
  }

  private void state(Job j, String state, Instant due) {
    flow.store.db.update(
        "update job set state=?,due_at=? where id=?", state, java.sql.Timestamp.from(due), j.key);
  }

  private boolean owned(Job j) {
    return flow.store.db.queryForObject(
            "select count(*) from job where id=? and state='RUNNING' and attempts=?",
            Integer.class,
            j.key,
            j.attempt)
        == 1;
  }

  private void perform(Job j) {
    if (!owned(j)) return;
    try {
      JsonNode response =
          switch (j.action) {
            case "SEND" -> {
              String parent = str(j.payload, "parentId");
              Long upstream = null;
              if (flow.clock.live && !parent.isEmpty()) {
                var ids =
                    flow.store.db.queryForList(
                        "select upstream_id from mail where id=? and shipment_id=?",
                        String.class,
                        parent,
                        j.id);
                if (ids.isEmpty() || ids.getFirst() == null)
                  throw new IllegalArgumentException("Missing reply parent");
                upstream = Long.parseLong(ids.getFirst());
              }
              yield organizer.call(
                  "POST",
                  "/v1/mailbox/messages",
                  obj(
                      "to",
                      j.payload.get("to"),
                      "subject",
                      j.payload.get("subject"),
                      "body",
                      j.payload.get("bodyText"),
                      "in_reply_to",
                      upstream));
            }
            case "BOOK" -> organizer.call("POST", "/v1/portal/bookings", j.payload);
            case "EXTRACT", "PORTAL", "REFRESH", "ATTACH", "TARIFF" ->
                live.read(j.action, j.payload);
            default -> obj();
          };
      tx.executeWithoutResult(
          t -> {
            flow.store.lock();
            if (!owned(j)) return;
            apply(j, response, flow.clock.now());
            state(j, "DONE", flow.clock.now());
          });
    } catch (RuntimeException ex) {
      tx.executeWithoutResult(
          t -> {
            flow.store.lock();
            if (owned(j)) fail(j, ex, flow.clock.now());
          });
    }
  }

  private void fail(Job j, RuntimeException error, Instant at) {
    int code = error instanceof Organizer.Failure f ? f.status : 0;
    int wait = error instanceof Organizer.Failure f ? f.retryAfter : 3;
    boolean ambiguous = j.action.equals("SEND") && (code == 0 || code >= 500);
    boolean retry = !ambiguous && j.attempt < 5 && (code == 429 || code == 0 || code >= 500);
    if (j.action.equals("TARIFF")) {
      state(j, retry ? "READY" : "FAILED", at.plusSeconds(wait));
      flow.store.event(null, "TARIFF", "Tariff download status " + code, at, null);
      return;
    }
    var d = flow.store.shipment(j.id);
    if (j.action.equals("PORTAL") && (code == 503 || code == 404) && at.isBefore(deadline(d))) {
      flow.queueMail(
          d,
          "rate-desk-r" + d.path("requestRevision").asInt(),
          "BID_REQUEST",
          "ratedesk@hackloop.mail",
          null,
          "Please provide all-in rate, carrier, booking instructions and transit for "
              + str(session(d), "reference")
              + " "
              + str(j.payload, "origin")
              + " to "
              + str(j.payload, "destination")
              + ", "
              + str(j.payload, "weight_kg")
              + " kg, "
              + str(j.payload, "volume_cbm")
              + " cbm.",
          at);
    }
    if (j.action.equals("BOOK") && (code == 410 || code == 404) && !d.has("refreshAttempted")) {
      d.put("refreshAttempted", true);
      var rate = d.path("upstreamRates").path(str(j.payload, "rate_id"));
      if (rate.isObject())
        flow.store.schedule(j.id + ":refresh", j.id, "REFRESH", at, rate.get("request"));
    }
    if (j.action.equals("BOOK") && code == 503) {
      retry = false;
      catalog
          .byName(d.at("/booking/provider").asText())
          .ifPresent(
              c ->
                  flow.queueMail(
                      d,
                      "fallback-book",
                      "BOOKING",
                      c.email(),
                      null,
                      "Please book "
                          + str(session(d), "reference")
                          + " per accepted quote "
                          + d.at("/quote/total/amount").asText()
                          + " "
                          + d.at("/quote/total/currency").asText(),
                      at));
    }
    state(
        j,
        ambiguous ? "UNCERTAIN" : retry ? "READY" : "FAILED",
        at.plusSeconds(Math.max(wait, Math.min(30, 1L << Math.min(j.attempt, 5)))));
    if (j.action.equals("SEND") && str(j.payload, "category").equals("QUOTE") && !retry)
      ((ObjectNode) d.get("quote")).put("sendState", ambiguous ? "UNCERTAIN" : "FAILED");
    if (j.action.equals("BOOK") && !retry)
      ((ObjectNode) d.get("booking"))
          .put(
              "state",
              code == 409
                  ? "UNCERTAIN"
                  : code == 503 || d.has("refreshAttempted") ? "REQUESTED" : "FAILED");
    flow.store.changed(
        d,
        "HEALTH",
        j.action
            + " attempt "
            + j.attempt
            + " status "
            + code
            + (ambiguous
                ? " delivery uncertain"
                : retry ? " retry scheduled" : " requires reconciliation"),
        at);
  }

  private void apply(Job j, JsonNode response, Instant at) {
    if (j.action.equals("DEMO_INTAKE")) {
      demo.start(at, true);
      return;
    }
    if (j.action.equals("TARIFF")) {
      live.tariff(j.payload, response, at);
      return;
    }
    var d = flow.store.shipment(j.id);
    var s = session(d);
    switch (j.action) {
      case "FAST_DECIDE" -> live.fastDecide(d, j.payload, at);
      case "DECIDE" -> flow.decide(d, at);
      case "SHADOW" -> flow.scoreShadow(d, at);
      case "OFFER" -> flow.offer(d, j.payload, at);
      case "SEND" -> {
        String upstream = response.path("id").asText(response.at("/message/id").asText(""));
        if (upstream.isBlank()) throw new Organizer.Failure(0, 1);
        flow.sent(d, j.payload, upstream, at);
        if (!flow.clock.live && str(j.payload, "category").equals("QUOTE"))
          flow.store.schedule(j.id + ":accept", j.id, "ACCEPT", at.plusSeconds(20), obj());
      }
      case "ACCEPT" -> {
        var m =
            mail(
                j.id + "_accept",
                "ACCEPTANCE",
                str(d, "customer"),
                TEAM,
                str(s, "subject"),
                "We accept your quoted service.",
                at,
                j.id + "_quote");
        flow.store.message(j.id, m);
        flow.accepted(d, str(m, "id"), at);
      }
      case "BOOK" -> {
        var b = (ObjectNode) d.get("booking");
        b.put("state", "ACKNOWLEDGED").put("bookingReference", str(response, "booking_reference"));
        d.set("bookingResponse", response);
        flow.store.changed(d, "BOOKING", "Provider acknowledged the booking request", at);
        if (!flow.clock.live)
          flow.store.schedule(
              j.id + ":carrier-confirm", j.id, "CONFIRM", at.plusSeconds(25), obj());
      }
      case "CONFIRM" -> {
        var m =
            mail(
                j.id + "_carrier",
                "BOOKING",
                "carrier@hackloop.mail",
                TEAM,
                str(s, "reference"),
                "Booking confirmed: " + d.at("/booking/bookingReference").asText(),
                at,
                null);
        flow.store.message(j.id, m);
        flow.confirmed(d, d.at("/booking/bookingReference").asText(), at);
        flow.store.schedule(j.id + ":question", j.id, "TRACK", at.plusSeconds(15), obj());
      }
      case "TRACK" -> {
        var m =
            mail(
                j.id + "_question",
                "TRACKING",
                str(d, "customer"),
                TEAM,
                str(s, "subject"),
                "What is the latest confirmed status?",
                at,
                j.id + "_confirm");
        flow.store.message(j.id, m);
        flow.tracking(d, m, at);
      }
      case "REFRESH" -> live.refreshBooking(d, j.payload, response, at);
      case "EXTRACT", "PORTAL", "ATTACH" -> live.apply(j.action, d, j.payload, response, at);
      default -> throw new IllegalArgumentException("Unknown job action");
    }
  }
}
