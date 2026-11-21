package ai.aeiou.dispatch;

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
  private static final Logger log = LoggerFactory.getLogger(PortalBookings.class);
  private static final Pattern APPROVAL =
      Pattern.compile(
          "(?iu)(?:(?:hi|hello|dear team)[ ,!.]*\\s+)?(?:(?:thank you for (?:your|the)"
              + " (?:offer|quote)|thanks|thank you|yes|that works for us|we accept (?:the|your)"
              + " (?:offer|quote))[,.! ]+)*(?:(?:please )?(?:go ahead and |proceed to )?book"
              + " (?:it|this|this shipment|the shipment|this option|the quoted option)|please"
              + " proceed with (?:the |this )?booking|bitte (?:so |wie angeboten )?buchen)[.! ]*");
  private static final Pattern SIGNOFF =
      Pattern.compile(
          "(?im)^\\s*(?:best (?:greetings|regards|wishes)|kind regards|regards|cheers|bestens|mit"
              + " freundlichen gr(?:ü|ue|u)(?:ss|ß)en|viele gr(?:ü|ue|u)(?:ss|ß)e|--)(?:[,!"
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
      Ledger ledger, FiveU upstream, Clock clock, @Value("${dispatch.auto}") boolean automatic) {
    this.ledger = ledger;
    this.upstream = upstream;
    this.clock = clock;
    this.automatic = automatic;
  }

  static boolean accepted(String body) {
    String authored =
        body.split("(?im)^\\s*(?:>|On .+ wrote:|-----Original Message-----)", 2)[0].strip();
    if (Pattern.compile(
            "(?iu)\\b(?:if|unless|until|hold|wait|cancel|not|never|wenn|nicht|stornieren)\\b")
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
                JOIN mail f ON f.id=(SELECT max(x.id) FROM mail x WHERE x.kind='follow-up'
                  AND x.sender=r.sender AND x.root IN (r.id,json_extract(d.response,'$.id'))
                  AND x.id>json_extract(d.response,'$.id'))
                LEFT JOIN booking_attempt a ON a.request_id=r.id
                WHERE r.kind='request' AND r.booking IS NULL
                  AND json_extract(r.proposal,'$.offer.source')='portal'
                  AND json_extract(r.proposal,'$.offer.rate_id') IS NOT NULL
                  AND COALESCE(a.retry_at,0)<=?
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
    if (draft == null || draft.offer() == null || !"portal".equals(draft.offer().source())
        || draft.offer().rateId() == null || draft.offer().rateId().isBlank())
      throw new WaveWorker.Conflict("Coordinate this booking with the carrier.");
    ledger
        .jdbc()
        .update(
            "INSERT OR IGNORE INTO booking_attempt(request_id,rate_id,submitted) VALUES(?,?,0)",
            id,
            draft.offer().rateId());
    var attempt = ledger.jdbc().queryForMap("SELECT * FROM booking_attempt WHERE request_id=?", id);
    var shipment = ledger.read(m.get("fields"), Shipment.class);
    String rate = Ledger.str(attempt, "rate_id");
    if (Ledger.id(attempt.get("submitted")) == 0) rate = verifiedBookingRate(id, shipment, draft);
    Map<String, Object> receipt;
    try {
      receipt = reserve(id, rate);
    } catch (HttpClientErrorException.Gone expired) {
      // Only a definitive expired response permits switching to a new rate ID. Otherwise
      // replay the persisted ID and team reference, including after a lost acknowledgment.
      rate = verifiedBookingRate(id, shipment, draft);
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

  private String verifiedBookingRate(long id, Shipment shipment, WaveWorker.Draft draft) {
    Offer current;
    try {
      current = draft.allIn(shipment, upstream.currentRate(shipment, draft.offer(), clock.instant()));
    } catch (IllegalArgumentException ex) {
      throw new WaveWorker.Conflict("The accepted carrier rate could not be verified.");
    }
    if (!draft.matchesQuotedTerms(current))
      throw new WaveWorker.Conflict(
          "The carrier's price or transit changed. Get customer approval for a new quote.");
    if (!current.validUntil().isAfter(clock.instant().plusSeconds(4)))
      throw new WaveWorker.Conflict("The accepted rate or pickup price expired.");
    ledger.jdbc().update("UPDATE booking_attempt SET rate_id=?,submitted=1 WHERE request_id=?",
        current.rateId(), id);
    return current.rateId();
  }
}
