package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

@Service
class WaveWorker {
  private static final Logger log = LoggerFactory.getLogger(WaveWorker.class);
  private static final long PORTAL_COMPARISON_SECONDS = 95;
  private static final int MAILBOX_PAGE_SIZE = 200;
  private static final long MAILBOX_POLL_SECONDS = 6;
  private final Ledger ledger;
  private final Catalog catalog;
  private final FiveU upstream;
  private final Clock clock;
  private final RfqGateway gateway;
  private final MailboxWebhook webhook;
  private final boolean automatic;
  private final Instant start;
  private final long waitSeconds;
  private final boolean counteroffers;
  private final ShipmentDocuments documents;
  private final CustomerClarifications clarifications;
  private final CarrierRestrictions carrierRestrictions;
  // Cloudline HTML quotes; plain text until a 5U test thread shows the HTML rendered.
  @Value("${dispatch.quote-html:false}")
  boolean quoteHtml;
  private volatile Instant lastPoll;
  private volatile String error;
  private Instant pollAfter = Instant.EPOCH;

  WaveWorker(
      Ledger ledger, Catalog catalog, FiveU upstream, Clock clock, RfqGateway gateway,
      MailboxWebhook webhook, boolean automatic, Instant start, long waitSeconds, boolean counteroffers) {
    this(ledger, catalog, upstream, clock, gateway, webhook, automatic, start, waitSeconds,
        counteroffers, Runnable::run, false);
  }

  WaveWorker(
      Ledger ledger, Catalog catalog, FiveU upstream, Clock clock, RfqGateway gateway, MailboxWebhook webhook,
      boolean automatic, Instant start, long waitSeconds, boolean counteroffers, Executor voiceExecutor,
      boolean packingTools) {
    this(ledger, catalog, upstream, clock, gateway, webhook, automatic, start, waitSeconds,
        counteroffers, voiceExecutor, packingTools, "", voiceExecutor, false);
  }

  @org.springframework.beans.factory.annotation.Autowired
  WaveWorker(
      Ledger ledger,
      Catalog catalog,
      FiveU upstream,
      Clock clock,
      RfqGateway gateway,
      MailboxWebhook webhook,
      @Value("#{${dispatch.auto} and !${dispatch.read-only}}") boolean automatic,
      @Value("${dispatch.start-at}") Instant start,
      @Value("${dispatch.bid-wait-seconds}") long waitSeconds,
      @Value("${dispatch.counteroffers}") boolean counteroffers,
      @org.springframework.beans.factory.annotation.Qualifier("voiceExecutor") Executor voiceExecutor,
      @Value("${dispatch.packing-tools:false}") boolean packingTools,
      @Value("${dispatch.edge-reader-lib:}") String edgeReaderLibrary,
      @org.springframework.beans.factory.annotation.Qualifier("edgeExecutor") Executor edgeExecutor,
      @Value("${dispatch.edge-reader-gateway:false}") boolean edgeGateway) {
    this.ledger = ledger;
    this.catalog = catalog;
    this.upstream = upstream;
    this.clock = clock;
    this.gateway = gateway;
    this.webhook = webhook;
    this.automatic = automatic;
    this.start = start;
    this.waitSeconds = waitSeconds;
    this.counteroffers = counteroffers;
    this.documents = new ShipmentDocuments(ledger, upstream, catalog, clock, voiceExecutor, packingTools,
        edgeExecutor, EdgeAttachmentReader.fromLibrary(edgeReaderLibrary, edgeGateway), edgeGateway);
    this.clarifications = new CustomerClarifications(ledger, catalog);
    this.carrierRestrictions = new CarrierRestrictions(ledger, upstream, clock);
  }

  record Status(
      Instant lastPoll,
      Instant lastWebhook,
      boolean webhookEnabled,
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
        webhook.lastProcessed(),
        webhook.enabled(),
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
    if (automatic) {
      var receivedAfter = clock.instant().minusSeconds(355);
      gateway.recheckOpenConflicts(receivedAfter);
      ledger.recheckOpenTruckBids(receivedAfter, catalog);
      ledger.jdbc().update("""
          DELETE FROM document_check WHERE request_id IN (
            SELECT m.id FROM mail m JOIN wave_case w ON w.id=m.id
            WHERE m.stage='waiting for documents' AND w.operator=0 AND julianday(m.received)>julianday(?)
              AND w.reason IN ('Waiting for a readable packing specification and cargo classification.',
                'Waiting for consistent shipment details.')
          )
          """, receivedAfter.toString());
    }
  }

