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
  private static final java.util.regex.Pattern DEPARTURE_TIMING = java.util.regex.Pattern.compile(
      "(?iu)\\b(?:earliest (?:available )?departure|departure and arrival (?:times?|dates?)|"
          + "departure (?:time|date))\\b");
  private static final java.util.regex.Pattern BOOKING_REFERENCE = java.util.regex.Pattern.compile(
      "(?<![A-Za-z0-9_-])BK-[A-Za-z0-9_-]+(?![A-Za-z0-9_-])");
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
        counteroffers, Runnable::run);
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
      @org.springframework.beans.factory.annotation.Qualifier("voiceExecutor") Executor voiceExecutor) {
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
    this.documents = new ShipmentDocuments(ledger, upstream, catalog, clock, voiceExecutor);
    this.clarifications = new CustomerClarifications(ledger, catalog, gateway);
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
      String rfqModel,
      String llmProvider,
      String quoteLlmProvider) {}

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
        gateway.model,
        upstream.directLlm() ? "openai" : "5u",
        upstream.quoteGateway() || !upstream.directLlm() ? "5u" : "openai");
  }

  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  synchronized void recheckOpenExtractions() {
    if (automatic) {
      var receivedAfter = clock.instant().minusSeconds(355);
      gateway.recheckOpenConflicts(receivedAfter);
      ledger.recheckOpenTruckBids(receivedAfter, catalog);
      for (var request : ledger.jdbc().queryForList("""
          SELECT m.* FROM mail m JOIN wave_case w ON w.id=m.id
          WHERE m.kind='request' AND m.stage='review' AND w.operator=0 AND (w.reason=? OR w.reason LIKE ?)
            AND julianday(m.received)>julianday(?)
            AND NOT EXISTS (SELECT 1 FROM deliveries WHERE action='quote:'||m.id)
          """, QuoteChecks.SCHEDULE_REVIEW, QuoteChecks.REVIEW + " Check %", receivedAfter.toString())) {
        try {
          QuoteChecks.preflight(request);
          ledger.stage(Ledger.id(request.get("id")), "new", null);
        } catch (Conflict unresolved) {
          // The requested service or timing still needs review.
        }
      }
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
                WHERE m.kind='request' AND w.operator=0 AND (
                  m.stage IN ('new','collecting','waiting for pickup','waiting for air price','waiting for customer')
                  OR (m.stage IN ('review','waiting for documents') AND julianday(m.received)>julianday(?) AND (
                    w.reason IN ('Pickup requested. Confirm the pickup postcode or zone.',
                      'Confirm the pickup requirement and postcode.', 'Confirm origin and destination airports.',
                      'Confirm total gross weight in kg.', 'Confirm total shipment volume in cubic metres.',
                      'Confirm cheapest or fastest service.', 'Confirm the pickup zone and matching airport.',
                      'Confirm the destination of each separately priced shipment.',
                      'Confirm every separately priced shipment and its cargo details.',
                      'Review the additional instructions for each shipment.',
                      'Confirm the EUR goods value, insured percentage and cargo insurance premium rate.') OR
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
      } catch (ChangedTariff ex) {
        ledger.stage(id, "collecting", ex.getMessage());
      } catch (Conflict ex) {
        ledger.stage(id, "review", ex.getMessage());
      } catch (RuntimeException ex) {
        // An unexpected error is often transient (an upstream reply, a race): retry on the next
        // ticks before parking the request, and keep the stack trace so the cause is visible.
        int failures = unexpected.merge(id, 1, Integer::sum);
        if (failures < UNEXPECTED_RETRIES) {
          log.warn("RFQ {} failed ({} of {}), retrying on the next tick", id, failures, UNEXPECTED_RETRIES, ex);
          continue;
        }
        unexpected.remove(id);
        log.warn("RFQ {} held after {} failures", id, failures, ex);
        ledger.stage(
            id, "review", "Automatic pricing interrupted. Inspect offers and delivery records.");
      }
    }
  }

  private static final int UNEXPECTED_RETRIES = 3;
  private final java.util.Map<Long, Integer> unexpected = new java.util.concurrent.ConcurrentHashMap<>();

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

  private static class ChangedTariff extends Conflict {
    ChangedTariff() {
      super("The approved tariff changed. Compare rates again before sending.");
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
    return clarifications.details(id, documents.withAttachedRequest(mail), documents::withAttachedRequest);
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
    var language = details.language();
    var decision = documents.inspect(details.message(), replies,
        details.pickupReply() == null ? null : details.zone(), details.deliveryAirport(), language);
    if (!decision.holdsQuote()) return decision.shipment();
    if (decision.action() == null) throw new RetryLater(decision.reason());
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
      String action = decision.action() + ":" + id;
      send(
          action,
          Map.of(
              "to",
              mail.get("sender"),
              "in_reply_to",
              parent,
              "subject",
              "Re: " + mail.get("subject"),
              "body",
              followUpBody(action, mail, language, decision.reply(), parent == id ? null : parent)));
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
      CustomerClarifications.Question question, boolean missingPostcode) {
    String prefix = (missingPostcode ? "pickup-postcode:" : "rfq-info:") + id;
    String action = details.latestReply() == null ? prefix : prefix + ":" + details.latestReply();
    boolean alreadyClaimed = ledger.delivery(action) != null;
    if (alreadyClaimed && CustomerClarifications.WAITING.equals(mail.get("stage"))) return;
    if (details.latestReply() != null && ledger.jdbc().queryForObject("""
        SELECT count(*) FROM deliveries d LEFT JOIN follow_up_copy f ON f.action=d.action
        WHERE (d.action=? OR d.action LIKE ?)
          AND (f.required_question=? OR instr(json_extract(d.payload,'$.body'),?)>0)
        """, Integer.class, prefix, prefix + ":%", question.text(), question.text()) >= 2) {
      if (!CustomerClarifications.WAITING.equals(mail.get("stage")))
        ledger.stage(id, CustomerClarifications.WAITING, question.text());
      return;
    }
    ledger.stage(id, CustomerClarifications.WAITING, question.text());
    if (alreadyClaimed) return;
    try {
      String body = followUpBody(action, mail, details.language(), question, details.latestReply());
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

  /** We quote air freight only: answer a sea, road or rail request once with a polite decline. */
  private void declineNonAir(long id, Map<String, Object> m, String mode) {
    String action = "decline:" + id;
    String reason = "Not an air freight request (" + mode + "). Declined.";
    if (ledger.delivery(action) != null) {
      if (!"declined".equals(m.get("stage"))) ledger.stage(id, "declined", reason);
      return;
    }
    String approved = Greeting.at(m, clock.instant()) + "\n\n"
        + "Thank you for your request. We specialise in air freight only, so we are unable to offer a"
        + " quote for this " + mode + " shipment.\n\n"
        + "If you have an air freight shipment, we would be glad to quote it.\n\n"
        + "Best regards,\nTeam AEIOU";
    String body = inCustomerLanguage(action, m, approved, List.of());
    ledger.stage(id, "declined", reason);
    send(action, Map.of(
        "to", m.get("sender"), "in_reply_to", id, "subject", "Re: " + m.get("subject"), "body", body));
  }

  /** Renders a reply Java wrote in English into the language of the customer's thread. */
  String inCustomerLanguage(String action, Map<String, Object> mail, String approved, List<String> names) {
    return gateway.customerCopy(action, mail, CustomerLanguage.ENGLISH, approved, names).body();
  }

  String followUpBody(String action, Map<String, Object> mail, CustomerLanguage language,
      String question, Long replyId) {
    return followUpBody(action, mail, language, new CustomerClarifications.Question(question), replyId);
  }

  private String followUpBody(String action, Map<String, Object> mail, CustomerLanguage language,
      CustomerClarifications.Question question, Long replyId) {
    String reply = replyId == null ? "" : Ledger.str(ledger.mail(replyId), "body");
    var draft = upstream.directLlm() ? null : gateway.followUpDraft(action, language, mail, reply, question);
    String introduction = draft == null ? null : draft.introduction();
    String wording = draft == null || draft.question() == null ? question.text() : draft.question();
    String approved = Greeting.at(mail, clock.instant(), language) + "\n\n"
        + (introduction == null ? "" : introduction + "\n\n")
        + wording + "\n\n" + language.signoff();
    return gateway.customerCopy(action, mail, language, approved, List.of()).body();
  }

  private void applyCustomerReply(long id, Long replyId, MailParser.Parsed parsed) {
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
      var reason = ledger.jdbc().queryForObject("SELECT reason FROM wave_case WHERE id=?", String.class, id);
      ledger.stage(id, "expired", reason == null
          ? "Quote window closed before a verified quote was sent." : "Quote window closed. " + reason);
      return;
    }
    String delivery = ledger.delivery("quote:" + id);
    if (delivery != null) {
      ledger.stage(
          id, delivery.equals("sent") ? "sent" : "review", "Check the recorded quote delivery.");
      return;
    }
    String mode = MailParser.nonAirMode(Ledger.str(m, "subject"), MailParser.authoredText(Ledger.str(m, "body")));
    if (mode != null) {
      declineNonAir(id, m, mode);
      return;
    }
    var separate = separateShipments(m);
    if (separate != null) {
      if (separate.issue() != null && Set.of("Confirm the destination of each separately priced shipment.",
          "Confirm every separately priced shipment and its cargo details.",
          "Review the additional instructions for each shipment.").contains(separate.issue())
          && !hasSeparateQuoteFollowUp(id, m) && clock.instant().isBefore(deadline.minusSeconds(20))) {
        separate = gateway.recoverSeparateShipments(id, m);
        if (separate == null) throw new Conflict("Confirm the details of every shipment against the original email.");
      }
      advanceSeparateShipments(id, m, received, deadline, separate);
      return;
    }
    if (gateway.quoteCoolingDown(id)) return;
    var details = details(id, m);
    QuoteChecks.preflight(details.message());
    CargoInsurance.from(details.message());
    var local = MailParser.request(Ledger.str(details.message(), "subject"), Ledger.str(details.message(), "body"),
        false, catalog, details.pickupReply() == null ? null : details.zone(), details.deliveryAirport());
    var verified = checkPacking(id, m, details);
    var w = ledger.jdbc().queryForMap("SELECT * FROM wave_case WHERE id=?", id);
    var recorded = ledger.read(m.get("fields"), Shipment.class);
    if (CustomerClarifications.WAITING.equals(m.get("stage")) && local.issue() == null
        && (catalog.problem(recorded) != null
            || (recorded.pickupZone() != null && local.shipment().pickupZone() == null
                && MailParser.selfDelivery(Ledger.str(details.message(), "body"))))) {
      applyCustomerReply(id, details.latestReply(), local);
      m = ledger.mail(id);
      w = ledger.jdbc().queryForMap("SELECT * FROM wave_case WHERE id=?", id);
      recorded = local.shipment();
    }
    boolean missingPostcode = clarifications.needsPostcode(details,
        recorded, local.issue());
    if (missingPostcode) {
      var question = clarifications.question(local.shipment(), local.issue(), true, details.language());
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
      var question = clarifications.question(s, issue, missingPostcode, details.language());
      if (question != null) askCustomer(id, m, details, question, missingPostcode);
      else ledger.stage(id, "review", issue);
      return;
    }
    if (s.pickupZone() == null) sendInvitations(id, s, details, deadline, Set.of());
    long queried = Ledger.id(w.get("queried"));
    var offers = new ArrayList<Offer>(Arrays.asList(cached(w)));
    if (queried == 0
        || (queried > 0
            && clock.instant().getEpochSecond() - queried >= (offers.isEmpty()
                && !clock.instant().isBefore(received.plusSeconds(PORTAL_COMPARISON_SECONDS)) ? 5 : 35)
            && clock.instant().isBefore(deadline.minusSeconds(35)))) {
      var now = clock.instant();
      var result = offers.isEmpty() ? upstream.rates(s, now)
          : upstream.rates(s, now, offers, (String) w.get("portal_version"));
      if (result.unavailable() && offers.isEmpty() && result.offers().isEmpty())
        requestRateDesk(id, s, deadline);
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
    if (s.pickupZone() != null) {
      var validThrough = clock.instant().plusSeconds(waitSeconds + 4);
      if (deadline.isBefore(validThrough)) validThrough = deadline;
      var chargeableWeight = s.chargeableKg();
      var baseRates = offers.stream().filter(offer -> offer.billedKg().compareTo(chargeableWeight) == 0).toList();
      sendInvitations(id, s, details, deadline, bookableAirlines(baseRates, validThrough));
    }
    boolean transcribingBids = recoverAttachedBids(id);
    var tenders = ledger.tenders(id);
    boolean needsPickupTransit = s.pickupZone() != null
        && ("fastest".equals(s.preference()) || arrivalWindow(details) != null);
    if (s.pickupZone() != null) askTruckTransit(id, s, details, tenders, deadline, false);
    var coveredAirlines = queried == -1 && !QuoteChecks.scheduleInformation(details.message())
        ? bookableAirlines(offers, clock.instant().plusSeconds(4)) : Set.<String>of();
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
    var calculation = calculate(id, s, null, true);
    var draft = calculation.draft();
    if (draft.offer() == null) {
      String reason = String.join(" ", draft.notes());
      if (draft.notes().contains(QuoteChecks.PICKUP_REVIEW)) {
        ledger.stage(id, "review", QuoteChecks.PICKUP_REVIEW);
        return;
      }
      if (needsPickupTransit && draft.pickupDays() == null)
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
    boolean deskCompared = rateDeskOffer(id, s)
        .filter(rate -> !carrierRestrictions.excludes(carrierRestrictions.forRequest(id, ledger.mail(id)),
            rate.airline()))
        .isPresent();
    boolean portalMissing = !deskCompared && queried != -1 && offers.isEmpty()
        && clock.instant().isBefore(deadline.minusSeconds(60));
    if (portalMissing
        || (!deskCompared && queried != -1
            && clock.instant().isBefore(received.plusSeconds(PORTAL_COMPARISON_SECONDS)))) {
      ledger.stage(id, "collecting", ledger.delivery("rate-desk:" + id) == null ? null
          : "Portal rates unavailable. Waiting for a comparable Rate Desk quote.");
      return;
    }
    if (counteroffers) {
      if (awaitCounteroffer(id, deadline, waitUntil, draft, tenders)) {
        ledger.stage(id, "collecting", null);
        return;
      }
      if (ledger.counterAnswered(id)) {
        var refreshed = calculate(id, s, null, true);
        if (refreshed.draft().offer() != null) calculation = refreshed;
      }
    }
    save(id, s, calculation.draft(), false);
    sendQuote(id, calculation.draft().revision(), true);
  }

  private void requestRateDesk(long id, Shipment shipment, Instant deadline) {
    String action = "rate-desk:" + id;
    if (ledger.delivery(action) != null || !clock.instant().isBefore(deadline.minusSeconds(30))) return;
    try {
      send(action, RateDesk.request(id, shipment));
    } catch (Conflict ex) {
      log.info("RFQ {} Rate Desk request: {}", id, ex.getMessage());
    }
  }

  private Optional<Offer> rateDeskOffer(long id, Shipment shipment) {
    return ledger.jdbc().queryForList("""
        SELECT r.body FROM deliveries d JOIN mail r
          ON (r.reply_to=json_extract(d.response,'$.id') OR r.root=json_extract(d.response,'$.id'))
        WHERE d.action=? AND d.state='sent' AND r.sender=? AND r.kind='ratedesk'
          AND json_extract(d.payload,'$.body')=? AND julianday(r.received)>=julianday(d.attempted_at)
        ORDER BY r.id DESC LIMIT 1
        """, String.class, "rate-desk:" + id, RateDesk.ADDRESS, RateDesk.request(id, shipment).get("body"))
        .stream().findFirst().flatMap(body -> RateDesk.offer(body, shipment, catalog, clock.instant()));
  }

  private void askAirportDelivery(long id, Map<String, Object> mail,
      CustomerClarifications.Details details, String airport) {
    String action = "airport-delivery:" + id;
    if (ledger.delivery(action) != null) return;
    var language = details.language();
    String question = language.text(
        "Thanks for confirming %s. We’re checking citywide collection rates. If the warehouse postcode"
            + " becomes available, please send all five digits. If you can bring the shipment to %s yourselves instead,"
            + " please reply with: We will deliver to %s ourselves.\n\n"
            + "With your confirmation, we can quote the airport-to-airport service.",
        "Merci d’avoir confirmé %s. Nous vérifions les tarifs d’enlèvement pour toute la ville."
            + " Si vous obtenez le code postal de l’entrepôt, merci de nous transmettre ses cinq chiffres."
            + " Si vous pouvez déposer vous-mêmes la marchandise à %s, merci de répondre"
            + " « Nous livrerons la marchandise nous-mêmes à %s. »\n\n"
            + "Avec votre confirmation, nous pourrons vous proposer un tarif d’aéroport à aéroport.",
        "Vielen Dank für die Bestätigung von %s. Wir prüfen die Abholpreise für das gesamte Stadtgebiet."
            + " Falls die Postleitzahl des Lagers bekannt wird, teilen Sie uns bitte alle fünf Stellen mit."
            + " Wenn Sie die Sendung stattdessen selbst nach %s bringen können, antworten Sie bitte mit"
            + " „Wir bringen die Sendung selbst nach %s.“\n\n"
            + "Sobald Sie das bestätigen, können wir den Transport von Flughafen zu Flughafen anbieten.",
        "感谢您确认提货城市为 %s。我们正在查询当地的提货费。如能确认仓库的五位数邮编，请发给我们。"
            + "如果您可以自行把货物送到 %s 机场，请回复“我们会自行送到 %s 机场”。\n\n"
            + "收到您的确认后，我们可以提供机场到机场的报价。")
        .formatted(details.area(), airport, airport);
    try {
      send(action, Map.of(
          "to", mail.get("sender"), "in_reply_to", details.latestReply(),
          "subject", "Re: " + mail.get("subject"),
          "body", followUpBody(action, mail, language, question, details.latestReply())));
    } catch (Conflict ex) {
      log.info("RFQ {} airport delivery option: {}", id, ex.getMessage());
    }
  }

  private void askTruckTransit(long id, Shipment shipment, CustomerClarifications.Details details,
      List<Map<String, Object>> tenders, Instant deadline, boolean inviteAlternatives) {
    var excluded = carrierRestrictions.forRequest(id, details.message());
    boolean transitRequired = "fastest".equals(shipment.preference()) || arrivalWindow(details) != null;
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
      if ((!transitRequired && !MailParser.quotesAmbiguousPickupTiming(text))
          || (!inviteAlternatives && !MailParser.roadTransitEvidence(text).isEmpty())) continue;
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
      } catch (IllegalArgumentException ex) {
        log.warn("RFQ {} skipped replacement trucker {}", id, carrier.name(), ex);
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

  private MailParser.SeparateShipments separateShipments(Map<String, Object> mail) {
    var files = ledger.read(mail.get("attachments"), List.class);
    return MailParser.separateShipments(Ledger.str(mail, "subject"), Ledger.str(mail, "body"),
        files != null && !files.isEmpty(), catalog);
  }

  private record ShipmentQuote(long tenderId, Shipment shipment, CustomerClarifications.Details details) {}

  private void advanceSeparateShipments(long id, Map<String, Object> mail, Instant received, Instant deadline,
      MailParser.SeparateShipments separate) {
    if (separate.issue() != null) throw new Conflict(separate.issue());
    if (CargoInsurance.mentioned(Ledger.str(mail, "body") + "\n" + Ledger.str(mail, "subject"))
        && CargoInsurance.hasUnallocatedTerms(separate.sharedText()))
      throw new Conflict("Allocate the insured value and any shared insurance fees to each shipment before quoting.");
    var original = details(id, mail);
    if (original.latestReply() != null || hasSeparateQuoteFollowUp(id, mail))
      throw new Conflict("Review the customer’s follow-up for both shipments before quoting.");

    var shipments = new ArrayList<ShipmentQuote>();
    boolean waiting = false;
    int number = 0;
    for (var entry : separate.shipments()) {
      number++;
      var shipment = entry.shipment();
      var message = new HashMap<>(original.message());
      message.put("body", entry.text());
      var postcode = MailParser.pickupPostcode(entry.text(), "").orElse(null);
      if (shipment.pickupZone() != null && postcode == null)
        throw new Conflict("Confirm the full pickup postcode for " + shipment.destination() + ".");
      var details = new CustomerClarifications.Details(message, postcode, null, shipment.pickupZone(),
          null, null, null, original.language());
      QuoteChecks.preflight(message);
      CargoInsurance.from(message);
      long tenderId = Ledger.shipmentQuoteLeg(id, number);
      shipments.add(new ShipmentQuote(tenderId, shipment, details));
      for (var carrier : invitations(id, tenderId, shipment, false)) {
        if (!clock.instant().isBefore(deadline.minusSeconds(8))) break;
        try {
          invite(id, shipment, carrier, details, null, entry.pieces(), tenderId, number);
        } catch (Conflict ex) {
          log.info("RFQ {} shipment {} bid to {}: {}", id, number, carrier.name(), ex.getMessage());
        } catch (IllegalArgumentException ex) {
          log.warn("RFQ {} shipment {} skipped bid to {}", id, number, carrier.name(), ex);
        }
      }
      var tenders = ledger.tenders(tenderId);
      var first = ledger.firstInvitation(tenderId);
      boolean transcribing = recoverAttachedBids(tenderId, id);
      if (shipment.pickupZone() != null)
        askTruckTransit(id, shipment, details, tenders, deadline, false);
      waiting |= (tenders.stream().anyMatch(tender -> tender.get("reply_id") == null)
              && clock.instant().isBefore((first == null ? received : first).plusSeconds(waitSeconds)))
          || (transcribing && clock.instant().isBefore(deadline.minusSeconds(20)));
    }
    ledger.stage(id, "collecting", "Checking a separate all-in rate for each shipment.");
    if (waiting) return;

    String pricing = "shipment-price:" + id;
    checkSendRetry(pricing);
    ledger.jdbc().update("INSERT OR REPLACE INTO retry VALUES(?,?)",
        pricing, clock.instant().plusSeconds(8).getEpochSecond());
    var drafts = new ArrayList<Draft>();
    var terms = new ArrayList<String>();
    for (var leg : shipments) {
      var shipment = leg.shipment();
      var details = leg.details();
      var calculation = calculate(id, shipment, null, false,
          new PricingContext(mail, details, received, deadline, null, CargoInsurance.from(details.message())),
          leg.tenderId());
      var draft = calculation.draft();
      if (draft.offer() == null) {
        if (draft.notes().contains(QuoteChecks.PICKUP_REVIEW))
          throw new Conflict(shipment.destination() + ". " + QuoteChecks.PICKUP_REVIEW);
        ledger.stage(id, "collecting", "Waiting for a verified all-in rate for " + shipment.destination() + ".");
        return;
      }
      try {
        draft = rankCurrentPortalOffers(deadline, shipment, details, draft, arrivalWindow(details));
      } catch (Conflict ex) {
        ledger.stage(id, "collecting", shipment.destination() + ". " + ex.getMessage());
        return;
      }
      String term = separateQuoteTerms(terms.size() + 1, details, shipment, draft);
      QuoteChecks.verify(details, shipment, draft, term);
      drafts.add(draft);
      terms.add(term);
    }
    if (upstream.quoteGateway())
      gateway.checkSeparateQuote(id, original.message(), shipments.stream().map(ShipmentQuote::shipment).toList());
    var now = clock.instant();
    if (!now.isBefore(deadline.minusSeconds(4))) return;
    for (var draft : drafts) {
      checkCarrierRestrictions(id, mail, draft);
      if ((draft.offer().validUntil() != null && !draft.offer().validUntil().isAfter(now.plusSeconds(4)))
          || (draft.pickupValidUntil() != null && !draft.pickupValidUntil().isAfter(now.plusSeconds(4)))) return;
    }
    var language = original.language();
    String body = Greeting.at(original.message(), now, language) + "\n\n"
        + language.text("Here are the separate all-in prices for your shipments.",
            "Voici les tarifs tout compris pour chacun de vos envois.",
            "Hier sind die Gesamtpreise für Ihre einzelnen Sendungen.",
            "以下是每票货物各自的全包报价。")
        + "\n\n" + String.join("\n\n", terms) + "\n\n" + language.signoff();
    String action = "quote:" + id;
    var copy = gateway.customerCopy(action, mail, language, body,
        drafts.stream().map(draft -> draft.offer().airline()).toList());
    if (!clock.instant().isBefore(deadline)) return;
    var reply = Map.<String, Object>of("to", mail.get("sender"), "in_reply_to", id,
        "subject", "Re: " + mail.get("subject"), "body", copy.body());
    send(action, reply, () -> ledger.transactions().execute(tx -> {
      for (int index = 0; index < shipments.size(); index++)
        checkSheetRate(shipments.get(index).shipment(), received, drafts.get(index));
      return ledger.jdbc().update("""
        INSERT OR IGNORE INTO deliveries(action,state,payload,attempted_at)
        SELECT ?,'pending',?,? WHERE NOT EXISTS (
          SELECT 1 FROM mail WHERE kind='follow-up' AND sender=? AND root=? AND id>?)
        """, action, ledger.write(reply), now.toString(), mail.get("sender"), id, id);
    }), () -> {
      ledger.jdbc().update("UPDATE mail SET proposal=NULL WHERE id=?", id);
      ledger.stage(id, "sent", null);
    });
  }

  private boolean hasSeparateQuoteFollowUp(long id, Map<String, Object> mail) {
    return !ledger.jdbc().queryForList("""
        SELECT 1 FROM mail WHERE kind='follow-up' AND sender=? AND root=? AND id>?
        LIMIT 1
        """, Integer.class, mail.get("sender"), id, id).isEmpty();
  }

  private List<Catalog.Carrier> invitations(long id, Shipment shipment, boolean confirmedPickup) {
    return invitations(id, id, shipment, confirmedPickup);
  }

  private List<Catalog.Carrier> invitations(long id, long tenderId, Shipment shipment, boolean confirmedPickup) {
    return invitations(id, tenderId, shipment, confirmedPickup, Set.of());
  }

  private List<Catalog.Carrier> invitations(long id, long tenderId, Shipment shipment, boolean confirmedPickup,
      Set<String> portalAirlines) {
    var excluded = carrierRestrictions.forRequest(id, ledger.mail(id));
    var tenders = ledger.tenders(tenderId);
    var invited = new HashSet<String>();
    for (var tender : tenders) invited.add(Ledger.str(tender, "email"));
    boolean limitToInvited = shipment.pickupZone() != null && "cheapest".equals(shipment.preference())
        && invited.size() >= Catalog.MAX_INVITATIONS;
    var truckTransit = shipment.pickupZone() != null && "fastest".equals(shipment.preference())
        ? ledger.truckTransitBefore(id, shipment) : Map.<String, Double>of();
    var selected = catalog.invitations(shipment, ledger.airlineRoutesBefore(id), truckTransit, portalAirlines,
        carrier -> !carrierRestrictions.excludes(excluded, carrier.name())
            && (!limitToInvited || invited.contains(carrier.email())));
    if (!confirmedPickup || shipment.pickupZone() == null) return selected;
    var airlines = selected.stream().filter(c -> !c.truck()).toList();
    var trucks = new LinkedHashSet<Catalog.Carrier>();
    for (var tender : tenders)
      catalog.contact(Ledger.str(tender, "email"))
          .filter(c -> c.truck() && c.serves(shipment.pickupZone()))
          .filter(c -> !carrierRestrictions.excludes(excluded, c.name())).ifPresent(trucks::add);
    selected.stream().filter(Catalog.Carrier::truck).forEach(trucks::add);
    var result = new ArrayList<>(airlines);
    result.addAll(trucks.stream().limit(Catalog.MAX_INVITATIONS - airlines.size()).toList());
    return result;
  }

  private void sendInvitations(long id, Shipment shipment, CustomerClarifications.Details details,
      Instant deadline, Set<String> portalAirlines) {
    var carriers = invitations(id, id, shipment, details.pickupReply() != null, portalAirlines);
    if (details.pickupReply() != null)
      carriers = carriers.stream().sorted(Comparator.comparing(c -> !c.truck())).toList();
    var invited = new HashSet<String>();
    for (var tender : ledger.tenders(id)) invited.add(Ledger.str(tender, "email"));
    for (var carrier : carriers) {
      if (clock.instant().isAfter(deadline.minusSeconds(8))) return;
      if (shipment.pickupZone() != null && invited.size() >= Catalog.MAX_INVITATIONS
          && !invited.contains(carrier.email())) continue;
      invited.add(carrier.email());
      try {
        invite(id, shipment, carrier, details);
      } catch (Conflict ex) {
        log.info("RFQ {} bid to {}: {}", id, carrier.name(), ex.getMessage());
      } catch (IllegalArgumentException ex) {
        // One carrier that cannot be invited must not stop the bids to the others.
        log.warn("RFQ {} skipped bid to {}", id, carrier.name(), ex);
      }
    }
  }

  private Set<String> bookableAirlines(List<Offer> offers, Instant validThrough) {
    var names = new HashSet<String>();
    for (var offer : offers) {
      if ("portal".equals(offer.source()) && offer.rateId() != null && !offer.rateId().isBlank()
          && offer.validUntil() != null && offer.validUntil().isAfter(validThrough))
        names.add(offer.airline().toLowerCase(Locale.ROOT));
    }
    return names;
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
      String pickupWaitingDisclosure,
      CargoInsurance insurance,
      BigDecimal airEur) {
    Draft(String revision, Offer offer, List<Offer> alternatives, List<String> notes, String body,
        String trucker, BigDecimal pickupEur, Double pickupDays, Instant pickupValidUntil) {
      this(revision, offer, alternatives, notes, body, trucker, pickupEur, pickupDays,
          pickupValidUntil, null, null, null);
    }

    Offer allIn(Shipment shipment, Offer air) {
      var transport = shipment.pickupZone() == null ? air
          : air.addPickup(shipment.withPickup(pickupEur, pickupDays == null ? 0 : pickupDays), pickupValidUntil);
      return insurance == null ? transport : insurance.addTo(transport);
    }

    Draft withOffer(Offer current, List<Offer> options, String quote) {
      return withOffer(current, options, quote, airEur);
    }

    Draft withOffer(Offer current, List<Offer> options, String quote, BigDecimal currentAirEur) {
      return new Draft(revision, current, List.copyOf(options), notes, quote, trucker,
          pickupEur, pickupDays, pickupValidUntil, pickupWaitingDisclosure, insurance, currentAirEur);
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
    return recoverAttachedBids(id, id);
  }

  private boolean recoverAttachedBids(long tenderId, long id) {
    boolean awaitingVoice = false;
    for (var reply : ledger.jdbc().queryForList("""
        SELECT r.*, t.leg FROM tender t JOIN mail r
          ON r.id=t.reply_id AND r.sender=t.email AND r.root=?
        WHERE t.request_id=? AND t.euros IS NULL AND r.kind='carrier'
          AND json_array_length(r.attachments)>0
        """, id, tenderId)) {
      boolean truck = "trucker".equals(reply.get("leg"));
      var text = documents.attachedOffer(reply, truck);
      if (text != null)
        ledger.recoverAttachedBid(tenderId, Ledger.id(reply.get("id")), Ledger.str(reply, "sender"),
            text, truck, catalog, clock.instant());
      else awaitingVoice |= documents.awaitingVoice(reply) || documents.awaitingScan(reply);
    }
    return awaitingVoice;
  }

  private record Calculation(Draft draft) {}
  private record PricingContext(Map<String, Object> mail, CustomerClarifications.Details details,
      Instant received, Instant deadline, Long revisionReply, CargoInsurance insurance) {}

  private Calculation calculate(long id, Shipment s, ManualBid manual, boolean cachedPortal) {
    var mail = target(id);
    var details = details(id, mail);
    return calculate(id, s, manual, cachedPortal,
        new PricingContext(mail, details, Ledger.instant(mail.get("received")), ageLimit(mail), null,
            CargoInsurance.from(details.message())));
  }

  private Calculation calculate(long id, Shipment s, ManualBid manual, boolean cachedPortal, PricingContext context) {
    return calculate(id, s, manual, cachedPortal, context, id);
  }

  private Calculation calculate(long id, Shipment s, ManualBid manual, boolean cachedPortal, PricingContext context,
      long tenderId) {
    String problem = catalog.problem(s);
    if (problem != null) throw new IllegalArgumentException(problem);
    var m = context.mail();
    var details = context.details();
    QuoteChecks.preflight(details.message());
    var insurance = context.insurance();
    var arrival = arrivalWindow(details);
    if (arrival != null && !arrival.complete()) throw new Conflict(MailParser.ARRIVAL_DATES_ISSUE);
    var excluded = carrierRestrictions.forRequest(id, m);
    var offers = new ArrayList<>(tariffOffers(s, context.received()));
    var portal = cachedPortal
        ? List.of(cached(ledger.jdbc().queryForMap("SELECT * FROM wave_case WHERE id=?", id)))
        : upstream.rates(s, clock.instant()).offers();
    portal.stream()
        .filter(o -> cachedPortal
            || (o.validUntil() != null && o.validUntil().isAfter(clock.instant().plusSeconds(4))))
        .forEach(offers::add);
    if (context.revisionReply() == null && tenderId == id) rateDeskOffer(id, s).ifPresent(offers::add);
    offers.removeIf(offer -> carrierRestrictions.excludes(excluded, offer.airline()));
    var airBids = new ArrayList<Offer>();
    var trucks = new ArrayList<Offer>();
    var truckReviews = new ArrayList<TruckBidReview>();
    var trucksWithAmbiguousTiming = new ArrayList<Offer>();
    boolean conditionalPickup = false;
    boolean fastest = "fastest".equals(s.preference());
    var missingTruckTransit = new HashSet<String>();
    for (var t : ledger.jdbc().queryForList("""
        SELECT t.*,COALESCE(r.carrier_offer_text,r.body) AS reply_body,p.state AS parse_state FROM tender t
        JOIN mail r ON r.id=t.reply_id LEFT JOIN truck_bid_parse p ON p.reply_id=t.reply_id
        WHERE t.request_id=? ORDER BY t.email
        """, tenderId)) {
      var c = catalog.contact(Ledger.str(t, "email")).orElseThrow();
      if (carrierRestrictions.excludes(excluded, c.name())) continue;
      if (!matchesTender(t, s, details, c.truck())) continue;
      if (context.revisionReply() != null && !isRevisionTender(t, context.revisionReply())) continue;
      String body = Ledger.str(t, "reply_body");
      boolean conditional = QuoteChecks.conditionalCarrier(body) || (c.truck() && QuoteChecks.pickupExtras(body));
      if (conditional) {
        conditionalPickup |= c.truck();
        continue;
      }
      if (t.get("euros") == null) continue;
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
      if (t.get("days") == null) {
        if (!c.truck()) continue;
        missingTruckTransit.add(c.name());
        boolean ambiguous = MailParser.quotesAmbiguousPickupTiming(body);
        if (fastest || ambiguous) {
          if (!fastest) trucksWithAmbiguousTiming.add(offer);
          if ((cachedPortal || tenderId != id || context.revisionReply() != null)
              && s.pickupEur() == null && t.get("parse_state") == null) {
            var evidence = MailParser.roadTransitEvidence(body);
            if (!evidence.isEmpty())
              truckReviews.add(new TruckBidReview(Ledger.id(t.get("reply_id")), c.email(), body,
                  new BigDecimal(Ledger.str(t, "euros")), offer, evidence));
          }
          continue;
        }
      }
      // Some airlines bill the chargeable weight at a break it does not reach; the sheet price stands.
      if (!c.truck() && misappliedBreak(s, context.received(), c.name(), offer.totalEur())) continue;
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
      resolveCompetitiveTruckBids(id, context.deadline(), trucks, truckReviews, missingTruckTransit);
    if (trucks.stream().noneMatch(truck -> !missingTruckTransit.contains(truck.airline())))
      trucks.addAll(trucksWithAmbiguousTiming);
    var plan = QuotePlanner.plan(s, offers, airBids, trucks, missingTruckTransit);
    if (arrival != null) plan = planWithinArrival(s, offers, airBids, trucks, missingTruckTransit, plan, arrival);
    var best = plan.best();
    var airEur = best == null ? null : best.totalEur().subtract(
        plan.pickupEur() == null ? BigDecimal.ZERO : plan.pickupEur());
    var options = plan.options();
    if (insurance != null && best != null) {
      best = insurance.addTo(best);
      options = options.stream().map(insurance::addTo).toList();
    }
    var pickupDays = plan.pickupDays();
    String body = best == null ? "" : quoteBody(details, s, best, pickupDays, null, insurance);
    var notes = new ArrayList<>(plan.notes());
    if (conditionalPickup && s.pickupZone() != null && plan.pickupEur() == null)
      notes.add(QuoteChecks.PICKUP_REVIEW);
    var draft = new Draft(
        UUID.randomUUID().toString(),
        best,
        options,
        List.copyOf(notes),
        body,
        plan.trucker(),
        plan.pickupEur(),
        pickupDays,
        plan.pickupValidUntil(),
        null,
        insurance,
        airEur);
    return new Calculation(draft);
  }

  private MailParser.ArrivalWindow arrivalWindow(CustomerClarifications.Details details) {
    return MailParser.arrivalWindow(Ledger.str(details.message(), "body"));
  }

  private LocalDate quoteDay() {
    return clock.instant().atZone(CarrierTariffs.BERLIN).toLocalDate();
  }

  private QuotePlanner.Plan planWithinArrival(Shipment shipment, List<Offer> offers, List<Offer> airBids,
      List<Offer> trucks, Set<String> unknownTransit, QuotePlanner.Plan original,
      MailParser.ArrivalWindow arrival) {
    // A faster truck can make a cheaper air option feasible. Compare complete combinations: the air
    // leg the customer asked for first, then the most balanced truck that still arrives in time.
    var timed = trucks.stream().filter(truck -> !unknownTransit.contains(truck.airline())).toList();
    var combinations = shipment.pickupZone() == null || shipment.pickupEur() != null
        ? java.util.stream.Stream.of(original)
        : timed.stream()
            .map(truck -> QuotePlanner.plan(shipment, offers, airBids, List.of(truck), Set.of()));
    LocalDate today = quoteDay();
    var ranking = Offer.order(shipment.preference());
    var balance = QuotePlanner.balance(timed);
    Comparator<QuotePlanner.Plan> byAirLeg = Comparator.comparing(plan -> airLeg(plan), ranking);
    Comparator<QuotePlanner.Plan> byTruck = Comparator.comparing(plan -> timed.stream()
        .filter(truck -> truck.airline().equals(plan.trucker())).findFirst().orElse(null),
        Comparator.nullsLast(balance));
    return combinations.map(plan -> {
      var eligible = plan.options().stream().filter(offer -> arrival.allows(today, offer.days())).toList();
      return new QuotePlanner.Plan(eligible.isEmpty() ? null : eligible.getFirst(), eligible, plan.notes(),
          plan.trucker(), plan.pickupEur(), plan.pickupDays(), plan.pickupValidUntil());
    }).filter(plan -> plan.best() != null).min(byAirLeg.thenComparing(byTruck))
        .orElseGet(() -> new QuotePlanner.Plan(null, List.of(),
            original.best() == null ? original.notes()
                : List.of("No confirmed offer meets the requested arrival date of " + arrival.arriveBy() + "."),
            original.trucker(), original.pickupEur(), original.pickupDays(), original.pickupValidUntil()));
  }

  /** The air part of a planned offer, so combinations compare by the customer's air preference. */
  private static Offer airLeg(QuotePlanner.Plan plan) {
    var best = plan.best();
    if (plan.trucker() == null || plan.pickupEur() == null) return best;
    double pickupDays = plan.pickupDays() == null ? 0 : plan.pickupDays();
    return new Offer(best.airline(), Math.max(0, best.days() - pickupDays),
        best.totalEur().subtract(plan.pickupEur()).max(BigDecimal.ZERO), best.billedKg(), best.source(),
        best.rateId(), best.validUntil());
  }

  private List<Offer> tariffOffers(Shipment shipment, Instant received) {
    var date = received.atZone(CarrierTariffs.BERLIN).toLocalDate();
    var today = clock.instant().atZone(CarrierTariffs.BERLIN).toLocalDate();
    return catalog.price(shipment, date, ledger.approvedTariffs(shipment, date, today));
  }

  private boolean misappliedBreak(Shipment shipment, Instant received, String airline, BigDecimal total) {
    var date = received.atZone(CarrierTariffs.BERLIN).toLocalDate();
    var today = clock.instant().atZone(CarrierTariffs.BERLIN).toLocalDate();
    return catalog.misappliedBreak(shipment, date, ledger.approvedTariffs(shipment, date, today), airline, total);
  }

  private void resolveCompetitiveTruckBids(
      long id, Instant deadline, List<Offer> trucks, List<TruckBidReview> reviews, Set<String> missingTransit) {
    var ranking = Offer.order("fastest");
    var best = trucks.stream().filter(truck -> !missingTransit.contains(truck.airline())).min(ranking).orElse(null);
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
      missingTransit.remove(recovered.airline());
      if (best == null || ranking.compare(recovered, best) < 0) best = recovered;
    }
  }

  private String quoteBody(
      CustomerClarifications.Details details, Shipment s, Offer offer, Double pickupDays,
      String pickupWaitingDisclosure, CargoInsurance insurance) {
    var language = details.language();
    return Greeting.at(details.message(), clock.instant(), language)
        + "\n\n" + quoteTerms(details, s, offer, pickupDays, pickupWaitingDisclosure)
        + (insurance == null ? "" : "\n\n" + insurance.disclosure(language))
        + "\n\n" + language.text("Would you like us to book it for you?",
            "Souhaitez-vous que nous réservions ce transport ?",
            "Sollen wir den Transport für Sie buchen?", "请问您需要我们安排订舱吗？",
            "Rezervasyonu sizin için yapmamızı ister misiniz?")
        + "\n\n" + language.signoff();
  }

  private String quoteTerms(
      CustomerClarifications.Details details, Shipment s, Offer offer, Double pickupDays,
      String pickupWaitingDisclosure) {
    var language = details.language();
    var arrival = arrivalWindow(details);
    String weight = Shipment.displayKg(s.weightKg());
    String priority = "fastest".equals(s.preference())
        ? language.text("quickest option", "solution la plus rapide", "schnellste Option", "最快方案", "en hızlı seçenek")
        : arrival == null
            ? language.text("lowest rate", "offre la moins chère", "günstigste Option", "最低价方案", "en ekonomik seçenek")
            : language.text("lowest qualifying rate", "offre la moins chère qui respecte votre délai",
                "günstigste Option, die Ihre Ankunftsfrist einhält", "满足到达期限的最低价方案",
                "teslim sürenize uyan en ekonomik seçenek");
    String price = "EUR " + offer.totalEur().toPlainString();
    String offerLine = language.text(
        "The %s we found for your %s kg shipment from %s to %s is with %s at %s all in.",
        "Pour votre envoi de %2$s kg de %3$s à %4$s, la %1$s est proposée par %5$s à %6$s tout compris.",
        "Die %s für Ihre Sendung mit %s kg von %s nach %s bietet %s für insgesamt %s an.",
        "您这票 %2$s 公斤、从 %3$s 运往 %4$s 的货物，%1$s由 %5$s 承运，全包价为 %6$s。",
        "%3$s–%4$s arasında %2$s kg yükünüz için %1$s %5$s ile tüm masraflar dahil %6$s tutarındadır.")
        .formatted(priority, weight, s.origin(), s.destination(), offer.airline(), price);
    return offerLine + "\n\n" + quoteService(details, s, offer, pickupDays, pickupWaitingDisclosure);
  }

  private String separateQuoteTerms(int number, CustomerClarifications.Details details, Shipment s, Draft draft) {
    var offer = draft.offer();
    var language = details.language();
    String heading = language.text(
        "%d. %s to %s — EUR %s all-in",
        "%d. %s à %s — EUR %s tout compris",
        "%d. %s nach %s — EUR %s Gesamtpreis",
        "%d. %s 至 %s — EUR %s 全包价")
        .formatted(number, s.origin(), s.destination(), offer.totalEur().toPlainString());
    return heading + "\n"
        + "%s kg · %s m³ · %s".formatted(Shipment.displayKg(s.weightKg()),
            Shipment.displayCbm(s.volumeCbm()), offer.airline())
        + "\n" + quoteService(details, s, offer, draft.pickupDays(), draft.pickupWaitingDisclosure())
        + (draft.insurance() == null ? "" : "\n" + draft.insurance().disclosure(language));
  }

  private String quoteService(CustomerClarifications.Details details, Shipment s, Offer offer, Double pickupDays,
      String pickupWaitingDisclosure) {
    var language = details.language();
    var arrival = arrivalWindow(details);
    boolean scheduleRequested = QuoteChecks.scheduleInformation(details.message());
    var schedule = scheduleRequested ? carrierSchedule(details.message(), s, offer.airline()) : null;
    String transit = language.transit(offer.days());
    String service = language.text("Allow about %s from airport to airport.",
        "Comptez environ %s d’aéroport à aéroport.",
        "Die Laufzeit von Flughafen zu Flughafen beträgt etwa %s.",
        "机场到机场的运输时间约为 %s。", "Havalimanından havalimanına tahmini taşıma süresi %s.").formatted(transit);
    if (s.pickupZone() != null) {
      String location = details.location(s.pickupZone(), language);
      String pickup = language.text("That includes collection from %s. ",
          "Le prix comprend l’enlèvement à %s. ", "Der Preis beinhaltet die Abholung in %s. ",
          "报价包含上门提货（%s）。");
      String timing = pickupDays == null
          ? language.text("The flight takes about %s. We still need to confirm pickup timing.",
              "Le vol dure environ %s. L’horaire d’enlèvement reste à confirmer.",
              "Der Flug dauert etwa %s. Der Abholtermin steht noch aus.",
              "空运时间约为 %s，具体提货时间待确认。")
          : language.text("Allow about %s from collection to arrival.",
              "Comptez environ %s entre l’enlèvement et l’arrivée.",
              "Die Laufzeit von der Abholung bis zur Ankunft beträgt etwa %s.",
              "从提货到货物抵达约需 %s。");
      service = pickup.formatted(location) + timing.formatted(transit);
    }
    if (arrival != null && arrival.complete()) {
      var estimated = arrival.estimatedArrival(quoteDay(), offer.days());
      service += " " + language.text(
          "Planned arrival is %s based on the estimated transit, within your %s deadline.",
          "L’arrivée est estimée au %s, dans le délai du %s.",
          "Die voraussichtliche Ankunft ist am %s und liegt damit innerhalb Ihrer Frist bis %s.",
          "预计于 %s 到达，符合您要求的 %s 截止日期。").formatted(estimated, arrival.arriveBy());
    } else if (arrival == null && schedule == null) {
      var ready = MailParser.cargoReadyDate(Ledger.str(details.message(), "body"));
      if (ready != null && (s.pickupZone() == null || pickupDays != null)) {
        var estimate = new MailParser.ArrivalWindow(ready, null).estimatedArrival(quoteDay(), offer.days());
        service += " " + language.text(
            "Based on your cargo ready date, estimated arrival is %s.",
            "Compte tenu de la date de disponibilité du fret, l’arrivée est estimée au %s.",
            "Auf Grundlage Ihres Bereitstellungsdatums ist die voraussichtliche Ankunft am %s.",
            "根据您的货物备妥日期，预计于 %s 到达。", "Tahmini varış tarihi %s.").formatted(estimate);
      }
    }
    String request = Ledger.str(details.message(), "body");
    if (DEPARTURE_TIMING.matcher(MailParser.authoredText(request)).find()) {
      var ready = MailParser.cargoReadyDate(request);
      if (ready != null && s.pickupZone() == null) {
        var firstDay = ready.isBefore(quoteDay()) ? quoteDay() : ready;
        service += " " + language.text(
            "Departure can be no earlier than %s. The exact flight departure and arrival times are pending carrier confirmation.",
            "Le départ ne peut intervenir avant le %s. Les horaires de départ et d’arrivée restent à confirmer par le transporteur.",
            "Der Abflug ist frühestens am %s möglich. Die genauen Abflug- und Ankunftszeiten müssen vom Frachtführer bestätigt werden.",
            "最早可于 %s 出发，具体起飞和到达时间有待承运人确认。").formatted(firstDay);
      } else {
        service += " " + language.text(
            "The exact flight departure and arrival times are pending carrier confirmation.",
            "Les horaires de départ et d’arrivée restent à confirmer par le transporteur.",
            "Die genauen Abflug- und Ankunftszeiten müssen vom Frachtführer bestätigt werden.",
            "具体起飞和到达时间有待承运人确认。");
      }
    }
    if (scheduleRequested) {
      service += schedule == null
          ? " We have asked the carrier to confirm the cargo acceptance cutoff at " + s.origin()
              + " and the scheduled arrival at " + s.destination() + ". Both times are pending."
          : " The carrier confirmed a cargo acceptance cutoff at " + s.origin() + " of " + schedule.cutoff()
              + " and a scheduled arrival at " + s.destination() + " of " + schedule.arrival() + ".";
    }
    return service + language.waitingDisclosure(pickupWaitingDisclosure);
  }

  private QuoteChecks.FlightSchedule carrierSchedule(Map<String, Object> request, Shipment shipment,
      String airline) {
    long id = Ledger.id(request.get("id"));
    return ledger.tenders(id).stream()
        .filter(tender -> tender.get("reply_id") != null
            && sameAirShipment(ledger.read(tender.get("terms"), Shipment.class), shipment))
        .filter(tender -> catalog.contact(Ledger.str(tender, "email"))
            .filter(carrier -> !carrier.truck() && carrier.name().equalsIgnoreCase(airline)).isPresent())
        .map(tender -> QuoteChecks.flightSchedule(Ledger.str(ledger.mail(Ledger.id(tender.get("reply_id"))), "body")))
        .filter(schedule -> usableSchedule(schedule, request))
        .findFirst().orElse(null);
  }

  private boolean usableSchedule(QuoteChecks.FlightSchedule schedule, Map<String, Object> request) {
    var ready = MailParser.cargoReadyDate(Ledger.str(request, "body"));
    return schedule != null && schedule.cutoff().toInstant().isAfter(clock.instant())
        && (ready == null || !schedule.cutoff().atZoneSameInstant(CarrierTariffs.BERLIN).toLocalDate().isBefore(ready));
  }

  private record ScheduleUpdate(long quoteId, String carrier, String booking, String origin,
      String destination, QuoteChecks.FlightSchedule times) {}

  @Scheduled(fixedDelay = 5000)
  void sendConfirmedSchedules() {
    if (!automatic || clock.instant().isBefore(start)) return;
    var cases = ledger.jdbc().queryForList("""
        SELECT m.* FROM mail m JOIN deliveries q ON q.action='quote:'||m.id AND q.state='sent'
        WHERE m.kind='request' AND m.proposal IS NOT NULL AND m.sender<>''
          AND julianday(m.received)>=julianday(?) AND instr(lower(m.body),'cargo acceptance cut')>0
          AND NOT EXISTS (SELECT 1 FROM deliveries f WHERE f.action='schedule-update:'||m.id)
        """, start.toString());
    for (var request : cases) {
      long id = Ledger.id(request.get("id"));
      String action = "schedule-update:" + id;
      try {
        var update = confirmedScheduleUpdate(id, request);
        if (update == null) continue;
        checkSendRetry(action);
        var language = CustomerLanguage.of(request);
        String reference = update.booking().isBlank() ? "" : " for booking " + update.booking();
        String approved = Greeting.at(request, clock.instant(), language) + "\n\n"
            + update.carrier() + " has confirmed the schedule" + reference + ".\n\n"
            + "Cargo acceptance cutoff at " + update.origin() + ": " + update.times().cutoff() + "\n"
            + "Scheduled arrival at " + update.destination() + ": " + update.times().arrival() + "\n\n"
            + language.signoff();
        var copy = gateway.customerCopy(action, request, language, approved,
            List.of(update.carrier(), update.booking()));
        var mail = Map.<String, Object>of("to", request.get("sender"), "in_reply_to", update.quoteId(),
            "subject", "Re: " + request.get("subject"), "body", copy.body());
        send(action, mail, () -> ledger.transactions().execute(tx ->
            update.equals(confirmedScheduleUpdate(id, ledger.mail(id))) ? claimDelivery(action, mail) : 0));
      } catch (RetryLater ex) {
        // The next scheduled pass checks the confirmation again before sending.
      } catch (RuntimeException ex) {
        log.warn("Could not send the confirmed schedule for RFQ {} ({})", id, ex.getClass().getSimpleName());
      }
    }
  }

  private ScheduleUpdate confirmedScheduleUpdate(long id, Map<String, Object> request) {
    if (!"request".equals(request.get("kind")) || !QuoteChecks.scheduleInformation(request)) return null;
    var revised = ledger.latestRequote(id);
    if (revised != null && (!"sent".equals(revised.get("state")) || revised.get("message_id") == null)) return null;
    Long quoteId = revised != null ? Ledger.id(revised.get("message_id")) : ledger.jdbc().queryForObject("""
        SELECT json_extract(response,'$.id') FROM deliveries WHERE action=? AND state='sent'
        """, Long.class, "quote:" + id);
    if (quoteId == null) return null;
    var draft = ledger.read(revised == null ? request.get("proposal") : revised.get("proposal"), Draft.class);
    var shipment = ledger.read(revised != null && revised.get("shipment") != null
        ? revised.get("shipment") : request.get("fields"), Shipment.class);
    if (draft == null || draft.offer() == null || shipment == null || shipment.pickupZone() != null) return null;
    String carrier = draft.offer().airline();
    var booking = ledger.read(request.get("booking"), Map.class);
    String bookingReference = booking == null ? "" : Ledger.str(booking, "booking_reference");
    if (booking == null && draft.offer().validUntil() != null
        && !draft.offer().validUntil().isAfter(clock.instant())) return null;
    if (booking != null && (bookingReference.isBlank() || !"booked".equals(Ledger.str(booking, "status"))
        || !carrier.equalsIgnoreCase(Ledger.str(booking, "airline")))) return null;

    String bookingSchedule = bookingReference.isBlank() ? null : bookingSchedule(quoteId, carrier, bookingReference);
    var schedule = bookingSchedule == null ? carrierSchedule(request, shipment, carrier)
        : QuoteChecks.flightSchedule(bookingSchedule);
    if (!usableSchedule(schedule, request) || (draft.body().contains(schedule.cutoff().toString())
        && draft.body().contains(schedule.arrival().toString()))) return null;
    return new ScheduleUpdate(quoteId, carrier, bookingReference, shipment.origin(), shipment.destination(), schedule);
  }

  private String bookingSchedule(long quoteId, String carrier, String reference) {
    var contact = catalog.carriers.stream().filter(c -> !c.truck() && c.name().equalsIgnoreCase(carrier)).findFirst();
    if (contact.isEmpty()) return null;
    for (var reply : ledger.jdbc().queryForList("""
        SELECT subject,body FROM mail WHERE kind='carrier' AND sender=? AND id>?
          AND (instr(subject,?)>0 OR instr(body,?)>0) ORDER BY id DESC
        """, contact.get().email(), quoteId, reference, reference)) {
      String raw = Ledger.str(reply, "body");
      String authored = MailParser.authoredText(raw);
      if (!QuoteChecks.mentionsFlightSchedule(authored)) continue;
      String subject = MailParser.currentSubject(Ledger.str(reply, "subject"), raw);
      var references = BOOKING_REFERENCE.matcher(subject + "\n" + authored).results()
          .map(match -> match.group()).distinct().toList();
      if (references.equals(List.of(reference))) return authored;
    }
    return null;
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
    if (separateShipments(message) != null)
      throw new Conflict("This request requires one reply with separate prices for all shipments.");
    checkPacking(id, message, shipment);
    gateway.ensure(id, message, false);
    recoverAttachedBids(id);
    var draft = calculate(id, shipment, bid, false).draft();
    save(id, shipment, draft, true);
    return draft;
  }

  record PortalRateEntry(String airline, String rateId, BigDecimal airTotalEur, Double transitDays,
      Instant validUntil) {}

  /** Adds a rate the operator read from the hosted portal page while the rate API is down. */
  synchronized Offer enterPortalRate(long id, PortalRateEntry entry) {
    var message = target(id);
    if ("sent".equals(message.get("stage"))) throw new Conflict("The quote was already sent.");
    var s = ledger.read(message.get("fields"), Shipment.class);
    String problem = s == null ? "Confirm the shipment details first." : catalog.problem(s);
    if (problem != null) throw new IllegalArgumentException(problem);
    if (entry.airline() == null || entry.airline().isBlank()) throw new IllegalArgumentException("Enter the airline.");
    String rateId = entry.rateId() == null ? "" : entry.rateId().strip();
    if (!rateId.matches("R-[A-Za-z0-9_-]{16}"))
      throw new IllegalArgumentException("The rate ID must be R- plus 16 characters; \"" + rateId + "\" has "
          + Math.max(0, rateId.length() - 2) + ". Copying from the 5U page picks up hidden characters,"
          + " so type it as shown.");
    if (entry.airTotalEur() == null || entry.airTotalEur().signum() <= 0)
      throw new IllegalArgumentException("Enter the EUR air total.");
    if (entry.transitDays() == null || entry.transitDays() <= 0)
      throw new IllegalArgumentException("Enter the transit in days.");
    var now = clock.instant();
    var validUntil = entry.validUntil() == null ? now.plus(Duration.ofMinutes(10)) : entry.validUntil();
    if (!validUntil.isAfter(now.plusSeconds(30))) throw new Conflict("This rate has expired. Look it up again.");
    String airline;
    try {
      airline = upstream.laneAirline(s, entry.airline().strip(), now);
    } catch (RestClientException | IllegalArgumentException ex) {
      throw new Conflict("The portal lane list is unavailable, so the airline cannot be checked.");
    }
    if (airline == null)
      throw new IllegalArgumentException(entry.airline().strip() + " does not fly " + s.origin() + "–"
          + s.destination() + " on the portal.");
    if (carrierRestrictions.excludes(carrierRestrictions.forRequest(id, message), airline))
      throw new ExcludedCarrier();
    var offer = new Offer(airline, entry.transitDays(), entry.airTotalEur(), s.chargeableKg(), "portal",
        rateId, validUntil);
    ledger.transactions().executeWithoutResult(tx -> {
      var w = ledger.jdbc().queryForList("SELECT offers FROM wave_case WHERE id=?", id);
      if (w.isEmpty()) throw new Conflict("The worker has not opened this request yet.");
      var offers = new ArrayList<Offer>(Arrays.asList(cached(w.getFirst())));
      offers.removeIf(old -> old.airline().equals(airline) && old.billedKg().compareTo(offer.billedKg()) == 0);
      offers.add(offer);
      ledger.jdbc().update("UPDATE wave_case SET offers=? WHERE id=?", ledger.write(offers), id);
      ledger.jdbc().update("INSERT OR REPLACE INTO operator_portal_rate VALUES(?,?,?,?)",
          id, offer.rateId(), ledger.write(offer), now.toString());
    });
    log.info("RFQ {}: operator entered portal rate {} for {}", id, offer.rateId(), airline);
    return offer;
  }

  /** While the rate API is down, a rate the operator entered stands in for the live recheck. */
  private Optional<Offer> operatorPortalRate(Offer candidate) {
    if (candidate.rateId() == null) return Optional.empty();
    return ledger.jdbc()
        .queryForList("SELECT offer FROM operator_portal_rate WHERE rate_id=?", String.class, candidate.rateId())
        .stream().map(json -> ledger.read(json, Offer.class))
        .filter(o -> o.airline().equals(candidate.airline()) && o.billedKg().compareTo(candidate.billedKg()) == 0)
        .findFirst();
  }

  private void checkSendRetry(String key) {
    var retry =
        ledger.jdbc().queryForList("SELECT after_epoch FROM retry WHERE action=?", Long.class, key);
    if (!retry.isEmpty() && retry.getFirst() > clock.instant().getEpochSecond())
      throw new RetryLater();
  }

  private Map<String, Object> send(String key, Map<String, Object> mail) {
    return send(key, mail, () -> claimDelivery(key, mail));
  }

  private int claimDelivery(String key, Map<String, Object> mail) {
    return ledger.jdbc().update("INSERT OR IGNORE INTO deliveries(action,state,payload,attempted_at)"
        + " VALUES(?,'pending',?,?)", key, ledger.write(mail), clock.instant().toString());
  }

  Map<String, Object> send(String key, Map<String, Object> mail, IntSupplier claim) {
    return send(key, mail, claim, () -> {});
  }

  private Map<String, Object> send(String key, Map<String, Object> mail, IntSupplier claim, Runnable onSent) {
    checkSendRetry(key);
    if (claim.getAsInt() == 0) throw new Conflict("This send was already claimed. Check the delivery record.");
    try {
      var receipt = upstream.post("/v1/mailbox/messages", mail);
      ledger.transactions().executeWithoutResult(tx -> {
        ledger.jdbc().update("UPDATE deliveries SET state='sent',response=? WHERE action=?",
            ledger.write(receipt), key);
        onSent.run();
      });
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
    if (separateShipments(m) != null)
      throw new Conflict("This request requires one reply with separate prices for all shipments.");
    String key = "quote:" + id;
    if (ledger.delivery(key) != null)
      throw new Conflict("This send was already claimed. Check the delivery record.");
    checkSendRetry(key);
    var shipment = ledger.read(m.get("fields"), Shipment.class);
    checkPacking(id, m, shipment);
    var d = ledger.read(m.get("proposal"), Draft.class);
    if (d == null || d.offer() == null || !d.revision().equals(revision))
      throw new Conflict("Calculate a current quote first.");
    var details = details(id, m);
    var insurance = CargoInsurance.from(details.message());
    if (!Objects.equals(insurance, d.insurance()))
      throw new Conflict("Cargo insurance terms changed. Calculate the all-in quote again.");
    Instant received = Ledger.instant(m.get("received"));
    checkSheetRate(shipment, received, d);
    checkCarrierRestrictions(id, m, d);
    var arrival = arrivalWindow(details);
    if (arrival != null && !arrival.complete()) throw new Conflict(MailParser.ARRIVAL_DATES_ISSUE);
    gateway.checkQuote(id, details.message(), shipment);
    boolean portal = "portal".equals(d.offer().source());
    if (portal) {
      d = rerankPortal
          ? rankCurrentPortalOffers(ageLimit(m), shipment, details, d, arrival)
          : verifyOperatorDraft(ageLimit(m), shipment, d);
    }
    if (arrival != null && !arrival.allows(quoteDay(), d.offer().days()))
      throw new Conflict("The current rate no longer meets the requested arrival date. Compare rates again.");
    String body = details.language() == CustomerLanguage.ENGLISH && arrival == null
        && MailParser.cargoReadyDate(Ledger.str(details.message(), "body")) == null
        ? Greeting.refresh(d.body(), details.message(), clock.instant())
        : quoteBody(details, shipment, d.offer(), d.pickupDays(), d.pickupWaitingDisclosure(), d.insurance());
    boolean greetingChanged = !body.equals(d.body());
    if (greetingChanged) d = d.withOffer(d.offer(), d.alternatives(), body);
    try {
      QuoteChecks.verify(details, shipment, d, body);
    } catch (Conflict ex) {
      ledger.stage(id, "review", ex.getMessage());
      throw ex;
    }
    var copy = gateway.customerCopy(key, m, details.language(), body, List.of(d.offer().airline()));
    if (!copy.body().equals(body)) {
      // The customer reads the rendered text, so the price checks run on it as well.
      try {
        QuoteChecks.verify(details, shipment, d, copy.body());
      } catch (Conflict ex) {
        ledger.stage(id, "review", ex.getMessage());
        throw ex;
      }
    }
    if (!copy.body().equals(d.body())) {
      d = d.withOffer(d.offer(), d.alternatives(), copy.body());
      greetingChanged = true;
    }
    if (portal || greetingChanged) save(id, shipment, d, !rerankPortal);
    checkCarrierRestrictions(id, target(id), d);
    if (d.offer().validUntil() != null && !d.offer().validUntil().isAfter(clock.instant()))
      throw new Conflict("Quote rate expired.");
    var reply = new HashMap<String, Object>();
    reply.put("to", m.get("sender"));
    reply.put("in_reply_to", id);
    reply.put("subject", "Re: " + m.get("subject"));
    reply.put("body", quoteHtml ? cloudline(d, shipment, copy.language()) : d.body());
    var attachments = QuoteAttachments.generate(id, details.message(), shipment, d, copy.language());
    if (!attachments.isEmpty()) reply.put("attachments", attachments);
    if (!clock.instant().isBefore(ageLimit(m)))
      throw new Conflict("The quote window closed before the reply was ready.");
    var selected = d;
    var result = send(key, reply, () -> ledger.transactions().execute(tx -> {
      checkSheetRate(shipment, received, selected);
      return claimDelivery(key, reply);
    }));
    ledger.stage(id, "sent", insurance == null ? null : CargoInsurance.BOOKING_REVIEW);
    return result;
  }

  private void checkSheetRate(Shipment shipment, Instant received, Draft draft) {
    if ("sheet".equals(draft.offer().source()) && tariffOffers(shipment, received).stream()
        .noneMatch(offer -> draft.matchesQuotedTerms(draft.allIn(shipment, offer)))) throw new ChangedTariff();
  }

  private Draft verifyOperatorDraft(Instant deadline, Shipment shipment, Draft draft) {
    VerifiedRate rate;
    try {
      rate = currentQuoteOffer(deadline, shipment, draft, draft.offer());
    } catch (RestClientException | IllegalArgumentException ex) {
      throw new Conflict("The carrier's current rate could not be verified. Compare rates again.");
    }
    var current = rate.offer();
    if (!draft.matchesQuotedTerms(current))
      throw new Conflict("The carrier's terms changed. Compare rates again.");
    var options = draft.alternatives().stream()
        .map(offer -> offer.equals(draft.offer()) ? current : offer).toList();
    return draft.withOffer(current, options, draft.body(), rate.airEur());
  }

  private Draft rankCurrentPortalOffers(Instant deadline, Shipment shipment,
      CustomerClarifications.Details details, Draft draft, MailParser.ArrivalWindow arrival) {
    var options = new ArrayList<>(draft.alternatives());
    var verified = new HashMap<Offer, BigDecimal>();
    var ranking = Offer.order(shipment.preference());
    while (!options.isEmpty()) {
      var candidate = options.getFirst();
      if (arrival != null && !arrival.allows(quoteDay(), candidate.days())) {
        options.removeFirst();
        continue;
      }
      if ("portal".equals(candidate.source()) && !verified.containsKey(candidate)) {
        options.removeFirst();
        try {
          var rate = currentQuoteOffer(deadline, shipment, draft, candidate);
          options.add(rate.offer());
          verified.put(rate.offer(), rate.airEur());
          options.sort(ranking);
        } catch (RestClientException | IllegalArgumentException ex) {
          // Advance to the next candidate when the portal cannot verify this one.
        }
      } else if (candidate.validUntil() == null
          || candidate.validUntil().isAfter(clock.instant().plusSeconds(4))) {
        BigDecimal airEur = verified.get(candidate);
        if (airEur == null)
          airEur = candidate.equals(draft.offer()) ? draft.airEur() : candidate.totalEur()
              .subtract(draft.pickupEur() == null ? BigDecimal.ZERO : draft.pickupEur())
              .subtract(draft.insurance() == null ? BigDecimal.ZERO : draft.insurance().premiumEur());
        return draft.withOffer(candidate, options, quoteBody(details, shipment, candidate,
            draft.pickupDays(), draft.pickupWaitingDisclosure(), draft.insurance()), airEur);
      } else {
        options.removeFirst();
      }
    }
    if (arrival != null) throw new Conflict("No current rate meets the requested arrival date.");
    throw new Conflict("No current rate could be confirmed.");
  }

  private record VerifiedRate(Offer offer, BigDecimal airEur) {}

  private VerifiedRate currentQuoteOffer(Instant deadline, Shipment shipment, Draft draft, Offer candidate) {
    var now = clock.instant();
    if (!now.isBefore(deadline.minusSeconds(8)))
      throw new Conflict("The quote window closed before a live rate could be confirmed.");
    if (draft.pickupValidUntil() != null && !draft.pickupValidUntil().isAfter(now.plusSeconds(4)))
      throw new Conflict("The pickup rate expired. Compare rates again.");
    Offer air;
    try {
      air = upstream.currentRate(shipment, candidate, now);
    } catch (HttpServerErrorException.ServiceUnavailable ex) {
      air = operatorPortalRate(candidate).orElseThrow(() -> ex);
    }
    var current = draft.allIn(shipment, air);
    if (!current.validUntil().isAfter(clock.instant().plusSeconds(4)))
      throw new IllegalArgumentException("Rate expired during verification");
    return new VerifiedRate(current, air.totalEur());
  }

  private void checkCarrierRestrictions(long id, Map<String, Object> mail, Draft draft) {
    var restrictions = carrierRestrictions.forRequest(id, mail);
    if (carrierRestrictions.excludes(restrictions, draft.offer().airline())
        || (draft.trucker() != null && carrierRestrictions.excludes(restrictions, draft.trucker())))
      throw new ExcludedCarrier();
  }

  // The all-in total exactly as quoteBody writes it, so Cloudline can bold it.
  private static String cloudline(Draft d, Shipment s, String language) {
    return Cloudline.renderMailHtml(
        d.body(), "EUR " + d.offer().totalEur().toPlainString(), language, "cloudline",
        s.origin(), s.destination());
  }

  synchronized Map<String, Object> invite(long id, Shipment s, Catalog.Carrier c) {
    var m = target(id);
    checkPacking(id, m, s);
    return invite(id, s, c, details(id, m));
  }

  private Map<String, Object> invite(long id, Shipment s, Catalog.Carrier c,
      CustomerClarifications.Details details) {
    return invite(id, s, c, details, null, null);
  }

  private Map<String, Object> invite(long id, Shipment s, Catalog.Carrier c,
      CustomerClarifications.Details details, Long revisionReply, Integer pieces) {
    return invite(id, s, c, details, revisionReply, pieces, id, null);
  }

  private Map<String, Object> invite(long id, Shipment s, Catalog.Carrier c,
      CustomerClarifications.Details details, Long revisionReply, Integer pieces, long tenderId, Integer number) {
    if (carrierRestrictions.excludes(carrierRestrictions.forRequest(id, details.message()), c.name()))
      throw new ExcludedCarrier();
    if (catalog.problem(s) != null || !c.serves(c.truck() ? s.pickupZone() : s.destination()))
      throw new IllegalArgumentException("Check shipment and carrier coverage.");
    var tenders = ledger.tenders(tenderId);
    var previous = tenders.stream().filter(t -> c.email().equals(t.get("email"))).findFirst();
    if (revisionReply == null && s.pickupZone() != null && "cheapest".equals(s.preference()) && previous.isEmpty()
        && tenders.size() >= Catalog.MAX_INVITATIONS)
      throw new Conflict("This request has already used all five carrier invitations.");
    boolean citywide = c.truck() && details.area() != null;
    String base = "bid:" + id + ":" + c.email();
    String action = base;
    if (number != null) {
      action = base + ":shipment-" + number;
    } else if (revisionReply != null) {
      action = base + ":" + revisionReply + (citywide ? ":area" : "");
    } else if (details.latestReply() != null) {
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
            tenderId,
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
    var arrival = arrivalWindow(details);
    var ready = MailParser.cargoReadyDate(Ledger.str(details.message(), "body"));
    String dates = arrival != null && arrival.complete()
        ? " The cargo is ready on %s and must reach %s by %s."
            .formatted(arrival.readyOn(), s.destination(), arrival.arriveBy())
        : ready != null ? " The cargo is ready on " + ready + "." : "";
    return send(
        action,
        Map.of(
            "to",
            c.email(),
            "subject",
            "Rate request AEIOU-" + id + (number == null ? "" : " | shipment " + number) + " | " + leg,
            "body",
            "Hi,\n\nCould you quote "
                + service
                + " for a shipment weighing "
                + Shipment.displayKg(s.weightKg())
                + " kg gross, with a total volume of "
                + Shipment.displayCbm(s.volumeCbm())
                + " cbm" + (pieces == null ? "" : " across " + pieces + (pieces == 1 ? " piece" : " pieces")) + "?"
                + (revisionReply == null ? dates : " These are the revised shipment details." + dates)
                + (c.truck()
                    ? "\n\nPlease send your all-in rate in EUR and the estimated transit time from"
                        + " actual pickup to airport delivery, excluding the wait before collection."
                        + " Please give the rate validity and these figures directly in the reply text."
                    : "\n\nPlease send your all-in rate in EUR and your estimated transit time."
                        + (QuoteChecks.scheduleInformation(details.message())
                            ? " Please also confirm the cargo acceptance cutoff at " + s.origin()
                                + " and scheduled arrival at " + s.destination() + " for this cargo ready date."
                                + " Reply on separate lines as Cargo acceptance cutoff: <timestamp> and"
                                + " Scheduled arrival: <timestamp>, each in ISO 8601 with UTC offset."
                            : ""))
                + (citywide ? " The customer confirmed the town and has yet to confirm a street"
                    + " address. Can you make this a firm rate with the same transit time for"
                    + " every pickup address in " + details.area() + "? If so, please write: Valid for all of "
                    + details.area() + "." : "")
                + "\n\nThanks,\nTeam AEIOU"));
  }

  private boolean isRevisionTender(Map<String, Object> tender, long reply) {
    String key = "bid:" + tender.get("request_id") + ":" + tender.get("email") + ":" + reply;
    String action = Ledger.bidAction(tender);
    return action.equals(key) || action.equals(key + ":area");
  }

  @Scheduled(fixedDelayString = "${dispatch.poll-ms}")
  synchronized void repriceQuotedShipments() {
    Instant now = clock.instant();
    if (!automatic || now.isBefore(start)) return;
    var cases = ledger.jdbc().queryForList("""
        SELECT r.*,json_extract(q.response,'$.id') AS first_quote
        FROM mail r JOIN deliveries q ON q.action='quote:'||r.id AND q.state='sent'
        WHERE r.kind='request' AND r.booking IS NULL AND julianday(r.received)>=julianday(?)
          AND json_extract(q.response,'$.id') IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM booking_attempt b WHERE b.request_id=r.id AND b.submitted<>0)
          AND EXISTS (SELECT 1 FROM mail f WHERE f.sender=r.sender AND f.kind='follow-up'
            AND f.id>json_extract(q.response,'$.id') AND julianday(f.received)>julianday(?)
            AND (f.root IN (r.id,json_extract(q.response,'$.id')) OR f.reply_to=json_extract(q.response,'$.id') OR EXISTS (
              SELECT 1 FROM deliveries u WHERE (u.action='requote:'||r.id OR u.action LIKE 'requote:'||r.id||':%'
                OR u.action='schedule-update:'||r.id)
                AND json_extract(u.response,'$.id') IN (f.root,f.reply_to))))
        ORDER BY r.id
        """, start.toString(), now.minusSeconds(360).toString());
    for (var request : cases) {
      long id = Ledger.id(request.get("id"));
      try {
        repriceQuotedShipment(id, request);
      } catch (RetryLater ex) {
        // The next scheduled pass can resume confirmed rate limits.
      } catch (RuntimeException ex) {
        ledger.stage(id, "sent", ex instanceof Conflict ? ex.getMessage() : "The updated shipment needs operator review.");
        log.warn("Could not reprice RFQ {} ({})", id, ex.getClass().getSimpleName());
      }
    }
  }

  private void repriceQuotedShipment(long id, Map<String, Object> request) {
    if (separateShipments(request) != null) return;
    var revised = ledger.latestRequote(id);
    if (revised != null && (!"sent".equals(revised.get("state")) || revised.get("message_id") == null)) return;
    long previousQuote = Ledger.id(revised == null ? request.get("first_quote") : revised.get("message_id"));
    String sender = Ledger.str(request, "sender");
    var replies = ledger.quoteReplies(id, sender, previousQuote);
    if (replies.isEmpty()) return;
    var reply = replies.getFirst();
    String replyText = Ledger.str(reply, "body");
    boolean rateChanged = !ledger.jdbc().queryForList("""
        SELECT 1 FROM booking_attempt WHERE request_id=? AND submitted=0
          AND quote_id IS ? AND issue=?
        """, Integer.class, id, revised == null ? null : previousQuote, PortalBookings.RATE_CHANGE_ISSUE).isEmpty()
        && (PortalBookings.accepted(replyText) || gateway.bookingApproval(Ledger.id(reply.get("id")), replyText));
    if (!rateChanged && !QuoteChecks.changesQuote(replyText)) return;
    long replyId = Ledger.id(reply.get("id"));
    Instant received = Ledger.instant(reply.get("received"));
    if (received == null || !clock.instant().isBefore(received.plusSeconds(352))) return;
    Instant deadline = received.plusSeconds(360);
    String action = "requote:" + id + ":" + replyId;
    if (ledger.delivery(action) != null) return;
    checkSendRetry(action);

    var prior = ledger.read(request.get("fields"), Shipment.class);
    var previousDraft = ledger.read(request.get("proposal"), Draft.class);
    var originalInsurance = previousDraft != null && previousDraft.insurance() != null
        ? previousDraft.insurance() : CargoInsurance.from(request);
    QuoteChecks.preflight(reply);
    var insurance = CargoInsurance.revised(originalInsurance, replyText);
    var change = MailParser.shipmentRevision(prior, "Please update the quote.\n" + replyText).orElse(null);
    if (prior != null && change == null && (rateChanged
        || (!Objects.equals(originalInsurance, insurance) && !QuoteChecks.shippingTermsChanged(replyText)))) {
      change = new MailParser.ShipmentRevision(prior.withPickup(null, 0), null);
    }
    var files = ledger.read(reply.get("attachments"), List.class);
    if (change == null || catalog.problem(change.shipment()) != null
        || (files != null && !files.isEmpty())) {
      ledger.stage(id, "sent", "Review the customer's updated shipment details before quoting.");
      return;
    }
    var shipment = change.shipment();
    var details = details(id, request);
    if (checkPacking(id, request, details) != null) {
      ledger.stage(id, "sent", "Verify the packing documents against the revised shipment.");
      return;
    }
    for (var carrier : invitations(id, shipment, details.pickupReply() != null)) {
      try {
        invite(id, shipment, carrier, details, replyId, change.pieces());
      } catch (ExcludedCarrier ex) {
        continue;
      }
    }
    var tenders = ledger.tenders(id).stream().filter(tender -> isRevisionTender(tender, replyId)).toList();
    String bidPrefix = "bid:" + id + ":%:" + replyId;
    var first = Ledger.instant(ledger.jdbc().queryForObject("""
        SELECT MIN(attempted_at) FROM deliveries WHERE action LIKE ? OR action LIKE ?
        """, String.class, bidPrefix, bidPrefix + ":area"));
    Instant waitUntil = (first == null ? received : first).plusSeconds(waitSeconds);
    boolean pendingBids = tenders.stream().anyMatch(tender -> tender.get("reply_id") == null);
    if ((pendingBids && clock.instant().isBefore(waitUntil))
        || (recoverAttachedBids(id) && clock.instant().isBefore(deadline.minusSeconds(20)))) {
      return;
    }
    if (shipment.pickupZone() != null) {
      askTruckTransit(id, shipment, details, tenders, deadline, false);
    }
    String pricing = "requote-price:" + id + ":" + replyId;
    checkSendRetry(pricing);
    ledger.jdbc().update("INSERT OR REPLACE INTO retry VALUES(?,?)", pricing, clock.instant().plusSeconds(8).getEpochSecond());
    var calculation = calculate(id, shipment, null, false,
        new PricingContext(request, details, received, deadline, replyId, insurance));
    var draft = calculation.draft();
    if (draft.offer() == null) {
      ledger.stage(id, "sent", String.join(" ", draft.notes()));
      return;
    }
    var arrival = arrivalWindow(details);
    if ("portal".equals(draft.offer().source()))
      draft = rankCurrentPortalOffers(deadline, shipment, details, draft, arrival);
    if (draft.offer().validUntil() != null && !draft.offer().validUntil().isAfter(clock.instant().plusSeconds(4)))
      throw new Conflict("A revised carrier rate expired. Comparing rates again.");
    var language = details.language();
    var confirmation = language.text(
        "This updated offer uses your total volume of %s m³.",
        "Cette offre mise à jour tient compte de votre volume total de %s m³.",
        "Dieses aktualisierte Angebot berücksichtigt Ihr Gesamtvolumen von %s m³.",
        "本次更新报价按您的总体积 %s 立方米计算。").formatted(Shipment.displayCbm(shipment.volumeCbm()));
    if (rateChanged || sameAirShipment(prior, shipment)) {
      confirmation = language.text(
          "This is an updated offer. Please confirm whether you accept the revised all-in price.",
          "Voici une offre mise à jour. Merci de confirmer si vous acceptez le nouveau tarif tout compris.",
          "Dies ist ein aktualisiertes Angebot. Bitte bestätigen Sie, ob Sie den neuen Gesamtpreis akzeptieren.",
          "这是更新后的报价，请确认您是否接受新的全包价格。");
    }
    String body = draft.body();
    int introduction = body.indexOf("\n\n");
    body = body.substring(0, introduction + 2) + confirmation + "\n\n" + body.substring(introduction + 2);
    QuoteChecks.verify(details, shipment, draft, body, insurance);
    var copy = gateway.customerCopy(action, request, language, body, List.of(draft.offer().airline()));
    if (!copy.body().equals(body)) QuoteChecks.verify(details, shipment, draft, copy.body(), insurance);
    var revisedDraft = draft.withOffer(draft.offer(), draft.alternatives(), copy.body());
    var message = new HashMap<String, Object>();
    message.put("to", sender);
    message.put("subject", "Re: " + request.get("subject"));
    message.put("in_reply_to", replyId);
    message.put("body", quoteHtml ? cloudline(revisedDraft, shipment, copy.language()) : copy.body());
    var attachments = QuoteAttachments.generate(id, details.message(), shipment, revisedDraft, copy.language());
    if (!attachments.isEmpty()) message.put("attachments", attachments);
    var saved = new HashMap<>(message);
    saved.put("updated_quote", revisedDraft);
    saved.put("updated_shipment", shipment);
    saved.put("revision_reply_id", replyId);
    if (!clock.instant().isBefore(deadline)) return;
    send(action, message, () -> ledger.transactions().execute(tx -> {
      var currentReplies = ledger.quoteReplies(id, sender, previousQuote);
      if (currentReplies.isEmpty() || Ledger.id(currentReplies.getFirst().get("id")) != replyId) return 0;
      checkSheetRate(shipment, received, revisedDraft);
      return ledger.jdbc().update("""
          INSERT OR IGNORE INTO deliveries(action,state,payload,attempted_at)
          SELECT ?,'pending',?,? FROM mail WHERE id=? AND booking IS NULL
            AND NOT EXISTS (SELECT 1 FROM booking_attempt WHERE request_id=? AND submitted<>0)
          """, action, ledger.write(saved), clock.instant().toString(), id, id);
    }), () -> {
      ledger.jdbc().update("UPDATE mail SET fields=?,proposal=? WHERE id=?",
          ledger.write(shipment), ledger.write(revisedDraft), id);
      ledger.jdbc().update("UPDATE wave_case SET reason=? WHERE id=?",
          revisedDraft.insurance() == null ? null : CargoInsurance.BOOKING_REVIEW, id);
    });
  }

  synchronized Map<String, Object> reply(long id, String body) {
    var m = ledger.mail(id);
    if ("spam".equals(m.get("kind"))) throw new Conflict("This message is marked as spam.");
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
