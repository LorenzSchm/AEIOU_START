package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

@Service
class WaveWorker {
  private static final Logger log = LoggerFactory.getLogger(WaveWorker.class);
  private final Ledger ledger;
  private final Catalog catalog;
  private final FiveU upstream;
  private final Clock clock;
  private final RfqGateway gateway;
  private final boolean automatic;
  private final Instant start;
  private final long waitSeconds;
  private final boolean counteroffers;
  private volatile Instant lastPoll;
  private volatile String error;
  private Instant pollAfter = Instant.EPOCH;

  WaveWorker(
      Ledger ledger,
      Catalog catalog,
      FiveU upstream,
      Clock clock,
      RfqGateway gateway,
      @Value("${dispatch.auto}") boolean automatic,
      @Value("${dispatch.start-at}") Instant start,
      @Value("${dispatch.bid-wait-seconds}") long waitSeconds,
      @Value("${dispatch.counteroffers}") boolean counteroffers) {
    this.ledger = ledger;
    this.catalog = catalog;
    this.upstream = upstream;
    this.clock = clock;
    this.gateway = gateway;
    this.automatic = automatic;
    this.start = start;
    this.waitSeconds = waitSeconds;
    this.counteroffers = counteroffers;
  }

  record Status(
      Instant lastPoll,
      String error,
      boolean autoSend,
      long cursor,
      int tariffs,
      Instant startAt,
      String backend,
      String rfqModel) {}

  Status status() {
    return new Status(
        lastPoll,
        error,
        automatic,
        ledger.cursor(),
        catalog.size(),
        start,
        "spring-boot",
        gateway.model);
  }

  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  synchronized void recheckOpenExtractions() {
    if (automatic) gateway.recheckOpenConflicts(clock.instant().minusSeconds(355));
  }

  @Scheduled(fixedDelayString = "${dispatch.poll-ms}")
  void poll() {
    if (clock.instant().isBefore(pollAfter)) return;
    try {
      ledger.ingest(
          upstream.get("/v1/mailbox/messages?since_id={id}&limit=200", ledger.cursor()),
          catalog,
          clock.instant());
      lastPoll = clock.instant();
      error = null;
    } catch (RuntimeException ex) {
      error = "Mailbox poll failed (" + ex.getClass().getSimpleName() + ")";
      log.warn("{}", error);
      pollAfter = clock.instant().plusSeconds(5);
    }
  }

  @Scheduled(fixedDelayString = "${dispatch.poll-ms}")
  synchronized void work() {
    if (!automatic || clock.instant().isBefore(start)) return;
    for (long id :
        ledger
            .jdbc()
            .queryForList(
                """
                SELECT m.id FROM mail m JOIN wave_case w ON m.id=w.id WHERE w.operator=0 AND
                m.stage IN ('new','collecting','waiting for pickup','waiting for air price') ORDER BY m.id
                """,
                Long.class)) {
      try {
        advance(id);
      } catch (RetryLater ex) {
        /* Confirmed rate limit. Try again on a later tick. */
      } catch (Conflict ex) {
        ledger.stage(id, "review", ex.getMessage());
      } catch (RuntimeException ex) {
        log.warn("RFQ {} held: {}", id, ex.getClass().getSimpleName());
        ledger.stage(
            id, "review", "Automatic pricing interrupted. Inspect offers and delivery records.");
      }
    }
  }

  static class Conflict extends RuntimeException {
    Conflict(String msg) {
      super(msg);
    }
  }

  static class RetryLater extends Conflict {
    RetryLater() {
      super("Mailbox rate limit. Waiting to retry.");
    }
  }

  private Instant ageLimit(Map<String, Object> m) {
    var received = Ledger.instant(m.get("received"));
    if (received == null)
      throw new Conflict("Unknown arrival time. Verify the original RFQ timestamp.");
    return received.plusSeconds(360);
  }

