package ai.aeiou.dispatch;

import ai.aeiou.freight.Shipment;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
          "(?im)^\\s*(?:best (?:greetings|regards|wishes)|kind regards|regards|bestens|mit"
              + " freundlichen gr[üu]ssen|viele gr[üu](?:ss|ß)e|--)(?:[,! .]|$).*");
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
    String text = SIGNOFF.split(body.strip(), 2)[0].strip();
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
        if (e instanceof WaveWorker.Conflict
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
    if (draft == null || draft.offer() == null || draft.offer().rateId() == null)
      throw new WaveWorker.Conflict("Coordinate this booking with the carrier.");
    ledger
        .jdbc()
        .update(
            "INSERT OR IGNORE INTO booking_attempt(request_id,rate_id) VALUES(?,?)",
            id,
            draft.offer().rateId());
    String rate =
        ledger
            .jdbc()
            .queryForObject(
                "SELECT rate_id FROM booking_attempt WHERE request_id=?", String.class, id);
    Map<String, Object> receipt;
    try {
      receipt = reserve(id, rate);
    } catch (HttpClientErrorException.Gone expired) {
      // Only a definitive expired response permits switching to a new rate ID. Otherwise
      // replay the persisted ID and team reference, including after a lost acknowledgment.
      String fresh = refreshRate(ledger.read(m.get("fields"), Shipment.class), draft);
      ledger
          .jdbc()
          .update(
              "UPDATE booking_attempt SET rate_id=? WHERE request_id=? AND rate_id=?",
              fresh,
              id,
              rate);
      rate =
          ledger
              .jdbc()
              .queryForObject(
                  "SELECT rate_id FROM booking_attempt WHERE request_id=?", String.class, id);
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

  private String refreshRate(Shipment s, WaveWorker.Draft d) {
    var offer = d.offer();
    var r =
        upstream.get(
            "/v1/portal/rates?origin={o}&destination={d}&airline={a}&weight_kg={w}&volume_cbm={v}",
            s.origin(),
            s.destination(),
            offer.airline(),
            s.weightKg(),
            s.volumeCbm());
    var fx =
        switch (Ledger.str(r, "currency")) {
          case "EUR" -> BigDecimal.ONE;
          case "USD" -> new BigDecimal(Ledger.str(r, "fx_eur_per_usd"));
          default -> throw new WaveWorker.Conflict("Unsupported refreshed currency.");
        };
    var air = new BigDecimal(Ledger.str(r, "total")).multiply(fx).setScale(2, RoundingMode.HALF_UP);
    var total = air.add(d.pickupEur() == null ? BigDecimal.ZERO : d.pickupEur());
    double days =
        Double.parseDouble(Ledger.str(r, "transit_days"))
            + (d.pickupDays() == null ? 0 : d.pickupDays());
    if (!offer.airline().equals(r.get("airline"))
        || !s.origin().equals(r.get("origin"))
        || !s.destination().equals(r.get("destination"))
        || total.compareTo(offer.totalEur()) != 0
        || Math.abs(days - offer.days()) > 1e-8
        || Ledger.str(r, "rate_id").isBlank())
      throw new WaveWorker.Conflict(
          "Refreshed rate differs from the accepted price, route or transit.");
    return Ledger.str(r, "rate_id");
  }
}
