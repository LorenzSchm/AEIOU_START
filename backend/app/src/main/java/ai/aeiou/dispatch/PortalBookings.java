package ai.aeiou.dispatch;

import ai.aeiou.freight.MailParser;
import ai.aeiou.freight.Offer;
import ai.aeiou.freight.Shipment;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

@Service
class PortalBookings {
  private static final String RATE_CHANGE =
      "The carrier rate changed, expired or could not be verified. Get customer approval for a new quote.";
  static final String RATE_CHANGE_ISSUE = "Booking needs attention: " + RATE_CHANGE;
  private static final Logger log = LoggerFactory.getLogger(PortalBookings.class);
  static final long BOOKING_GRACE_SECONDS = 45;
  private static final Pattern APPROVAL =
      Pattern.compile(
          "(?iu)(?:(?:hi|hello|bonjour|bonsoir|guten (?:tag|morgen|abend)|dear team|good (?:morning|afternoon|evening)(?: team(?: aeiou)?)?)"
              + "[ ,!.]*\\s+)?(?:(?:thank you for (?:your|the)"
              + " (?:offer|quote)|thanks|thank you|yes|that works for us|we would like to proceed|we accept (?:the|your)"
              + " (?:offer|quote))[,.! ]+)*(?:(?:please )?(?:go ahead and |proceed to )?book"
              + " (?:it|this|this shipment|the shipment|this option|the quoted option)(?: now)?|please"
              + " proceed with (?:the |this )?booking|bitte (?:so |wie angeboten )?buchen"
              + "|veuillez proc[ée]der [àa] la r[ée]servation)"
              + "(?: (?:as quoted|under (?:these|the quoted) (?:terms|conditions)))?[.! ]*");
  private static final Pattern TURKISH_APPROVAL = Pattern.compile(
      "(?iu)(?:merhaba[,.! ]+)?(?:(?:teklifiniz için teşekkür (?:ederiz|ediyorum|ediyoruz)|teklifiniz için teşekkürler|teşekkürler"
          + "|evet|(?:bu teklif|bu seçenek|bu) bizim için uygun(?:dur)?|bizim için uygun(?:dur)?"
          + "|teklifinizi kabul ediyoruz)[,.! ]+)*(?:lütfen )?"
          + "(?:(?:bu (?:gönderi|seçenek|teklif) için )?rezervasyon(?:u)? (?:yapın(?:ız)?|yapabilirsiniz"
          + "|yapmanızı rica ederiz|gerçekleştirin(?:iz)?|tamamlayın(?:ız)?)"
          + "|rezervasyona devam edin(?:iz)?|bu (?:gönderiyi|seçeneği|teklifi) rezerve edin(?:iz)?)"
          + "(?:[, ]+lütfen)?[.! ]*");
  private static final Pattern SIGNOFF =
      Pattern.compile(
          "(?imu)^\\s*(?:best (?:greetings|regards|wishes)|kind regards|regards|cheers|bestens|mit"
              + " freundlichen gr(?:ü|ue|u)(?:ss|ß)en|viele gr(?:ü|ue|u)(?:ss|ß)e|(?:bien )?cordialement"
              + "|saygılar(?:ımızla|ımla)?|iyi çalışmalar|--)(?:[,!"
              + " .]|$).*|^\\h*thanks(?: a lot| again)? and have a (?:lovely|great|nice|good|wonderful)"
              + " (?:day|evening|weekend)[.!]*\\h*$");
  private static final Pattern NAME_SIGNATURE =
      Pattern.compile(
          "[\\p{Lu}][\\p{L}’'&.-]*(?: [\\p{Lu}][\\p{L}’'&.-]*){0,5}(?:\\R[\\p{Lu}][\\p{L}’'&.-]*(?:"
              + " [\\p{Lu}][\\p{L}’'&.-]*){0,5}){0,3}");
  private static final Pattern CONDITIONAL = Pattern.compile(
      "(?iu)\\b(?:if|unless|until|hold|wait|cancel|not|never|wenn|nicht|stornieren|"
          + "si|sauf|attendre|annuler|pas|eğer|şartıyla|değil|iptal|bekleyin)\\b");
  private static final String SHIPMENT_TARGET =
      "(?:the )?(first|second|third|1st|2nd|3rd) (?:shipment|consignment|one)"
          + "|shipment (?:no\\.? ?|number |#)?([1-9])"
          + "|(?:the )?(?:shipment|consignment) to ([a-z]{3})"
          + "|(?:the )?([a-z]{3}) (?:shipment|consignment)"
          + "|(both)(?: shipments| of them)?|(all)(?: (?:two|three))? shipments|them (both)";
  private static final Pattern TARGET = Pattern.compile("(?iu)" + SHIPMENT_TARGET);
  private static final String TARGETS =
      "((?:" + SHIPMENT_TARGET + ")(?:(?:,| and|, and) (?:" + SHIPMENT_TARGET + "))*)";
  private static final Pattern SHIPMENT_BOOKING = Pattern.compile(
      "(?iu)(?:(?:yes|great|perfect|thanks|thank you)[,!]? )?(?:please )?(?:go ahead and |proceed to )?book "
          + TARGETS
          + "(?: as well| too)?(?: now| right away| today)?(?: as quoted)?(?: please)?");
  private static final Pattern SHIPMENT_DEFERRAL = Pattern.compile(
      "(?iu)we(?: will| shall|[’']ll) (?:confirm|decide on) " + TARGETS
          + " (?:later|separately|soon|shortly|in due course)");
  private static final Pattern SHIPMENT_PLEASANTRY = Pattern.compile(
      "(?iu)(?:hi|hello|dear team(?: aeiou)?|good (?:morning|afternoon|evening)(?: team(?: aeiou)?)?"
          + "|thank you(?: (?:very|so) much)?(?: for (?:your|the) (?:offers?|quotes?|prices|proposal))?"
          + "|thanks(?: a lot)?|yes|great|perfect"
          + "|(?:both|the|these|your) (?:prices|quotes|offers|rates)(?: both)? (?:work|look good|are fine)"
          + " for us|(?:that|this) works for us|we accept (?:the|your|both) (?:offers?|quotes?|prices))");
  private final Ledger ledger;
  private final FiveU upstream;
  private final Clock clock;
  private final boolean automatic;
  private final RfqGateway gateway;