  private Map<String, Object> target(long id) {
    var m = ledger.mail(id);
    if (!"request".equals(m.get("kind")) || Ledger.id(m.get("root")) != id)
      throw new Conflict("Select the original customer RFQ.");
    if (!clock.instant().isBefore(ageLimit(m)))
      throw new Conflict("The six-minute quote window is closed.");
    return m;
  }

  private void advance(long id) {
    var m = ledger.mail(id);
    var received = Ledger.instant(m.get("received"));
    if (received == null || received.isBefore(start) || Ledger.str(m, "sender").isBlank()) {
      ledger.stage(id, "review", "Verify the sender and wave arrival time.");
      return;
    }
    var deadline = ageLimit(m);
    if (!clock.instant().isBefore(deadline.minusSeconds(4))) {
      ledger.stage(
          id, "expired", "Quote window closed before a reliable all-in price was available.");
      return;
    }
    String delivery = ledger.delivery("quote:" + id);
    if (delivery != null) {
      ledger.stage(
          id, delivery.equals("sent") ? "sent" : "review", "Check the recorded quote delivery.");
      return;
    }
    gateway.ensure(id, m, true);
    m = target(id);
    var w = ledger.jdbc().queryForMap("SELECT * FROM wave_case WHERE id=?", id);
    var s = ledger.read(m.get("fields"), Shipment.class);
    if ("new".equals(m.get("stage")) && w.get("reason") != null) {
      ledger.stage(id, "review", w.get("reason").toString());
      return;
    }
    if (catalog.problem(s) != null) {
      ledger.stage(id, "review", catalog.problem(s));
      return;
    }
    for (var c : catalog.invitations(s)) {
      if (clock.instant().isAfter(deadline.minusSeconds(8))) break;
      String key = "bid:" + id + ":" + c.email();
      if (ledger.delivery(key) == null) {
        try {
          invite(id, s, c);
        } catch (Conflict ex) {
          log.info("RFQ {} bid to {}: {}", id, c.name(), ex.getMessage());
        }
      }
    }
    long queried = Ledger.id(w.get("queried"));
    if (queried == 0
        || (queried > 0
            && clock.instant().getEpochSecond() - queried >= 35
            && clock.instant().isBefore(deadline.minusSeconds(35)))) {
      var result = upstream.rates(s, clock.instant());
      var offers = new ArrayList<Offer>(Arrays.asList(cached(w)));
      for (var o : result.offers()) {
        offers.removeIf(old -> old.airline().equals(o.airline()));
        offers.add(o);
      }
      ledger
          .jdbc()
          .update(
              "UPDATE wave_case SET offers=?,queried=? WHERE id=?",
              ledger.write(offers),
              result.complete() ? -1 : clock.instant().getEpochSecond(),
              id);
    }
    var tenders = ledger.tenders(id);
    boolean allAnswered =
        !tenders.isEmpty() && tenders.stream().allMatch(t -> t.get("reply_id") != null);
    Instant waitUntil = received.plusSeconds(waitSeconds);
    if (!allAnswered && clock.instant().isBefore(waitUntil)) {
      ledger.stage(id, "collecting", null);
      return;
    }
    var draft = calculate(id, s, null, true);
    if (draft.offer() == null) {
      String reason = String.join(" ", draft.notes());
      ledger.stage(
          id, reason.contains("pickup") ? "waiting for pickup" : "waiting for air price", reason);
      return; // Late valid bids can still close an exception before expiry.
    }
    if (counteroffers) {
      if (awaitCounteroffer(id, deadline, waitUntil, draft, tenders)) {
        ledger.stage(id, "collecting", null);
        return;
      }
      if (ledger.counterAnswered(id)) {
        var refreshed = calculate(id, s, null, true);
        if (refreshed.offer() != null) draft = refreshed;
      }
    }
    save(id, s, draft, false);
    sendQuote(id, draft.revision());
  }

