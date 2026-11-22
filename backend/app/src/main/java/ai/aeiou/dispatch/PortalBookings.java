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
  private static final Pattern APPROVAL =
      Pattern.compile(
          "(?iu)(?:(?:hi|hello|bonjour|bonsoir|guten (?:tag|morgen|abend)|dear team|good (?:morning|afternoon|evening)(?: team(?: aeiou)?)?)"
              + "[ ,!.]*\\s+)?(?:(?:thank you for (?:your|the)"
              + " (?:offer|quote)|thanks|thank you|yes|that works for us|we would like to proceed|we accept (?:the|your)"
              + " (?:offer|quote))[,.! ]+)*(?:(?:please )?(?:go ahead and |proceed to )?book"
              + " (?:it|this|this shipment|the shipment|this option|the quoted option)(?: now)?|please"
              + " proceed with (?:the |this )?booking|bitte (?:so |wie angeboten )?buchen"
              + "|veuillez proc[ée]der [àa] la r[ée]servation)[.! ]*");
  private static final Pattern SIGNOFF =
      Pattern.compile(
          "(?im)^\\s*(?:best (?:greetings|regards|wishes)|kind regards|regards|cheers|bestens|mit"
              + " freundlichen gr(?:ü|ue|u)(?:ss|ß)en|viele gr(?:ü|ue|u)(?:ss|ß)e|(?:bien )?cordialement|--)(?:[,!"
              + " .]|$).*|^\\h*thanks(?: a lot| again)? and have a (?:lovely|great|nice|good|wonderful)"
              + " (?:day|evening|weekend)[.!]*\\h*$");
  private static final Pattern NAME_SIGNATURE =
      Pattern.compile(
          "[\\p{Lu}][\\p{L}’'&.-]*(?: [\\p{Lu}][\\p{L}’'&.-]*){0,5}(?:\\R[\\p{Lu}][\\p{L}’'&.-]*(?:"
              + " [\\p{Lu}][\\p{L}’'&.-]*){0,5}){0,3}");
  private final Ledger ledger;
  private final FiveU upstream;
  private final Clock clock;
  private final boolean automatic;

  PortalBookings(
      Ledger ledger, FiveU upstream, Clock clock, @Value("#{${dispatch.auto} and !${dispatch.read-only}}") boolean automatic) {
    this.ledger = ledger;
    this.upstream = upstream;
    this.clock = clock;
    this.automatic = automatic;
  }

  static boolean accepted(String body) {
    String authored = MailParser.authoredText(body);
    if (authored.matches("(?s)(?:您好[，,]?\\s*)?(?:请|請)(?:帮我们|幫我們)?安排(?:订舱|訂艙)"
        + "[。.!]?(?:\\s*(?:谢谢|謝謝)[。！!]?)?")) return true;
    if (Pattern.compile(
            "(?iu)\\b(?:if|unless|until|hold|wait|cancel|not|never|wenn|nicht|stornieren|"
                + "si|sauf|attendre|annuler|pas)\\b")
        .matcher(authored)
        .find()) return false;
    String text = SIGNOFF.split(authored, 2)[0].strip();
    String[] blocks = text.split("\\R\\h*\\R", 2);
    if (blocks.length > 1 && NAME_SIGNATURE.matcher(blocks[1].strip()).matches()) text = blocks[0];
    return APPROVAL.matcher(text.replaceAll("\\s+", " ").strip()).matches();
  }

  @Scheduled(fixedDelay = 2500)
  void bookAccepted() {
    if (!automatic) return;
    var candidates =
        ledger
            .jdbc()
            .queryForList(
                """
                SELECT r.id, f.body FROM mail r
                JOIN deliveries d ON d.action='quote:'||r.id AND d.state='sent'
                LEFT JOIN deliveries reminder ON reminder.action='reply:'||r.id AND reminder.state='sent'
                LEFT JOIN deliveries revised ON revised.rowid=(SELECT u.rowid FROM deliveries u
                  WHERE u.action='requote:'||r.id OR u.action LIKE 'requote:'||r.id||':%'
                  ORDER BY u.rowid DESC LIMIT 1)
                JOIN mail f ON f.id=(SELECT max(x.id) FROM mail x WHERE x.kind='follow-up'
                  AND x.sender=r.sender AND (
                    (revised.action IS NULL AND x.root IN (
                      r.id,json_extract(d.response,'$.id'),json_extract(reminder.response,'$.id'))
                      AND x.id>json_extract(d.response,'$.id'))
                    OR (revised.state='sent' AND (x.reply_to=json_extract(revised.response,'$.id')
                      OR x.root IN (r.id,json_extract(revised.response,'$.id')))
                      AND x.id>json_extract(revised.response,'$.id'))))
                LEFT JOIN booking_attempt a ON a.request_id=r.id
                WHERE r.kind='request' AND r.booking IS NULL
                  AND (revised.action IS NULL OR f.reply_to=json_extract(revised.response,'$.id'))
                  AND json_extract(r.proposal,'$.offer.source')='portal'
                  AND json_extract(r.proposal,'$.offer.rate_id') IS NOT NULL
                  AND json_extract(r.proposal,'$.insurance') IS NULL
                  AND (COALESCE(a.retry_at,0)<=? OR (revised.state='sent' AND a.submitted=0
                    AND a.quote_id IS NOT json_extract(revised.response,'$.id')))
                """,
                clock.instant().getEpochSecond());
    for (var row : candidates) {
      if (!accepted(Ledger.str(row, "body"))) continue;
      long id = Ledger.id(row.get("id"));
      try {
        book(id);
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

  synchronized Object book(long id) {
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
      if (replies.isEmpty() || replies.getFirst().get("reply_to") == null
          || revisedQuote != Ledger.id(replies.getFirst().get("reply_to"))
          || !accepted(Ledger.str(replies.getFirst(), "body")))
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
      rate = verifiedBookingRate(id, shipment, draft, Ledger.str(m, "sender"), approvedQuote);
    Map<String, Object> receipt;
    try {
      receipt = reserve(id, rate);
    } catch (HttpClientErrorException.Gone expired) {
      // Only a definitive expired response permits switching to a new rate ID. Otherwise
      // replay the persisted ID and team reference, including after a lost acknowledgment.
      ledger.jdbc().update("UPDATE booking_attempt SET submitted=0 WHERE request_id=?", id);
      rate = verifiedBookingRate(id, shipment, draft, Ledger.str(m, "sender"), approvedQuote);
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
    return upstream.post(
        "/v1/portal/bookings", Map.of("rate_id", rate, "team_reference", "AEIOU-" + id));
  }

  private String verifiedBookingRate(long id, Shipment shipment, WaveWorker.Draft draft,
      String sender, Long approvedQuote) {
    Offer current;
    try {
      current = draft.allIn(shipment, upstream.currentRate(shipment, draft.offer(), clock.instant()));
    } catch (IllegalArgumentException | HttpClientErrorException.NotFound | HttpClientErrorException.Gone ex) {
      throw requestFreshQuote(id);
    }
    if (!draft.matchesQuotedTerms(current) || current.validUntil() == null
        || !current.validUntil().isAfter(clock.instant().plusSeconds(4))) throw requestFreshQuote(id);
    ledger.transactions().executeWithoutResult(tx -> {
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