  PortalBookings(Ledger ledger, FiveU upstream, Clock clock, boolean automatic) {
    this(ledger, upstream, clock, automatic, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  PortalBookings(Ledger ledger, FiveU upstream, Clock clock,
      @Value("#{${dispatch.auto} and !${dispatch.read-only}}") boolean automatic, RfqGateway gateway) {
    this.ledger = ledger;
    this.upstream = upstream;
    this.clock = clock;
    this.automatic = automatic;
    this.gateway = gateway;
  }

  static boolean accepted(String body) {
    String authored = MailParser.authoredText(body);
    if (authored.matches("(?s)(?:您好[，,]?\\s*)?(?:请|請)(?:帮我们|幫我們)?安排(?:订舱|訂艙)"
        + "[。.!]?(?:\\s*(?:谢谢|謝謝)[。！!]?)?")) return true;
    if (Pattern.compile(
            "(?iu)\\b(?:if|unless|until|hold|wait|cancel|not|never|wenn|nicht|stornieren|"
                + "si|sauf|attendre|annuler|pas|eğer|şartıyla|değil|iptal|bekleyin)\\b")
        .matcher(authored)
        .find()) return false;
    String text = SIGNOFF.split(authored, 2)[0].strip();
    String[] blocks = text.split("\\R\\h*\\R", 2);
    if (blocks.length > 1 && NAME_SIGNATURE.matcher(blocks[1].strip()).matches()) text = blocks[0];
    String instruction = text.replaceAll("\\s+", " ").strip();
    return APPROVAL.matcher(instruction).matches() || TURKISH_APPROVAL.matcher(instruction).matches();
  }

  /**
   * The shipments of a separately priced quote that the customer told us to book, by number
   * (1-based); empty unless every sentence is a pleasantry, a booking instruction naming shipments
   * or a deferral of other shipments. Conditions, holds and unnamed shipments never book.
   */
  static Set<Integer> acceptedShipments(String body, List<String> destinations) {
    String authored = MailParser.authoredText(body);
    if (CONDITIONAL.matcher(authored).find()) return Set.of();
    String text = SIGNOFF.split(authored, 2)[0].strip();
    String[] blocks = text.split("\\R\\h*\\R", 2);
    if (blocks.length > 1 && NAME_SIGNATURE.matcher(blocks[1].strip()).matches()) text = blocks[0];
    var booked = new TreeSet<Integer>();
    var deferred = new TreeSet<Integer>();
    for (String raw : text.replaceAll("\\s+", " ").strip().split("(?<=[.!?;])\\s+|[,;]?\\s+but\\s+")) {
      String clause = raw.replaceAll("[.!?;,]+$", "").strip();
      if (clause.isEmpty() || SHIPMENT_PLEASANTRY.matcher(clause).matches()) continue;
      var booking = SHIPMENT_BOOKING.matcher(clause);
      var deferral = SHIPMENT_DEFERRAL.matcher(clause);
      Set<Integer> into;
      String targets;
      if (booking.matches()) {
        into = booked;
        targets = booking.group(1);
      } else if (deferral.matches()) {
        into = deferred;
        targets = deferral.group(1);
      } else {
        return Set.of();
      }
      var named = TARGET.matcher(targets);
      while (named.find()) {
        var numbers = shipmentNumbers(named, destinations);
        if (numbers.isEmpty()) return Set.of();
        into.addAll(numbers);
      }
    }
    if (booked.isEmpty() || booked.stream().anyMatch(deferred::contains)) return Set.of();
    return Set.copyOf(booked);
  }

  private static Set<Integer> shipmentNumbers(java.util.regex.MatchResult target, List<String> destinations) {
    int count = destinations.size();
    if (target.group(5) != null || target.group(6) != null || target.group(7) != null) {
      var all = new TreeSet<Integer>();
      for (int number = 1; number <= count; number++) all.add(number);
      return all;
    }
    int number;
    if (target.group(1) != null) {
      number = switch (target.group(1).toLowerCase(Locale.ROOT)) {
        case "first", "1st" -> 1;
        case "second", "2nd" -> 2;
        default -> 3;
      };
    } else if (target.group(2) != null) {
      number = Integer.parseInt(target.group(2));
    } else {
      String airport = (target.group(3) != null ? target.group(3) : target.group(4)).toUpperCase(Locale.ROOT);
      if (Collections.frequency(destinations, airport) != 1) return Set.of();
      number = destinations.indexOf(airport) + 1;
    }
    return number >= 1 && number <= count ? Set.of(number) : Set.of();
  }

  /** Books the shipments a customer approved from a quote with a separate price for each shipment. */
  @Scheduled(fixedDelay = 2500)
  void bookAcceptedShipments() {
    if (!automatic) return;
    long now = clock.instant().getEpochSecond();
    var candidates = ledger.jdbc().queryForList("""
        SELECT r.id, f.body FROM mail r
        JOIN deliveries d ON d.action='quote:'||r.id AND d.state='sent'
        JOIN mail f ON f.id=(SELECT max(x.id) FROM mail x WHERE x.kind='follow-up' AND x.sender=r.sender
          AND (x.root IN (r.id,json_extract(d.response,'$.id')) OR x.reply_to=json_extract(d.response,'$.id'))
          AND x.id>json_extract(d.response,'$.id'))
        WHERE r.kind='request' AND r.proposal IS NULL
          AND (f.received IS NULL OR julianday(f.received)<=julianday(?))
          AND NOT EXISTS (SELECT 1 FROM deliveries u
            WHERE u.action='requote:'||r.id OR u.action LIKE 'requote:'||r.id||':%')
          AND EXISTS (SELECT 1 FROM shipment_leg l LEFT JOIN booking_attempt a ON a.request_id=l.leg_id
            WHERE l.request_id=r.id AND l.proposal IS NOT NULL AND l.booking IS NULL
              AND COALESCE(a.retry_at,0)<=?)
        """, settled(), now);
    for (var row : candidates) {
      long id = Ledger.id(row.get("id"));
      var legs = ledger.jdbc().queryForList("""
          SELECT l.*,COALESCE(a.retry_at,0) AS retry_at FROM shipment_leg l
          LEFT JOIN booking_attempt a ON a.request_id=l.leg_id
          WHERE l.request_id=? AND l.proposal IS NOT NULL ORDER BY l.number
          """, id);
      var destinations = legs.stream()
          .map(leg -> ledger.read(leg.get("shipment"), Shipment.class).destination()).toList();
      var approved = acceptedShipments(Ledger.str(row, "body"), destinations);
      for (int index = 0; index < legs.size(); index++) {
        var leg = legs.get(index);
        if (!approved.contains(index + 1) || leg.get("booking") != null
            || Ledger.id(leg.get("retry_at")) > now) continue;
        try {
          bookShipment(id, leg);
        } catch (RuntimeException e) {
          String issue = recordFailure(Ledger.id(leg.get("leg_id")), e);
          log.warn("RFQ {} shipment {}: {}", id, leg.get("number"), issue);
        }
      }
    }
  }

  /**
   * Replies received up to this instant may book. Live customers have cancelled about 20 seconds
   * after accepting (#43292, #53112); a booking made in between cost a 20-point adjustment, so an
   * approval only books once it has stood unchanged for a short while.
   */
  private String settled() {
    return clock.instant().minusSeconds(BOOKING_GRACE_SECONDS).toString();
  }

  private String recordFailure(long attempt, RuntimeException e) {
    long retry = clock.instant().getEpochSecond() + 30;
    if ((e instanceof WaveWorker.Conflict && !(e instanceof WaveWorker.RetryLater))
        || (e instanceof HttpClientErrorException ex
            && ex.getStatusCode().value() != 429
            && ex.getStatusCode().value() != 408)) retry = Long.MAX_VALUE;
    String issue = "Booking needs attention: "
        + (e instanceof WaveWorker.Conflict ? e.getMessage() : e.getClass().getSimpleName());
    ledger.jdbc().update("UPDATE booking_attempt SET retry_at=?,issue=? WHERE request_id=?", retry, issue, attempt);
    return issue;
  }

  private synchronized void bookShipment(long id, Map<String, Object> leg) {
    long legId = Ledger.id(leg.get("leg_id"));
    if (ledger.jdbc().queryForObject("SELECT booking FROM shipment_leg WHERE leg_id=?", String.class, legId) != null)
      return;
    var m = ledger.mail(id);
    var draft = ledger.read(leg.get("proposal"), WaveWorker.Draft.class);
    var shipment = ledger.read(leg.get("shipment"), Shipment.class);
    Long quote = ledger.jdbc().queryForObject(
        "SELECT json_extract(response,'$.id') FROM deliveries WHERE action=? AND state='sent'", Long.class,
        "quote:" + id);
    if (draft == null || draft.offer() == null || shipment == null || !"portal".equals(draft.offer().source())
        || draft.offer().rateId() == null || draft.offer().rateId().isBlank()) {
      ledger.jdbc().update("INSERT OR IGNORE INTO booking_attempt(request_id,rate_id,submitted,quote_id)"
          + " VALUES(?,'',0,?)", legId, quote);
      throw new WaveWorker.Conflict("Coordinate this booking with the carrier.");
    }
    ledger.jdbc().update(
        "INSERT OR IGNORE INTO booking_attempt(request_id,rate_id,submitted,quote_id) VALUES(?,?,0,?)",
        legId, draft.offer().rateId(), quote);
    if (draft.insurance() != null || CargoInsurance.from(m) != null)
      throw new WaveWorker.Conflict(CargoInsurance.BOOKING_REVIEW);
    if (ledger.quoteReplies(id, Ledger.str(m, "sender"), quote).stream()
        .anyMatch(reply -> QuoteChecks.changesQuote(Ledger.str(reply, "body"))))
      throw new WaveWorker.Conflict("Waiting for an updated quote after the shipment change.");
    var attempt = ledger.jdbc().queryForMap("SELECT * FROM booking_attempt WHERE request_id=?", legId);
    String rate = Ledger.str(attempt, "rate_id");
    if (Ledger.id(attempt.get("submitted")) == 0) rate = verifiedShipmentRate(legId, shipment, draft);
    // One booking per shipment: the request's team reference plus the shipment number.
    String reference = "AEIOU-" + id + "-" + leg.get("number");
    Map<String, Object> receipt;
    try {
      receipt = reserve(reference, rate);
    } catch (HttpClientErrorException.Gone expired) {
      ledger.jdbc().update("UPDATE booking_attempt SET submitted=0 WHERE request_id=?", legId);
      rate = verifiedShipmentRate(legId, shipment, draft);
      receipt = reserve(reference, rate);
    }
    if (receipt == null || Ledger.str(receipt, "booking_reference").isBlank()
        || !reference.equals(receipt.get("team_reference")) || !rate.equals(receipt.get("rate_id")))
      throw new IllegalStateException("Booking acknowledgment missing");
    ledger.jdbc().update("UPDATE shipment_leg SET booking=? WHERE leg_id=?", ledger.write(receipt), legId);
    ledger.jdbc().update("UPDATE booking_attempt SET issue=NULL WHERE request_id=?", legId);
  }

  private String verifiedShipmentRate(long legId, Shipment shipment, WaveWorker.Draft draft) {
    Offer current;
    try {
      current = draft.allIn(shipment, upstream.currentRate(shipment, draft.offer(), clock.instant()));
    } catch (IllegalArgumentException | HttpClientErrorException.NotFound | HttpClientErrorException.Gone ex) {
      throw requestFreshQuote(legId);
    }
    if (!draft.matchesQuotedTerms(current) || current.validUntil() == null
        || !current.validUntil().isAfter(clock.instant().plusSeconds(4))) throw requestFreshQuote(legId);
    ledger.jdbc().update("UPDATE booking_attempt SET rate_id=?,submitted=1 WHERE request_id=?",
        current.rateId(), legId);
    return current.rateId();
  }

  @Scheduled(fixedDelay = 2500)
  void bookAccepted() {
    if (!automatic) return;
    var candidates =
        ledger
            .jdbc()
            .queryForList(
                """
                SELECT r.id, f.id AS reply_id, f.body FROM mail r
                JOIN deliveries d ON d.action='quote:'||r.id AND d.state='sent'
                LEFT JOIN deliveries reminder ON reminder.action='reply:'||r.id AND reminder.state='sent'
                LEFT JOIN deliveries revised ON revised.rowid=(SELECT u.rowid FROM deliveries u
                  WHERE u.action='requote:'||r.id OR u.action LIKE 'requote:'||r.id||':%'
                  ORDER BY u.rowid DESC LIMIT 1)
                LEFT JOIN deliveries schedule ON schedule.action='schedule-update:'||r.id AND schedule.state='sent'
                  AND json_extract(schedule.payload,'$.in_reply_to')=
                    json_extract(CASE WHEN revised.action IS NULL THEN d.response ELSE revised.response END,'$.id')
                JOIN mail f ON f.id=(SELECT max(x.id) FROM mail x WHERE x.kind='follow-up'
                  AND x.sender=r.sender AND (
                    (revised.action IS NULL AND (x.root IN (
                      r.id,json_extract(d.response,'$.id'),json_extract(reminder.response,'$.id'),
                      json_extract(schedule.response,'$.id'))
                      OR x.root IN {price_match} OR x.reply_to IN {price_match})
                      AND x.id>json_extract(d.response,'$.id'))
                    OR (revised.state='sent' AND (x.reply_to=json_extract(revised.response,'$.id')
                      OR x.root IN (r.id,json_extract(revised.response,'$.id'),json_extract(schedule.response,'$.id')))
                      AND x.id>json_extract(revised.response,'$.id'))))
                LEFT JOIN booking_attempt a ON a.request_id=r.id
                WHERE r.kind='request' AND r.booking IS NULL
                  AND (f.received IS NULL OR julianday(f.received)<=julianday(?))
                  AND (revised.action IS NULL OR f.reply_to IN (
                    json_extract(revised.response,'$.id'),json_extract(schedule.response,'$.id')))
                  AND json_extract(r.proposal,'$.offer.source')='portal'
                  AND json_extract(r.proposal,'$.offer.rate_id') IS NOT NULL
                  AND json_extract(r.proposal,'$.insurance') IS NULL
                  AND (COALESCE(a.retry_at,0)<=? OR (revised.state='sent' AND a.submitted=0
                    AND a.quote_id IS NOT json_extract(revised.response,'$.id')))
                """.replace("{price_match}", PriceMatchReplies.sentAnswers("r.id")),
                settled(), clock.instant().getEpochSecond());
    for (var row : candidates) {
      long replyId = Ledger.id(row.get("reply_id"));
      if (!approval(replyId, row)) continue;
      long id = Ledger.id(row.get("id"));
      try {
        book(id, replyId);
      } catch (RuntimeException e) {
        long retry = clock.instant().getEpochSecond() + 30;
        if ((e instanceof WaveWorker.Conflict && !(e instanceof WaveWorker.RetryLater))
            || (e instanceof HttpClientErrorException ex
                && ex.getStatusCode().value() != 429
                && ex.getStatusCode().value() != 408)) retry = Long.MAX_VALUE;
        String issue =
            "Booking needs attention: "
                + (e instanceof WaveWorker.Conflict
                    ? e.getMessage()
                    : e.getClass().getSimpleName());
        ledger
            .jdbc()
            .update(
                "UPDATE booking_attempt SET retry_at=?,issue=? WHERE request_id=?",
                retry,
                issue,
                id);
        log.warn("RFQ {}: {}", id, issue);
      }
    }
  }

  private boolean approval(long replyId, Map<String, Object> reply) {
    String body = Ledger.str(reply, "body");
    // A price-match request is answered by PriceMatchReplies and never authorizes booking.
    if (PriceMatchReplies.requested(body)) return false;
    return accepted(body) || (gateway != null && gateway.bookingApproval(replyId, body));
  }

  synchronized Object book(long id) {
    return book(id, null);
  }

  private synchronized Object book(long id, Long approvalReply) {
    var m = ledger.mail(id);
    if (m.get("booking") != null) return ledger.read(m.get("booking"), Map.class);
    if (!"sent".equals(ledger.delivery("quote:" + id)))
      throw new WaveWorker.Conflict("Quote must be sent before booking.");
    var draft = ledger.read(m.get("proposal"), WaveWorker.Draft.class);
    var shipment = ledger.read(m.get("fields"), Shipment.class);
    Long revisedQuote = null;
    var revision = ledger.latestRequote(id);
    Long approvedQuote = ledger.jdbc().queryForObject(
        "SELECT json_extract(response,'$.id') FROM deliveries WHERE action=?", Long.class, "quote:" + id);
    if (revision != null) {
      if (!"sent".equals(revision.get("state")) || revision.get("message_id") == null)
        throw new WaveWorker.Conflict("Delivery of the updated quote must be confirmed before booking.");
      revisedQuote = Ledger.id(revision.get("message_id"));
      approvedQuote = revisedQuote;
      var replies = ledger.quoteReplies(id, Ledger.str(m, "sender"), revisedQuote);
      if (replies.isEmpty() || !repliesToRevisedQuote(id, revisedQuote, replies.getFirst())
          || !approval(Ledger.id(replies.getFirst().get("id")), replies.getFirst()))
        throw new WaveWorker.Conflict("Waiting for approval of the updated quote.");
      draft = ledger.read(revision.get("proposal"), WaveWorker.Draft.class);
      if (revision.get("shipment") != null) shipment = ledger.read(revision.get("shipment"), Shipment.class);
    }
    if (approvedQuote != null && ledger.quoteReplies(id, Ledger.str(m, "sender"), approvedQuote).stream()
        .anyMatch(reply -> QuoteChecks.changesQuote(Ledger.str(reply, "body"))))
      throw new WaveWorker.Conflict("Waiting for an updated quote after the shipment change.");
    if (draft == null || draft.offer() == null || !"portal".equals(draft.offer().source())
        || draft.offer().rateId() == null || draft.offer().rateId().isBlank())
      throw new WaveWorker.Conflict("Coordinate this booking with the carrier.");
    if (draft.insurance() != null || CargoInsurance.from(m) != null)
      throw new WaveWorker.Conflict(CargoInsurance.BOOKING_REVIEW);
    ledger
        .jdbc()
        .update(
            "INSERT OR IGNORE INTO booking_attempt(request_id,rate_id,submitted,quote_id) VALUES(?,?,0,?)",
            id,
            draft.offer().rateId(),
            revisedQuote);
    if (revisedQuote != null) {
      ledger.jdbc().update("""
          UPDATE booking_attempt SET rate_id=?,quote_id=?,retry_at=0,issue=NULL
          WHERE request_id=? AND submitted=0 AND quote_id IS NOT ?
          """, draft.offer().rateId(), revisedQuote, id, revisedQuote);
    }
    var attempt = ledger.jdbc().queryForMap("SELECT * FROM booking_attempt WHERE request_id=?", id);
    if (revisedQuote != null && (attempt.get("quote_id") == null
        || revisedQuote != Ledger.id(attempt.get("quote_id"))))
      throw new WaveWorker.Conflict("An earlier booking attempt must be resolved first.");
    String rate = Ledger.str(attempt, "rate_id");
    if (Ledger.id(attempt.get("submitted")) == 0)
      rate = verifiedBookingRate(id, shipment, draft, Ledger.str(m, "sender"), approvedQuote, approvalReply);
    Map<String, Object> receipt;
    try {
      receipt = reserve(id, rate);
    } catch (HttpClientErrorException.Gone expired) {
      // Only a definitive expired response permits switching to a new rate ID. Otherwise
      // replay the persisted ID and team reference, including after a lost acknowledgment.
      ledger.jdbc().update("UPDATE booking_attempt SET submitted=0 WHERE request_id=?", id);
      rate = verifiedBookingRate(id, shipment, draft, Ledger.str(m, "sender"), approvedQuote, approvalReply);
      receipt = reserve(id, rate);
    }
    if (Ledger.str(receipt, "booking_reference").isBlank()
        || !("AEIOU-" + id).equals(receipt.get("team_reference"))
        || !rate.equals(receipt.get("rate_id")))
      throw new IllegalStateException("Booking acknowledgment missing");
    ledger.jdbc().update("UPDATE mail SET booking=? WHERE id=?", ledger.write(receipt), id);
    return receipt;
  }

  private Map<String, Object> reserve(long id, String rate) {
    return reserve("AEIOU-" + id, rate);
  }

  private Map<String, Object> reserve(String reference, String rate) {
    return upstream.post("/v1/portal/bookings", Map.of("rate_id", rate, "team_reference", reference));
  }

  private boolean repliesToRevisedQuote(long request, long quote, Map<String, Object> reply) {
    if (reply.get("reply_to") == null) return false;
    long parent = Ledger.id(reply.get("reply_to"));
    return parent == quote || !ledger.jdbc().queryForList("""
        SELECT 1 FROM deliveries WHERE action=? AND state='sent'
          AND json_extract(payload,'$.in_reply_to')=? AND json_extract(response,'$.id')=?
        """, Integer.class, "schedule-update:" + request, quote, parent).isEmpty();
  }

  private String verifiedBookingRate(long id, Shipment shipment, WaveWorker.Draft draft,
      String sender, Long approvedQuote, Long approvalReply) {
    Offer current;
    try {
      current = draft.allIn(shipment, upstream.currentRate(shipment, draft.offer(), clock.instant()));
    } catch (IllegalArgumentException | HttpClientErrorException.NotFound | HttpClientErrorException.Gone ex) {
      throw requestFreshQuote(id);
    }
    if (!draft.matchesQuotedTerms(current) || current.validUntil() == null
        || !current.validUntil().isAfter(clock.instant().plusSeconds(4))) throw requestFreshQuote(id);
    ledger.transactions().executeWithoutResult(tx -> {
      if (approvalReply != null && approvedQuote != null) {
        Long latest = ledger.jdbc().queryForObject("""
            SELECT max(f.id) FROM mail f WHERE f.kind='follow-up' AND f.sender=? AND f.id>?
              AND (f.reply_to=? OR f.root IN (?, ?, (SELECT json_extract(response,'$.id')
                FROM deliveries WHERE action=? AND state='sent')) OR EXISTS (
                  SELECT 1 FROM deliveries s WHERE s.action=? AND s.state='sent'
                    AND json_extract(s.payload,'$.in_reply_to')=?
                    AND json_extract(s.response,'$.id') IN (f.root,f.reply_to))
              OR f.root IN {price_match} OR f.reply_to IN {price_match})
            """.replace("{price_match}", PriceMatchReplies.sentAnswers(Long.toString(id))),
            Long.class, sender, approvedQuote, approvedQuote, id, approvedQuote, "reply:" + id,
            "schedule-update:" + id, approvedQuote);
        if (!approvalReply.equals(latest)) throw new WaveWorker.RetryLater("The customer reply changed before booking.");
      }
      if (approvedQuote != null && ledger.quoteReplies(id, sender, approvedQuote).stream()
          .anyMatch(reply -> QuoteChecks.changesQuote(Ledger.str(reply, "body"))))
        throw new WaveWorker.Conflict("Waiting for an updated quote after the shipment change.");
      ledger.jdbc().update("UPDATE booking_attempt SET rate_id=?,submitted=1 WHERE request_id=?",
          current.rateId(), id);
    });
    return current.rateId();
  }

  private WaveWorker.Conflict requestFreshQuote(long id) {
    ledger.jdbc().update("UPDATE booking_attempt SET issue=?,retry_at=? WHERE request_id=? AND submitted=0",
        RATE_CHANGE_ISSUE, Long.MAX_VALUE, id);
    return new WaveWorker.Conflict(RATE_CHANGE);
  }
}