  private boolean awaitCounteroffer(
      long id,
      Instant deadline,
      Instant waitUntil,
      Draft draft,
      List<Map<String, Object>> tenders) {
    Instant now = clock.instant();
    String key = "counter:" + id;
    var attempts =
        ledger
            .jdbc()
            .queryForList("SELECT state, attempted_at FROM deliveries WHERE action=?", key);
    Instant validUntil = draft.offer().validUntil();
    if (!attempts.isEmpty()) {
      var attempt = attempts.getFirst();
      Instant responseDeadline =
          Ledger.instant(attempt.get("attempted_at")).plusSeconds(Ledger.COUNTER_WAIT_SECONDS);
      return "sent".equals(attempt.get("state"))
          && now.isBefore(responseDeadline)
          && (validUntil == null || validUntil.isAfter(responseDeadline.plusSeconds(8)))
          && !ledger.counterAnswered(id);
    }

    if (!now.isBefore(waitUntil.plusSeconds(4))
        || !now.isBefore(deadline.minusSeconds(Ledger.COUNTER_WAIT_SECONDS + 8))
        || (validUntil != null
            && !validUntil.isAfter(now.plusSeconds(Ledger.COUNTER_WAIT_SECONDS + 8)))) {
      return false;
    }
    String name =
        "carrier bid".equals(draft.offer().source()) ? draft.offer().airline() : draft.trucker();
    if (name == null) return false;
    var carrier = catalog.carriers.stream().filter(c -> c.name().equals(name)).findFirst();
    if (carrier.isEmpty()) return false;
    for (var tender : tenders) {
      String email = Ledger.str(tender, "email");
      if (!carrier.get().email().equals(email) || tender.get("euros") == null) continue;
      var price = new BigDecimal(tender.get("euros").toString());
      var target = price.multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP);
      if (target.signum() <= 0 || target.compareTo(price) >= 0) return false;
      long parent = Ledger.id(tender.get("reply_id"));
      try {
        send(
            key,
            Map.of(
                "to",
                email,
                "in_reply_to",
                parent,
                "subject",
                "Re: Rate request AEIOU-" + id + " | counteroffer",
                "body",
                "Thanks for your quote. Could you offer EUR "
                    + target.toPlainString()
                    + " all in for the same shipment and service? If you can revise your offer,"
                    + " please confirm your final total and transit using this format:\n"
                    + "Revised all-in rate: EUR <total>. Transit: <duration>.\n\nTeam AEIOU"));
        return true;
      } catch (Conflict ex) {
        log.info("RFQ {} counteroffer to {}: {}", id, name, ex.getMessage());
        return false;
      }
    }
    return false;
  }

  private Offer[] cached(Map<String, Object> w) {
    return w.get("offers") == null ? new Offer[0] : ledger.read(w.get("offers"), Offer[].class);
  }

  record ManualBid(String airline, BigDecimal airTotalEur, int transitDays) {}

  record Draft(
      String revision,
      Offer offer,
      List<Offer> alternatives,
      List<String> notes,
      String body,
      String trucker,
      BigDecimal pickupEur,
      Double pickupDays) {}

  private Draft calculate(long id, Shipment s, ManualBid manual, boolean cachedPortal) {
    String problem = catalog.problem(s);
    if (problem != null) throw new IllegalArgumentException(problem);
    var m = target(id);
    var offers =
        new ArrayList<>(
            catalog.price(
                s,
                Ledger.instant(m.get("received"))
                    .atZone(ZoneId.of("Europe/Berlin"))
                    .toLocalDate()));
    var w = ledger.jdbc().queryForMap("SELECT * FROM wave_case WHERE id=?", id);
    var portal = cachedPortal ? List.of(cached(w)) : upstream.rates(s, clock.instant()).offers();
    portal.stream()
        .filter(
            o -> o.validUntil() != null && o.validUntil().isAfter(clock.instant().plusSeconds(4)))
        .forEach(offers::add);
    var airBids = new ArrayList<Offer>();
    var trucks = new ArrayList<Offer>();
    var missingTruckTransit = new HashSet<String>();
    for (var t : ledger.tenders(id)) {
      var original = ledger.read(t.get("terms"), Shipment.class);
      boolean sameCargo =
          original != null
              && Objects.equals(original.origin(), s.origin())
              && Objects.equals(original.destination(), s.destination())
              && sameNumber(original.weightKg(), s.weightKg())
              && sameNumber(original.volumeCbm(), s.volumeCbm());
      if (!sameCargo || t.get("euros") == null) continue;
      var c = catalog.contact(Ledger.str(t, "email")).orElseThrow();
      if (c.truck()
          && (!Objects.equals(s.pickupZone(), original.pickupZone()) || s.pickupZone() == null))
        continue;
      if (t.get("days") == null) {
        if (!c.truck() || "fastest".equals(s.preference())) continue;
        missingTruckTransit.add(c.name());
      }
      var offer =
          new Offer(
              c.name(),
              t.get("days") == null ? 0 : ((Number) t.get("days")).doubleValue(),
              new BigDecimal(t.get("euros").toString()),
              s.chargeableKg(),
              "carrier bid",
              null,
              null);
      (c.truck() ? trucks : airBids).add(offer);
    }
    if (manual != null) {
      if (manual.airline() == null
          || manual.airline().isBlank()
          || manual.airTotalEur() == null
          || manual.airTotalEur().signum() <= 0
          || manual.transitDays() <= 0)
        throw new IllegalArgumentException("Confirm carrier name, total and transit.");
      airBids.add(
          new Offer(
              manual.airline(),
              manual.transitDays(),
              manual.airTotalEur(),
              s.chargeableKg(),
              "carrier bid",
              null,
              null));
    }
    var plan = QuotePlanner.plan(s, offers, airBids, trucks, missingTruckTransit);
    var best = plan.best();
    var pickupDays = plan.pickupDays();
    String body = best == null ? "" : quoteBody(m, s, best, pickupDays);
    return new Draft(
        UUID.randomUUID().toString(),
        best,
        plan.options(),
        plan.notes(),
        body,
        plan.trucker(),
        plan.pickupEur(),
        pickupDays);
  }

  private static String pickupLocation(Map<String, Object> mail, String zone) {
    String text = Ledger.str(mail, "subject") + "\n" + Ledger.str(mail, "body");
    return MailParser.pickupPostcode(text, zone)
        .map(code -> "postcode " + code)
        .orElse("pickup zone " + zone);
  }

  private static String quoteBody(
      Map<String, Object> mail, Shipment s, Offer offer, Double pickupDays) {
    String weight = s.weightKg().stripTrailingZeros().toPlainString() + " kg shipment";
    String priority = "fastest".equals(s.preference()) ? "quickest option" : "lowest rate";
    String route = s.origin() + " to " + s.destination();
    String price = "EUR " + offer.totalEur().toPlainString() + " all in";
    String transit = offer.transitLabel();
    String service = "Allow about " + transit + " from airport to airport.";
    if (s.pickupZone() != null) {
      String location = pickupLocation(mail, s.pickupZone());
      service =
          "That includes collection from "
              + location
              + ". "
              + (pickupDays == null
                  ? "The flight takes about "
                      + transit
                      + ". We still need to confirm pickup timing."
                  : "Allow about " + transit + " from collection to arrival.");
    }
    return "Hi,\n\nThe "
        + priority
        + " we found for your "
        + weight
        + " from "
        + route
        + " is with "
        + offer.airline()
        + " at "
        + price
        + ".\n\n"
        + service
        + "\n\nWould you like us to book it for you?\n\nBest regards,\nTeam AEIOU";
  }

  synchronized void resume(long id, Shipment s) {
    var message = target(id);
    if (!automatic) throw new Conflict("Automatic mode is disabled.");
    String issue = catalog.problem(s);
    if (issue != null) throw new IllegalArgumentException(issue);
    if (ledger.delivery("quote:" + id) != null)
      throw new Conflict("A customer quote is already claimed.");
    // Existing tenders refer to their original cargo. A corrected RFQ may start fresh when none
    // went out.
    for (var t : ledger.tenders(id)) {
      var previous = ledger.read(t.get("terms"), Shipment.class);
      if (previous == null
          || !Objects.equals(previous.origin(), s.origin())
          || !Objects.equals(previous.destination(), s.destination())
          || !sameNumber(previous.weightKg(), s.weightKg())
          || !sameNumber(previous.volumeCbm(), s.volumeCbm())
          || !Objects.equals(previous.pickupZone(), s.pickupZone()))
        throw new Conflict(
            "Bids already cover different shipment details. Use Compare rates with the corrected"
                + " fields.");
    }
    if (Ledger.instant(message.get("received")).isBefore(start))
      throw new Conflict("Request predates this wave.");
    gateway.ensure(id, message, false);
    ledger
        .transactions()
        .executeWithoutResult(
            t -> {
              ledger
                  .jdbc()
                  .update(
                      "UPDATE mail SET fields=?,proposal=NULL,stage='new' WHERE id=?",
                      ledger.write(s),
                      id);
              ledger
                  .jdbc()
                  .update(
                      "UPDATE wave_case SET reason=NULL,operator=0,queried=0,offers=NULL WHERE"
                          + " id=?",
                      id);
            });
  }

  private boolean sameNumber(BigDecimal a, BigDecimal b) {
    return a != null && b != null && a.compareTo(b) == 0;
  }

  private void save(long id, Shipment fields, Draft draft, boolean operator) {
    ledger
        .transactions()
        .executeWithoutResult(
            t -> {
              if (ledger.delivery("quote:" + id) != null)
                throw new Conflict("A quote is already claimed.");
              ledger
                  .jdbc()
                  .update(
                      "UPDATE mail SET proposal=?,fields=?,stage=? WHERE id=?",
                      ledger.write(draft),
                      ledger.write(fields),
                      operator ? "operator review" : "collecting",
                      id);
              ledger
                  .jdbc()
                  .update(
                      "UPDATE wave_case SET operator=?,reason=? WHERE id=?",
                      operator ? 1 : 0,
                      draft.notes().isEmpty() ? null : String.join(" ", draft.notes()),
                      id);
            });
  }

  synchronized Draft price(long id, Shipment shipment, ManualBid bid) {
    gateway.ensure(id, target(id), false);
    var draft = calculate(id, shipment, bid, false);
    save(id, shipment, draft, true);
    return draft;
  }

  private Map<String, Object> send(String key, Map<String, Object> mail) {
    var retry =
        ledger.jdbc().queryForList("SELECT after_epoch FROM retry WHERE action=?", Long.class, key);
    if (!retry.isEmpty() && retry.getFirst() > clock.instant().getEpochSecond())
      throw new RetryLater();
    if (ledger
            .jdbc()
            .update(
                "INSERT OR IGNORE INTO deliveries(action,state,payload,attempted_at)"
                    + " VALUES(?,'pending',?,?)",
                key,
                ledger.write(mail),
                clock.instant().toString())
        == 0) throw new Conflict("This send was already claimed. Check the delivery record.");
    try {
      var receipt = upstream.post("/v1/mailbox/messages", mail);
      ledger
          .jdbc()
          .update(
              "UPDATE deliveries SET state='sent',response=? WHERE action=?",
              ledger.write(receipt),
              key);
      return Map.of("state", "sent", "id", receipt.get("id"));
    } catch (HttpClientErrorException ex) {
      if (ex.getStatusCode().value() == 429) {
        String header =
            ex.getResponseHeaders() == null
                ? null
                : ex.getResponseHeaders().getFirst("Retry-After");
        long delay = header != null && header.matches("\\d+") ? Long.parseLong(header) : 10;
        ledger
            .transactions()
            .executeWithoutResult(
                t -> {
                  ledger
                      .jdbc()
                      .update(
                          "INSERT OR REPLACE INTO retry VALUES(?,?)",
                          key,
                          clock.instant().getEpochSecond() + Math.max(delay, 1));
                  ledger.jdbc().update("DELETE FROM deliveries WHERE action=?", key);
                });
        throw new RetryLater();
      }
      if (ex.getStatusCode().value() != 408) {
        ledger.jdbc().update("UPDATE deliveries SET state='rejected' WHERE action=?", key);
        throw new Conflict("Mailbox rejected send (HTTP " + ex.getStatusCode().value() + ").");
      }
    } catch (RestClientException ex) {
      /* An upstream send may have succeeded. */
    }
    ledger.jdbc().update("UPDATE deliveries SET state='uncertain' WHERE action=?", key);
    throw new Conflict("Mailbox did not confirm delivery. Verify before resending.");
  }

  synchronized Map<String, Object> sendQuote(long id, String revision) {
    var m = target(id);
    var d = ledger.read(m.get("proposal"), Draft.class);
    if (d == null || d.offer() == null || !d.revision().equals(revision))
      throw new Conflict("Calculate a current quote first.");
    if (d.offer().validUntil() != null && !d.offer().validUntil().isAfter(clock.instant()))
      throw new Conflict("Portal rate expired.");
    gateway.ensure(id, m, false);
    target(id);
    var result =
        send(
            "quote:" + id,
            Map.of(
                "to",
                m.get("sender"),
                "in_reply_to",
                id,
                "subject",
                "Re: " + m.get("subject"),
                "body",
                d.body()));
    ledger.stage(id, "sent", null);
    return result;
  }

  synchronized Map<String, Object> invite(long id, Shipment s, Catalog.Carrier c) {
    var m = target(id);
    if (catalog.problem(s) != null || !c.serves(c.truck() ? s.pickupZone() : s.destination()))
      throw new IllegalArgumentException("Check shipment and carrier coverage.");
    String leg =
        c.truck()
            ? "pickup from " + pickupLocation(m, s.pickupZone()) + " to " + s.origin()
            : s.origin() + " to " + s.destination();
    ledger
        .jdbc()
        .update(
            "INSERT OR IGNORE INTO tender(request_id,email,leg,terms) VALUES(?,?,?,?)",
            id,
            c.email(),
            c.kind(),
            ledger.write(s));
    String service =
        c.truck()
            ? "collection from "
                + pickupLocation(m, s.pickupZone())
                + " and delivery to "
                + s.origin()
            : "air freight from " + leg;
    return send(
        "bid:" + id + ":" + c.email(),
        Map.of(
            "to",
            c.email(),
            "subject",
            "Rate request AEIOU-" + id + " | " + leg,
            "body",
            "Hi,\n\nCould you quote "
                + service
                + " for a shipment weighing "
                + s.weightKg().stripTrailingZeros().toPlainString()
                + " kg gross, with a total volume of "
                + s.volumeCbm().stripTrailingZeros().toPlainString()
                + " cbm?"
                + "\n\nPlease send your all-in rate in EUR and your estimated transit time."
                + "\n\nThanks,\nTeam AEIOU"));
  }

  synchronized Map<String, Object> reply(long id, String body) {
    var m = ledger.mail(id);
    if ("request".equals(m.get("kind")) || body == null || body.isBlank())
      throw new Conflict("Use the quote workflow for RFQs. Replies need a body.");
    return send(
        "reply:" + id,
        Map.of(
            "to",
            m.get("sender"),
            "in_reply_to",
            id,
            "subject",
            "Re: " + m.get("subject"),
            "body",
            body));
  }
}