  @Scheduled(fixedDelayString = "${dispatch.poll-ms}")
  void poll() {
    if (webhook.enabled() || clock.instant().isBefore(pollAfter)) return;
    try {
      int cursor = ledger.cursor();
      var page = upstream.get("/v1/mailbox/messages?since_id={id}&limit=" + MAILBOX_PAGE_SIZE, cursor);
      ledger.ingest(page, catalog, clock.instant());
      lastPoll = clock.instant();
      error = null;
      pollAfter = lastPoll.plusSeconds(MAILBOX_POLL_SECONDS);
    } catch (RuntimeException ex) {
      error = "Mailbox poll failed (" + ex.getClass().getSimpleName() + ")";
      log.warn("{}", error);
      long interval = MAILBOX_POLL_SECONDS;
      if (ex instanceof HttpClientErrorException.TooManyRequests limit
          && limit.getResponseHeaders() != null) {
        String retry = limit.getResponseHeaders().getFirst("Retry-After");
        if (retry != null && retry.matches("[0-9]{1,9}"))
          interval = Math.max(interval, Long.parseLong(retry));
      }
      pollAfter = clock.instant().plusSeconds(interval);
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
                SELECT m.id FROM mail m JOIN wave_case w ON m.id=w.id
                LEFT JOIN document_check p ON p.request_id=m.id
                WHERE w.operator=0 AND (
                  m.stage IN ('new','collecting','waiting for pickup','waiting for air price','waiting for customer')
                  OR (m.stage IN ('review','waiting for documents') AND julianday(m.received)>julianday(?) AND (
                    w.reason IN ('Pickup requested. Confirm the pickup postcode or zone.',
                      'Confirm the pickup requirement and postcode.', 'Confirm origin and destination airports.',
                      'Confirm total gross weight in kg.', 'Confirm total shipment volume in cubic metres.',
                      'Confirm cheapest or fastest service.', 'Confirm the pickup zone and matching airport.') OR
                    (p.request_id IS NULL AND w.reason IN (
                      'Review the attached shipment specifications.',
                      'Packing attachment could not be checked for dangerous goods.',
                      'Waiting for a readable packing specification and cargo classification.',
                      'Waiting for consistent shipment details.',
                      'Dangerous goods indicated. Hold pending documents and carrier review.'))
                    OR (p.request_id IS NOT NULL AND EXISTS (
                      SELECT 1 FROM mail f WHERE f.kind='follow-up' AND f.sender=m.sender
                      AND f.id>p.checked_through AND f.root IN (m.id,
                        (SELECT json_extract(response,'$.id') FROM deliveries WHERE action='dg-docs:'||m.id),
                        (SELECT json_extract(response,'$.id') FROM deliveries WHERE action='packing-copy:'||m.id),
                        (SELECT json_extract(response,'$.id') FROM deliveries WHERE action='packing-correction:'||m.id),
                        (SELECT json_extract(response,'$.id') FROM deliveries WHERE action='dg-incomplete:'||m.id))
                    ))
                  ))
                ) ORDER BY m.id
                """,
                Long.class,
                clock.instant().minusSeconds(360).toString())) {
      try {
        advance(id);
      } catch (RetryLater ex) {
        /* Confirmed rate limit. Try again on a later tick. */
      } catch (DocumentHold ex) {
        // The document workflow already persisted its stage and reply.
      } catch (ExcludedCarrier ex) {
        // Recalculate after restrictions changed while the draft was being sent.
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

  private static class ExcludedCarrier extends Conflict {
    ExcludedCarrier() {
      super("This customer excluded the selected carrier.");
    }
  }

  private static class DocumentHold extends Conflict {
    DocumentHold(String reason) {
      super(reason);
    }
  }

  static class RetryLater extends Conflict {
    RetryLater() {
      this("Mailbox rate limit. Waiting to retry.");
    }

    RetryLater(String message) {
      super(message);
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

  private Shipment checkPacking(long id, Map<String, Object> mail) {
    return checkPacking(id, mail, details(id, mail));
  }

  private CustomerClarifications.Details details(long id, Map<String, Object> mail) {
    return clarifications.details(id, documents.withAttachedRequest(mail));
  }

  private Shipment checkPacking(long id, Map<String, Object> mail, CustomerClarifications.Details details) {
    if (documents.awaitingVoice(details.message()))
      throw new RetryLater("Transcribing the attached voice request.");
    var replies =
        ledger
            .jdbc()
            .queryForList(
                """
                SELECT f.* FROM mail f WHERE f.kind='follow-up' AND f.sender=? AND f.id>? AND
                (f.root=? OR f.root IN (SELECT json_extract(response,'$.id') FROM deliveries
                  WHERE action IN (?,?,?,?))) ORDER BY f.id
                """,
                mail.get("sender"),
                id,
                id,
                "dg-docs:" + id,
                "dg-incomplete:" + id,
                "packing-copy:" + id,
                "packing-correction:" + id);
    var decision = documents.inspect(details.message(), replies,
        details.pickupReply() == null ? null : details.zone(), details.deliveryAirport());
    if (!decision.holdsQuote()) return decision.shipment();
    if (automatic
        && !Ledger.str(mail, "sender").isBlank()
        && !Ledger.instant(mail.get("received")).isBefore(start)
        && ledger.delivery("quote:" + id) == null
        && ledger.delivery(decision.action() + ":" + id) == null) {
      if (decision.gatewayReview() != null && clock.instant().isBefore(ageLimit(mail).minusSeconds(23))) {
        gateway.checkDangerousGoods(id, decision.gatewayReview());
        target(id);
      }
      long parent = replies.isEmpty() ? id : Ledger.id(replies.getLast().get("id"));
      send(
          decision.action() + ":" + id,
          Map.of(
              "to",
              mail.get("sender"),
              "in_reply_to",
              parent,
              "subject",
              "Re: " + mail.get("subject"),
              "body",
              Greeting.at(mail, clock.instant()) + "\n\n" + decision.reply()
                  + "\n\nBest regards,\nTeam AEIOU"));
    }
    long checked = replies.isEmpty() ? id : Ledger.id(replies.getLast().get("id"));
    ledger
        .transactions()
        .executeWithoutResult(
            tx -> {
              ledger.stage(id, decision.stage(), decision.reason());
              ledger
                  .jdbc()
                  .update("INSERT OR REPLACE INTO document_check VALUES(?,?)", id, checked);
            });
    throw new DocumentHold(decision.reason());
  }

  private void checkPacking(long id, Map<String, Object> mail, Shipment requested) {
    var verified = checkPacking(id, mail);
    if (verified == null) return;
    if (!verified.origin().equals(requested.origin())
        || !verified.destination().equals(requested.destination())
        || !sameNumber(verified.weightKg(), requested.weightKg())
        || !sameNumber(verified.volumeCbm(), requested.volumeCbm())
        || !Objects.equals(verified.pickupZone(), requested.pickupZone())
        || !verified.preference().equals(requested.preference()))
      throw new Conflict("Shipment differs from the verified packing documents.");
  }

  private void askCustomer(long id, Map<String, Object> mail, CustomerClarifications.Details details,
      String question, boolean missingPostcode) {
    String prefix = (missingPostcode ? "pickup-postcode:" : "rfq-info:") + id;
    String action = details.latestReply() == null ? prefix : prefix + ":" + details.latestReply();
    boolean alreadyClaimed = ledger.delivery(action) != null;
    if (alreadyClaimed && CustomerClarifications.WAITING.equals(mail.get("stage"))) return;
    String body = Greeting.at(mail, clock.instant()) + "\n\n" + question + "\n\nBest regards,\nTeam AEIOU";
    if (details.latestReply() != null && ledger.jdbc().queryForObject("""
        SELECT count(*) FROM deliveries WHERE (action=? OR action LIKE ?)
          AND json_extract(payload,'$.body')=?
        """, Integer.class, prefix, prefix + ":%", body) >= 2) {
      if (!CustomerClarifications.WAITING.equals(mail.get("stage")))
        ledger.stage(id, CustomerClarifications.WAITING, question);
      return;
    }
    ledger.stage(id, CustomerClarifications.WAITING, question);
    if (alreadyClaimed) return;
    try {
      send(action, Map.of(
          "to", mail.get("sender"), "in_reply_to", details.latestReply() == null ? id : details.latestReply(),
          "subject", "Re: " + mail.get("subject"),
          "body", body));
    } catch (RetryLater retry) {
      throw retry;
    } catch (Conflict ex) {
      ledger.stage(id, CustomerClarifications.WAITING, ex.getMessage());
    }
  }

  private void applyCustomerReply(long id, long replyId, MailParser.Parsed parsed) {
    boolean keepAirRates = sameAirShipment(
        ledger.read(ledger.mail(id).get("fields"), Shipment.class), parsed.shipment());
    ledger.transactions().executeWithoutResult(tx -> {
      ledger.jdbc().update("UPDATE mail SET fields=?,stage='new',proposal=NULL WHERE id=?",
          ledger.write(parsed.shipment()), id);
      ledger.jdbc().update(
          """
          UPDATE wave_case SET customer_reply=?,reason=?,offers=CASE WHEN ? THEN offers END,
            queried=CASE WHEN ? THEN queried ELSE 0 END,
            portal_version=CASE WHEN ? THEN portal_version END WHERE id=?
          """, replyId, parsed.issue(), keepAirRates, keepAirRates, keepAirRates, id);
      if (parsed.issue() == null)
        ledger.jdbc().update("""
            INSERT OR IGNORE INTO rfq_parse(request_id,state,model) VALUES(?,'done','java')
            """, id);
    });
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
    if (gateway.quoteCoolingDown(id)) return;
    var details = details(id, m);
    var local = MailParser.request(Ledger.str(details.message(), "subject"), Ledger.str(details.message(), "body"),
        false, catalog, details.pickupReply() == null ? null : details.zone(), details.deliveryAirport());
    var verified = checkPacking(id, m, details);
    var w = ledger.jdbc().queryForMap("SELECT * FROM wave_case WHERE id=?", id);
    boolean missingPostcode = clarifications.needsPostcode(details,
        ledger.read(m.get("fields"), Shipment.class), local.issue());
    if (missingPostcode) {
      String question = clarifications.question(local.shipment(), local.issue(), true);
      if (question != null) { askCustomer(id, m, details, question, true); return; }
    }
    if (details.latestReply() != null && (w.get("customer_reply") == null
        || Ledger.id(w.get("customer_reply")) != details.latestReply())) {
      applyCustomerReply(id, details.latestReply(),
          verified == null ? local : new MailParser.Parsed(verified, null));
      m = ledger.mail(id);
    }
    if (verified == null) {
      if (details.latestReply() == null && !CustomerClarifications.WAITING.equals(m.get("stage")))
        gateway.ensure(id, details.message(), true);
    } else if (List.of("new", "review", "waiting for documents").contains(m.get("stage"))) {
      ledger
          .transactions()
          .executeWithoutResult(
              tx -> {
                ledger
                    .jdbc()
                    .update(
                        "UPDATE mail SET fields=?,stage='new' WHERE id=?",
                        ledger.write(verified),
                        id);
                ledger.jdbc().update("UPDATE wave_case SET reason=NULL WHERE id=?", id);
                ledger
                    .jdbc()
                    .update(
                        "INSERT OR IGNORE INTO rfq_parse(request_id,state,model)"
                            + " VALUES(?,'done','java')",
                        id);
              });
    }
    m = target(id);
    w = ledger.jdbc().queryForMap("SELECT * FROM wave_case WHERE id=?", id);
    var s = ledger.read(m.get("fields"), Shipment.class);
    String issue = catalog.problem(s);
    if (Set.of("new", "review").contains(m.get("stage")) && w.get("reason") != null)
      issue = w.get("reason").toString();
    if (CustomerClarifications.WAITING.equals(m.get("stage"))) issue = local.issue();
    missingPostcode = clarifications.needsPostcode(details, s, issue);
    if (issue != null || missingPostcode) {
      String question = clarifications.question(s, issue, missingPostcode);
      if (question != null) askCustomer(id, m, details, question, missingPostcode);
      else ledger.stage(id, "review", issue);
      return;
    }
    sendInvitations(id, s, details, deadline);
    boolean transcribingBids = recoverAttachedBids(id);
    var tenders = ledger.tenders(id);
    boolean fastestPickup = s.pickupZone() != null && "fastest".equals(s.preference());
    if (fastestPickup)
      askTruckTransit(id, s, details, tenders, deadline, false);
    long queried = Ledger.id(w.get("queried"));
    var offers = new ArrayList<Offer>(Arrays.asList(cached(w)));
    if (queried == 0
        || (queried > 0
            && clock.instant().getEpochSecond() - queried >= 35
            && clock.instant().isBefore(deadline.minusSeconds(35)))) {
      var now = clock.instant();
      var result = offers.isEmpty() ? upstream.rates(s, now)
          : upstream.rates(s, now, offers, (String) w.get("portal_version"));
      if (result.reset()) offers.clear();
      for (var o : result.offers()) {
        offers.removeIf(
            old ->
                old.airline().equals(o.airline()) && old.billedKg().compareTo(o.billedKg()) == 0);
        offers.add(o);
      }
      queried = result.complete() ? -1 : clock.instant().getEpochSecond();
      if (result.reset() && offers.isEmpty()) queried = 0;
      ledger
          .jdbc()
          .update(
              "UPDATE wave_case SET offers=?,queried=?,portal_version=? WHERE id=?",
              ledger.write(offers),
              queried,
              result.version(),
              id);
    }
    var coveredAirlines = new HashSet<String>();
    if (queried == -1) {
      var validAt = clock.instant().plusSeconds(4);
      for (var offer : offers) {
        if (offer.rateId() != null && !offer.rateId().isBlank() && offer.validUntil() != null
            && offer.validUntil().isAfter(validAt))
          coveredAirlines.add(offer.airline().toLowerCase(Locale.ROOT));
      }
    }
    boolean allAnswered = tenders.stream().allMatch(t -> t.get("reply_id") != null
        || catalog.contact(Ledger.str(t, "email"))
            .filter(c -> !c.truck() && coveredAirlines.contains(c.name().toLowerCase(Locale.ROOT)))
            .isPresent());
    Instant firstInvitation = ledger.firstInvitation(id);
    Instant waitUntil = (firstInvitation == null ? received : firstInvitation).plusSeconds(waitSeconds);
    if (waitUntil.isAfter(deadline.minusSeconds(20))) waitUntil = deadline.minusSeconds(20);
    if ((!allAnswered && clock.instant().isBefore(waitUntil))
        || (transcribingBids && clock.instant().isBefore(deadline.minusSeconds(20)))) {
      ledger.stage(id, "collecting", null);
      return;
    }
    var draft = calculate(id, s, null, true);
    if (draft.offer() == null) {
      String reason = String.join(" ", draft.notes());
      if (fastestPickup && draft.pickupEur() == null)
        askTruckTransit(id, s, details, tenders, deadline, true);
      if (s.pickupZone() != null && draft.pickupEur() == null && details.area() != null) {
        askAirportDelivery(id, m, details, s.origin());
        if ("sent".equals(ledger.delivery("airport-delivery:" + id)))
          reason += " Airport delivery offered as an alternative.";
      }
      ledger.stage(
          id, reason.contains("pickup") ? "waiting for pickup" : "waiting for air price", reason);
      return; // Late valid bids can still close an exception before expiry.
    }
    // Leave time for the gateway check and mailbox send before the 120-second scoring cutoff.
    if (queried != -1 && clock.instant().isBefore(received.plusSeconds(PORTAL_COMPARISON_SECONDS))) {
      ledger.stage(id, "collecting", null);
      return;
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
    sendQuote(id, draft.revision(), true);
  }

  private void askAirportDelivery(long id, Map<String, Object> mail,
      CustomerClarifications.Details details, String airport) {
    String action = "airport-delivery:" + id;
    if (ledger.delivery(action) != null) return;
    try {
      send(action, Map.of(
          "to", mail.get("sender"), "in_reply_to", details.latestReply(),
          "subject", "Re: " + mail.get("subject"),
          "body", Greeting.at(mail, clock.instant()) + "\n\nThanks for confirming " + details.area()
              + ". We’re checking citywide"
              + " collection rates. If the warehouse postcode becomes available, please send all five"
              + " digits. If you can bring the shipment to " + airport + " yourselves instead,"
              + " please reply with: We will deliver to " + airport + " ourselves.\n\n"
              + "With your confirmation, we can quote the airport-to-airport service."
              + "\n\nBest regards,\nTeam AEIOU"));
    } catch (Conflict ex) {
      log.info("RFQ {} airport delivery option: {}", id, ex.getMessage());
    }
  }

  private void askTruckTransit(long id, Shipment shipment, CustomerClarifications.Details details,
      List<Map<String, Object>> tenders, Instant deadline, boolean inviteAlternatives) {
    var excluded = carrierRestrictions.forRequest(id, details.message());
    var active = new HashSet<String>();
    boolean awaitingReply = false;
    for (var tender : tenders) {
      if (!clock.instant().isBefore(deadline.minusSeconds(38))) return;
      if (!"trucker".equals(tender.get("leg")) || !matchesTender(tender, shipment, details, true)) continue;
      var carrier = catalog.contact(Ledger.str(tender, "email")).orElse(null);
      if (carrier == null || carrierRestrictions.excludes(excluded, carrier.name())) continue;
      active.add(carrier.email());
      if (tender.get("reply_id") == null) {
        awaitingReply |= stillAwaitingCarrier(Ledger.bidAction(tender));
        continue;
      }
      if (tender.get("days") != null) continue;
      long parent = Ledger.id(tender.get("reply_id"));
      var reply = ledger.mail(parent);
      if (documents.awaitingVoice(reply)) continue;
      var files = ledger.read(reply.get("attachments"), List.class);
      if (tender.get("euros") == null && (files == null || files.isEmpty())) continue;
      String text = reply.get("carrier_offer_text") == null
          ? Ledger.str(reply, "body") : Ledger.str(reply, "carrier_offer_text");
      if (!inviteAlternatives && !MailParser.roadTransitEvidence(text).isEmpty()) continue;
      String action = Ledger.bidAction(tender) + ":transit";
      if (ledger.delivery(action) != null) {
        awaitingReply |= stillAwaitingCarrier(action);
        continue;
      }
      try {
        send(action, Map.of(
            "to", tender.get("email"), "in_reply_to", parent,
            "subject", reply.get("subject"),
            "body", "Hi,\n\nThanks for the pickup quote. Could you confirm the estimated transit time"
                + " in hours from actual pickup to delivery at " + shipment.origin() + " airport,"
                + " excluding the wait before collection? Please include the road transit, all-in EUR"
                + " total and validity directly in the reply text."
                + (details.area() != null ? " Please confirm both terms apply throughout " + details.area() + "." : "")
                + "\n\nThanks,\nTeam AEIOU"));
        awaitingReply = true;
      } catch (Conflict ex) {
        log.info("RFQ {} truck transit clarification: {}", id, ex.getMessage());
      }
    }
    if (awaitingReply || !inviteAlternatives) return;
    long initialSlots = invitations(id, shipment, false).stream().filter(Catalog.Carrier::truck).count();
    for (var carrier : catalog.carriers) {
      if (active.size() >= initialSlots + 2 || !clock.instant().isBefore(deadline.minusSeconds(38))) return;
      if (!carrier.truck() || !carrier.serves(shipment.pickupZone())
          || carrierRestrictions.excludes(excluded, carrier.name()) || !active.add(carrier.email())) continue;
      try {
        invite(id, shipment, carrier, details);
      } catch (Conflict ex) {
        log.info("RFQ {} replacement trucker {}: {}", id, carrier.name(), ex.getMessage());
      }
    }
  }

  private boolean stillAwaitingCarrier(String action) {
    return ledger.jdbc().queryForList(
        "SELECT 1 FROM deliveries WHERE action=? AND state='sent' AND julianday(attempted_at)>julianday(?)",
        Integer.class, action, clock.instant().minusSeconds(35).toString()).size() == 1;
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

  private List<Catalog.Carrier> invitations(long id, Shipment shipment, boolean confirmedPickup) {
    var excluded = carrierRestrictions.forRequest(id, ledger.mail(id));
    var selected = catalog.invitations(shipment, ledger.airlineRoutesBefore(id),
        carrier -> !carrierRestrictions.excludes(excluded, carrier.name()));
    if (!confirmedPickup || shipment.pickupZone() == null) return selected;
    var airlines = selected.stream().filter(c -> !c.truck()).toList();
    var trucks = new LinkedHashSet<Catalog.Carrier>();
    for (var tender : ledger.tenders(id))
      catalog.contact(Ledger.str(tender, "email"))
          .filter(c -> c.truck() && c.serves(shipment.pickupZone()))
          .filter(c -> !carrierRestrictions.excludes(excluded, c.name())).ifPresent(trucks::add);
    selected.stream().filter(Catalog.Carrier::truck).forEach(trucks::add);
    var result = new ArrayList<>(airlines);
    result.addAll(trucks.stream().limit(5 - airlines.size()).toList());
    return result;
  }

  private void sendInvitations(long id, Shipment shipment, CustomerClarifications.Details details,
      Instant deadline) {
    var carriers = invitations(id, shipment, details.pickupReply() != null);
    if (details.pickupReply() != null)
      carriers = carriers.stream().sorted(Comparator.comparing(c -> !c.truck())).toList();
    for (var carrier : carriers) {
      if (clock.instant().isAfter(deadline.minusSeconds(8))) return;
      try {
        invite(id, shipment, carrier, details);
      } catch (Conflict ex) {
        log.info("RFQ {} bid to {}: {}", id, carrier.name(), ex.getMessage());
      }
    }
  }

  private boolean sameAirShipment(Shipment first, Shipment second) {
    return first != null && Objects.equals(first.origin(), second.origin())
        && Objects.equals(first.destination(), second.destination())
        && sameNumber(first.weightKg(), second.weightKg())
        && sameNumber(first.volumeCbm(), second.volumeCbm());
  }

  private boolean bidHasPickupLocation(String action, String location) {
    return !ledger.jdbc().queryForList(
        "SELECT 1 FROM deliveries WHERE action=? AND instr(json_extract(payload,'$.subject'),?)>0",
        Integer.class, action, "pickup from " + location + " to ").isEmpty();
  }

  private boolean matchesTender(Map<String, Object> tender, Shipment shipment,
      CustomerClarifications.Details details, boolean truck) {
    var prior = ledger.read(tender.get("terms"), Shipment.class);
    return sameAirShipment(prior, shipment) && (!truck || (shipment.pickupZone() != null
        && Objects.equals(shipment.pickupZone(), prior.pickupZone())
        && (details.area() == null || Ledger.bidAction(tender).endsWith(":area"))
        && (details.pickupReply() == null || bidHasPickupLocation(Ledger.bidAction(tender), details.location(shipment.pickupZone())))));
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
      Double pickupDays,
      Instant pickupValidUntil,
      String pickupWaitingDisclosure) {
    Draft(String revision, Offer offer, List<Offer> alternatives, List<String> notes, String body,
        String trucker, BigDecimal pickupEur, Double pickupDays, Instant pickupValidUntil) {
      this(revision, offer, alternatives, notes, body, trucker, pickupEur, pickupDays,
          pickupValidUntil, null);
    }

    Offer allIn(Shipment shipment, Offer air) {
      if (shipment.pickupZone() == null) return air;
      return air.addPickup(shipment.withPickup(pickupEur, pickupDays == null ? 0 : pickupDays),
          pickupValidUntil);
    }

    Draft withOffer(Offer current, List<Offer> options, String quote) {
      return new Draft(revision, current, List.copyOf(options), notes, quote, trucker,
          pickupEur, pickupDays, pickupValidUntil, pickupWaitingDisclosure);
    }

    boolean matchesQuotedTerms(Offer current) {
      return offer.airline().equalsIgnoreCase(current.airline())
          && offer.totalEur().compareTo(current.totalEur()) == 0
          && Math.abs(offer.days() - current.days()) < 1e-8
          && offer.billedKg().compareTo(current.billedKg()) == 0;
    }
  }

  private record TruckBidReview(
      long replyId, String carrier, String body, BigDecimal euros, Offer offer, Map<String, Double> evidence) {
    double shortestDuration() {
      return Collections.min(evidence.values());
    }

    Offer withTransit(double days) {
      return new Offer(offer.airline(), days, offer.totalEur(), offer.billedKg(), offer.source(),
          offer.rateId(), offer.validUntil());
    }
  }

  private boolean recoverAttachedBids(long id) {
    boolean awaitingVoice = false;
    for (var reply : ledger.jdbc().queryForList("""
        SELECT r.*, t.leg FROM tender t JOIN mail r
          ON r.id=t.reply_id AND r.sender=t.email AND r.root=t.request_id
        WHERE t.request_id=? AND t.euros IS NULL AND r.kind='carrier'
          AND json_array_length(r.attachments)=1
        """, id)) {
      boolean truck = "trucker".equals(reply.get("leg"));
      var text = documents.attachedOffer(reply, truck);
      if (text != null)
        ledger.recoverAttachedBid(id, Ledger.id(reply.get("id")), Ledger.str(reply, "sender"),
            text, truck, catalog, clock.instant());
      else awaitingVoice |= documents.awaitingVoice(reply);
    }
    return awaitingVoice;
  }

  private Draft calculate(long id, Shipment s, ManualBid manual, boolean cachedPortal) {
    String problem = catalog.problem(s);
    if (problem != null) throw new IllegalArgumentException(problem);
    var m = target(id);
    var excluded = carrierRestrictions.forRequest(id, m);
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
        .filter(o -> cachedPortal
            || (o.validUntil() != null && o.validUntil().isAfter(clock.instant().plusSeconds(4))))
        .forEach(offers::add);
    offers.removeIf(offer -> carrierRestrictions.excludes(excluded, offer.airline()));
    var details = details(id, m);
    var airBids = new ArrayList<Offer>();
    var trucks = new ArrayList<Offer>();
    var truckReviews = new ArrayList<TruckBidReview>();
    var pickupWaitingDisclosures = new HashMap<String, String>();
    boolean fastest = "fastest".equals(s.preference());
    var missingTruckTransit = new HashSet<String>();
    for (var t : ledger.jdbc().queryForList("""
        SELECT t.*,COALESCE(r.carrier_offer_text,r.body) AS reply_body,p.state AS parse_state FROM tender t
        JOIN mail r ON r.id=t.reply_id LEFT JOIN truck_bid_parse p ON p.reply_id=t.reply_id
        WHERE t.request_id=? AND t.euros IS NOT NULL ORDER BY t.email
        """, id)) {
      var c = catalog.contact(Ledger.str(t, "email")).orElseThrow();
      if (carrierRestrictions.excludes(excluded, c.name())) continue;
      if (!matchesTender(t, s, details, c.truck())) continue;
      String body = Ledger.str(t, "reply_body");
      Instant validUntil;
      try {
        validUntil = MailParser.bidValidUntil(body);
      } catch (java.time.format.DateTimeParseException ex) {
        continue;
      }
      if (validUntil != null && !validUntil.isAfter(clock.instant().plusSeconds(4))) continue;
      var offer =
          new Offer(
              c.name(),
              t.get("days") == null ? 0 : ((Number) t.get("days")).doubleValue(),
              new BigDecimal(t.get("euros").toString()),
              s.chargeableKg(),
              "carrier bid",
              null,
              validUntil);
      if (c.truck()) {
        String disclosure = MailParser.pickupWaitingDisclosure(body);
        if (disclosure != null) pickupWaitingDisclosures.put(c.name(), disclosure);
      }
      if (t.get("days") == null) {
        if (!c.truck()) continue;
        if (fastest) {
          if (cachedPortal && s.pickupEur() == null && t.get("parse_state") == null) {
            var evidence = MailParser.roadTransitEvidence(body);
            if (!evidence.isEmpty())
              truckReviews.add(new TruckBidReview(Ledger.id(t.get("reply_id")), c.email(), body,
                  new BigDecimal(Ledger.str(t, "euros")), offer, evidence));
          }
          continue;
        }
        missingTruckTransit.add(c.name());
      }
      (c.truck() ? trucks : airBids).add(offer);
    }
    if (manual != null) {
      if (manual.airline() == null
          || manual.airline().isBlank()
          || manual.airTotalEur() == null
          || manual.airTotalEur().signum() <= 0
          || manual.transitDays() <= 0)
        throw new IllegalArgumentException("Confirm carrier name, total and transit.");
      if (carrierRestrictions.excludes(excluded, manual.airline())) throw new ExcludedCarrier();
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
    if (!offers.isEmpty() || !airBids.isEmpty())
      resolveCompetitiveTruckBids(id, ageLimit(m), trucks, truckReviews);
    var plan = QuotePlanner.plan(s, offers, airBids, trucks, missingTruckTransit);
    var best = plan.best();
    var pickupDays = plan.pickupDays();
    String disclosure = pickupWaitingDisclosures.get(plan.trucker());
    String body = best == null ? "" : quoteBody(details, s, best, pickupDays, disclosure);
    return new Draft(
        UUID.randomUUID().toString(),
        best,
        plan.options(),
        plan.notes(),
        body,
        plan.trucker(),
        plan.pickupEur(),
        pickupDays,
        plan.pickupValidUntil(),
        disclosure);
  }

  private void resolveCompetitiveTruckBids(
      long id, Instant deadline, List<Offer> trucks, List<TruckBidReview> reviews) {
    var ranking = Offer.order("fastest");
    var best = trucks.stream().min(ranking).orElse(null);
    reviews.sort(Comparator.comparingDouble(TruckBidReview::shortestDuration)
        .thenComparing(review -> review.offer().totalEur()));
    for (var review : reviews) {
      var now = clock.instant();
      if (!now.isBefore(deadline.minusSeconds(38))) return;
      var optimistic = review.withTransit(review.shortestDuration());
      if (best != null && ranking.compare(optimistic, best) >= 0) continue;
      if (optimistic.validUntil() != null && !optimistic.validUntil().isAfter(now.plusSeconds(20)))
        continue;
      var days = gateway.recoverTruckTransit(id, review.replyId(), review.carrier(), review.body(),
          review.euros(), review.evidence());
      if (days == null) continue;
      var recovered = review.withTransit(days);
      if (recovered.validUntil() != null && !recovered.validUntil().isAfter(clock.instant().plusSeconds(4)))
        continue;
      trucks.add(recovered);
      if (best == null || ranking.compare(recovered, best) < 0) best = recovered;
    }
  }

  private String quoteBody(
      CustomerClarifications.Details details, Shipment s, Offer offer, Double pickupDays,
      String pickupWaitingDisclosure) {
    String weight = s.weightKg().stripTrailingZeros().toPlainString() + " kg shipment";
    String priority = "fastest".equals(s.preference()) ? "quickest option" : "lowest rate";
    String route = s.origin() + " to " + s.destination();
    String price = "EUR " + offer.totalEur().toPlainString() + " all in";
    String transit = offer.transitLabel();
    String service = "Allow about " + transit + " from airport to airport.";
    if (s.pickupZone() != null) {
      String location = details.location(s.pickupZone());
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
    return Greeting.at(details.message(), clock.instant())
        + "\n\nThe "
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
        + (pickupWaitingDisclosure == null ? "" : " " + pickupWaitingDisclosure)
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
    checkPacking(id, message, s);
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
                      "UPDATE wave_case SET reason=NULL,operator=0,queried=0,offers=NULL,"
                          + " portal_version=NULL WHERE id=?",
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
    var message = target(id);
    checkPacking(id, message, shipment);
    gateway.ensure(id, message, false);
    recoverAttachedBids(id);
    var draft = calculate(id, shipment, bid, false);
    save(id, shipment, draft, true);
    return draft;
  }

  private void checkSendRetry(String key) {
    var retry =
        ledger.jdbc().queryForList("SELECT after_epoch FROM retry WHERE action=?", Long.class, key);
    if (!retry.isEmpty() && retry.getFirst() > clock.instant().getEpochSecond())
      throw new RetryLater();
  }

  private Map<String, Object> send(String key, Map<String, Object> mail) {
    return send(key, mail, () -> ledger.jdbc().update(
        "INSERT OR IGNORE INTO deliveries(action,state,payload,attempted_at)"
            + " VALUES(?,'pending',?,?)", key, ledger.write(mail), clock.instant().toString()));
  }

  Map<String, Object> send(String key, Map<String, Object> mail, IntSupplier claim) {
    checkSendRetry(key);
    if (claim.getAsInt() == 0) throw new Conflict("This send was already claimed. Check the delivery record.");
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
    return sendQuote(id, revision, false);
  }

  private Map<String, Object> sendQuote(long id, String revision, boolean rerankPortal) {
    var m = target(id);
    String key = "quote:" + id;
    if (ledger.delivery(key) != null)
      throw new Conflict("This send was already claimed. Check the delivery record.");
    checkSendRetry(key);
    var shipment = ledger.read(m.get("fields"), Shipment.class);
    checkPacking(id, m, shipment);
    var d = ledger.read(m.get("proposal"), Draft.class);
    if (d == null || d.offer() == null || !d.revision().equals(revision))
      throw new Conflict("Calculate a current quote first.");
    checkCarrierRestrictions(id, m, d);
    var details = details(id, m);
    gateway.checkQuote(id, details.message(), shipment);
    boolean portal = "portal".equals(d.offer().source());
    if (portal) {
      d = rerankPortal
          ? rankCurrentPortalOffers(ageLimit(m), shipment, details, d)
          : verifyOperatorDraft(ageLimit(m), shipment, d);
    }
    String body = Greeting.refresh(d.body(), details.message(), clock.instant());
    boolean greetingChanged = !body.equals(d.body());
    if (greetingChanged) d = d.withOffer(d.offer(), d.alternatives(), body);
    if (portal || greetingChanged) save(id, shipment, d, !rerankPortal);
    checkCarrierRestrictions(id, target(id), d);
    if (d.offer().validUntil() != null && !d.offer().validUntil().isAfter(clock.instant()))
      throw new Conflict("Quote rate expired.");
    var result =
        send(
            key,
            Map.of(
                "to",
                m.get("sender"),
                "in_reply_to",
                id,
                "subject",
                "Re: " + m.get("subject"),
                "body",
                quoteHtml ? cloudline(d, shipment) : d.body()));
    ledger.stage(id, "sent", null);
    return result;
  }

  private Draft verifyOperatorDraft(Instant deadline, Shipment shipment, Draft draft) {
    Offer current;
    try {
      current = currentQuoteOffer(deadline, shipment, draft, draft.offer());
    } catch (RestClientException | IllegalArgumentException ex) {
      throw new Conflict("The carrier's current rate could not be verified. Compare rates again.");
    }
    if (!draft.matchesQuotedTerms(current))
      throw new Conflict("The carrier's terms changed. Compare rates again.");
    var options = draft.alternatives().stream()
        .map(offer -> offer.equals(draft.offer()) ? current : offer).toList();
    return draft.withOffer(current, options, draft.body());
  }

  private Draft rankCurrentPortalOffers(Instant deadline, Shipment shipment,
      CustomerClarifications.Details details, Draft draft) {
    var options = new ArrayList<>(draft.alternatives());
    var verified = new HashSet<Offer>();
    var ranking = Offer.order(shipment.preference());
    while (!options.isEmpty()) {
      var candidate = options.getFirst();
      if ("portal".equals(candidate.source()) && !verified.contains(candidate)) {
        options.removeFirst();
        try {
          var current = currentQuoteOffer(deadline, shipment, draft, candidate);
          options.add(current);
          verified.add(current);
          options.sort(ranking);
        } catch (RestClientException | IllegalArgumentException ex) {
          // Advance to the next candidate when the portal cannot verify this one.
        }
      } else if (candidate.validUntil() == null
          || candidate.validUntil().isAfter(clock.instant().plusSeconds(4))) {
        return draft.withOffer(candidate, options, quoteBody(details, shipment, candidate,
            draft.pickupDays(), draft.pickupWaitingDisclosure()));
      } else {
        options.removeFirst();
      }
    }
    throw new Conflict("No current rate could be confirmed.");
  }

  private Offer currentQuoteOffer(Instant deadline, Shipment shipment, Draft draft, Offer candidate) {
    var now = clock.instant();
    if (!now.isBefore(deadline.minusSeconds(8)))
      throw new Conflict("The quote window closed before a live rate could be confirmed.");
    if (draft.pickupValidUntil() != null && !draft.pickupValidUntil().isAfter(now.plusSeconds(4)))
      throw new Conflict("The pickup rate expired. Compare rates again.");
    var current = draft.allIn(shipment, upstream.currentRate(shipment, candidate, now));
    if (!current.validUntil().isAfter(clock.instant().plusSeconds(4)))
      throw new IllegalArgumentException("Rate expired during verification");
    return current;
  }

  private void checkCarrierRestrictions(long id, Map<String, Object> mail, Draft draft) {
    var restrictions = carrierRestrictions.forRequest(id, mail);
    if (carrierRestrictions.excludes(restrictions, draft.offer().airline())
        || (draft.trucker() != null && carrierRestrictions.excludes(restrictions, draft.trucker())))
      throw new ExcludedCarrier();
  }

  // The all-in total exactly as quoteBody writes it, so Cloudline can bold it.
  static String cloudline(Draft d, Shipment s) {
    return Cloudline.renderMailHtml(
        d.body(), "EUR " + d.offer().totalEur().toPlainString(), "en", "cloudline",
        s.origin(), s.destination());
  }

  synchronized Map<String, Object> invite(long id, Shipment s, Catalog.Carrier c) {
    var m = target(id);
    checkPacking(id, m, s);
    return invite(id, s, c, details(id, m));
  }

  private Map<String, Object> invite(long id, Shipment s, Catalog.Carrier c,
      CustomerClarifications.Details details) {
    if (carrierRestrictions.excludes(carrierRestrictions.forRequest(id, details.message()), c.name()))
      throw new ExcludedCarrier();
    if (catalog.problem(s) != null || !c.serves(c.truck() ? s.pickupZone() : s.destination()))
      throw new IllegalArgumentException("Check shipment and carrier coverage.");
    var previous = ledger.tenders(id).stream().filter(t -> c.email().equals(t.get("email"))).findFirst();
    boolean citywide = c.truck() && details.area() != null;
    String base = "bid:" + id + ":" + c.email();
    String action = base;
    if (details.latestReply() != null) {
      var tender = previous.orElse(null);
      if (tender != null && matchesTender(tender, s, details, c.truck())) {
        action = Ledger.bidAction(tender);
      } else if (citywide) {
        action = base + ":" + details.pickupReply() + ":area";
      } else if (tender != null || (c.truck() && details.pickupReply() != null)) {
        action = base + ":" + details.latestReply();
      }
    }
    String state = ledger.delivery(action);
    if (state != null) return Map.of("state", state);
    String location = c.truck() ? details.location(s.pickupZone()) : "";
    String leg =
        c.truck()
            ? "pickup from " + location + " to " + s.origin()
            : s.origin() + " to " + s.destination();
    ledger
        .jdbc()
        .update(
            """
            INSERT INTO tender(request_id,email,leg,terms,bid_action) VALUES(?,?,?,?,?)
            ON CONFLICT(request_id,email) DO UPDATE SET terms=excluded.terms, bid_action=excluded.bid_action,
              reply_id=NULL,euros=NULL,days=NULL,issue=NULL
            WHERE COALESCE(tender.bid_action,'bid:'||tender.request_id||':'||tender.email)<>excluded.bid_action
            """,
            id,
            c.email(),
            c.kind(),
            ledger.write(s), action);
    String service =
        c.truck()
            ? "collection from "
                + location
                + " and delivery to "
                + s.origin()
            : "air freight from " + leg;
    return send(
        action,
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
                + (c.truck()
                    ? "\n\nPlease send your all-in rate in EUR and the estimated transit time from"
                        + " actual pickup to airport delivery, excluding the wait before collection."
                        + " Please give the rate validity and these figures directly in the reply text."
                    : "\n\nPlease send your all-in rate in EUR and your estimated transit time.")
                + (citywide ? " The customer confirmed the town and has yet to confirm a street"
                    + " address. Can you make this a firm rate with the same transit time for"
                    + " every pickup address in " + details.area() + "? If so, please write: Valid for all of "
                    + details.area() + "." : "")
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
