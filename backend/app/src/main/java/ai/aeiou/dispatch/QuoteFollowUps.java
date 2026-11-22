package ai.aeiou.dispatch;

import ai.aeiou.freight.Shipment;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class QuoteFollowUps {
  private static final Logger log = LoggerFactory.getLogger(QuoteFollowUps.class);
  private static final long REPLY_WAIT_SECONDS = 120;
  private static final String ELIGIBLE = """
      SELECT m.*, json_extract(q.response,'$.id') AS quote_id
      FROM mail m
      JOIN deliveries q ON q.action='quote:'||m.id AND q.state='sent'
      JOIN board_result b ON b.received=m.received AND json_extract(b.result,'$.verdict')='accepted'
      WHERE m.kind='request' AND m.booking IS NULL AND m.sender<>''
        AND COALESCE(json_extract(b.result,'$.booked'),0)=0
        AND json_extract(m.proposal,'$.offer.source')='portal'
        AND NULLIF(json_extract(m.proposal,'$.offer.rate_id'),'') IS NOT NULL
        AND json_extract(q.response,'$.id') IS NOT NULL
        AND julianday(m.received)>=julianday(?)
        AND julianday(q.attempted_at)<=julianday(?)
        AND julianday(json_extract(m.proposal,'$.offer.valid_until'))>julianday(?)
        AND NOT EXISTS (SELECT 1 FROM deliveries f WHERE f.action='reply:'||m.id)
        AND NOT EXISTS (
          SELECT 1 FROM mail f WHERE f.kind='follow-up' AND f.sender=m.sender
            AND f.id>json_extract(q.response,'$.id')
            AND f.root IN (m.id,json_extract(q.response,'$.id')))
      """;

  private final Ledger ledger;
  private final WaveWorker worker;
  private final Clock clock;
  private final boolean automatic;
  private final Instant start;

  QuoteFollowUps(Ledger ledger, WaveWorker worker, Clock clock,
      @Value("#{${dispatch.auto} and !${dispatch.read-only}}") boolean automatic, @Value("${dispatch.start-at}") Instant start) {
    this.ledger = ledger;
    this.worker = worker;
    this.clock = clock;
    this.automatic = automatic;
    this.start = start;
  }

  @Scheduled(fixedDelay = 5000)
  void followUp() {
    var now = clock.instant();
    if (!automatic || now.isBefore(start)) return;
    var cases = ledger.jdbc().queryForList(ELIGIBLE + " ORDER BY m.id",
        start.toString(), now.minusSeconds(REPLY_WAIT_SECONDS).toString(), now.toString());
    for (var request : cases) {
      long id = Ledger.id(request.get("id"));
      String action = "reply:" + id;
      var fields = ledger.read(request.get("fields"), Shipment.class);
      if (fields == null) continue;
      var language = CustomerLanguage.of(request);
      String question = language.text(
          "Would you like us to book your %s to %s shipment?"
              + " If so, please reply “Please go ahead and book it.” We’ll check the carrier’s"
              + " current rate and ask you to approve any changes before we book.",
          "Souhaitez-vous que nous réservions le transport de %s à %s ?"
              + " Si oui, merci de répondre « Veuillez procéder à la réservation. »"
              + " Nous vérifierons le tarif actuel et vous demanderons de valider tout changement.",
          "Sollen wir den Transport von %s nach %s für Sie buchen?"
              + " Antworten Sie bitte mit „Bitte so buchen.“"
              + " Wir prüfen den aktuellen Tarif und bitten Sie bei Änderungen um Bestätigung.",
          "请问您需要我们为从 %s 到 %s 的这票货物安排订舱吗？如需订舱，请回复“请安排订舱”。"
              + "我们会核实承运商的当前报价，如有变动会先请您确认。")
          .formatted(fields.origin(), fields.destination());
      try {
        var mail = Map.<String, Object>of(
            "to", request.get("sender"),
            "in_reply_to", Ledger.id(request.get("quote_id")),
            "subject", "Re: " + request.get("subject"),
            "body", worker.followUpBody(action, request, language, question, null));
        worker.send(action, mail, () -> claim(id, action, mail));
        log.info("RFQ {}: sent booking follow-up", id);
      } catch (WaveWorker.RetryLater ex) {
        continue;
      } catch (WaveWorker.Conflict ex) {
        log.info("RFQ {} booking follow-up: {}", id, ex.getMessage());
      }
    }
  }

  private static final java.util.regex.Pattern CANCELLATION = java.util.regex.Pattern.compile(
      "(?imu)\\b(?:cancel|withdraw)(?:l?ing)?\\h+(?:the|this|our|my)\\h+(?:shipment|booking|order|request|enquiry|inquiry)\\b"
          + "|\\bdo\\h+not\\h+(?:proceed\\h+with|go\\h+ahead\\h+with)\\h+(?:the\\h+|this\\h+)?booking\\b"
          + "|\\b(?:bitte\\h+)?(?:die\\h+)?(?:sendung|buchung|anfrage)\\h+(?:bitte\\h+)?stornieren\\b"
          + "|\\bannuler\\h+(?:l['’]envoi|la\\h+r[ée]servation|la\\h+demande)\\b"
          // "Cancel please.", "Please cancel it.", "We no longer need the shipment.", "Storno bitte."
          + "|(?:^|[.!?]\\h*)\\h*(?:(?:please|pls|kindly)\\h+)?cancel(?:\\h+(?:it|that|this|everything|the\\h+(?:quote|offer"
          + "|order)))?(?:,?\\h+(?:please|thanks|thank you))?\\h*[.!]?\\h*$"
          + "|\\b(?:we|i)\\h+(?:need|want|would\\h+like|have|must)\\h+to\\h+cancel(?:\\h+(?:it|this|the\\h+(?:shipment"
          + "|booking|order|request)))?\\h*[.!]?\\h*$"
          + "|\\bwe\\h+(?:no\\h+longer|don['’]t|do\\h+not)\\h+need\\h+(?:it|the\\h+(?:shipment|booking|transport))"
          + "(?:\\h+any\\h*more)?\\b(?!\\h+(?:to|until|before))"
          + "|(?:^|[.!?]\\h*)\\h*(?:bitte\\h+)?storn(?:o|ieren)(?:\\h+bitte)?\\h*[.!]?\\h*$"
          + "|\\b(?:veuillez\\h+)?annulez(?:\\h+(?:la|le|l['’])\\p{L}*)?\\b|(?<!\\p{L})(?:cancelar|annullare|cancelen)(?!\\p{L})");

  private static final java.util.regex.Pattern NOT_CANCELLING = java.util.regex.Pattern.compile(
      "(?iu)\\b(?:do\\h+not|don['’]t|no\\h+need\\h+to|no|non|won['’]t|will\\h+not|nicht)\\h+(?:\\p{L}+\\h+){0,2}"
          + "(?:cancel|stornieren|annuler|cancelar|annullare)");

  /** Whether the customer's own reply withdraws the request. */
  static boolean cancels(String body) {
    String reply = ai.aeiou.freight.MailParser.authoredText(body);
    return CANCELLATION.matcher(reply).find() && !NOT_CANCELLING.matcher(reply).find();
  }

  /**
   * A customer who withdraws after our quote and before any booking gets a short confirmation that
   * nothing was booked (live #43292 and #53112 heard nothing). A shipment that is already booked is
   * left to the desk: the portal offers no cancellation we could confirm here.
   */
  @Scheduled(fixedDelay = 5000)
  void acknowledgeCancellations() {
    var now = clock.instant();
    if (!automatic || now.isBefore(start)) return;
    var cases = ledger.jdbc().queryForList("""
        SELECT m.*, f.id AS reply_id, f.body AS reply_body
        FROM mail m
        JOIN deliveries q ON q.action='quote:'||m.id AND q.state='sent'
        JOIN mail f ON f.id=(SELECT max(x.id) FROM mail x WHERE x.kind='follow-up' AND x.sender=m.sender
          AND x.id>json_extract(q.response,'$.id')
          AND (x.root IN (m.id,json_extract(q.response,'$.id')) OR x.reply_to=json_extract(q.response,'$.id')))
        WHERE m.kind='request' AND m.booking IS NULL AND m.sender<>''
          AND julianday(m.received)>=julianday(?)
          AND NOT EXISTS (SELECT 1 FROM deliveries c WHERE c.action='cancel-ack:'||m.id)
          AND NOT EXISTS (SELECT 1 FROM shipment_leg l WHERE l.request_id=m.id AND l.booking IS NOT NULL)
        ORDER BY m.id
        """, start.toString());
    for (var request : cases) {
      if (!cancels(Ledger.str(request, "reply_body"))) continue;
      long id = Ledger.id(request.get("id"));
      String action = "cancel-ack:" + id;
      String approved = Greeting.at(request, now) + "\n\n"
          + "Thank you for letting us know. We have not booked the shipment and have closed your request,"
          + " so there is nothing further you need to do.\n\n"
          + "We would be glad to quote your next shipment.\n\n"
          + "Best regards,\nTeam AEIOU";
      try {
        var mail = Map.<String, Object>of(
            "to", request.get("sender"),
            "in_reply_to", Ledger.id(request.get("reply_id")),
            "subject", "Re: " + request.get("subject"),
            "body", worker.inCustomerLanguage(action, request, approved, java.util.List.of()));
        worker.send(action, mail, () -> ledger.jdbc().update("""
            INSERT OR IGNORE INTO deliveries(action,state,payload,attempted_at)
            SELECT ?,'pending',?,? WHERE (SELECT booking FROM mail WHERE id=?) IS NULL
            """, action, ledger.write(mail), now.toString(), id));
        log.info("RFQ {}: acknowledged the customer's cancellation", id);
      } catch (WaveWorker.RetryLater ex) {
        continue;
      } catch (WaveWorker.Conflict ex) {
        log.info("RFQ {} cancellation reply: {}", id, ex.getMessage());
      }
    }
  }

  private int claim(long id, String action, Map<String, Object> mail) {
    var now = clock.instant();
    return ledger.jdbc().update("""
        INSERT OR IGNORE INTO deliveries(action,state,payload,attempted_at)
        SELECT ?, 'pending', ?, ? FROM (
        """ + ELIGIBLE + ") WHERE id=?", action, ledger.write(mail), now.toString(),
        start.toString(), now.minusSeconds(REPLY_WAIT_SECONDS).toString(), now.toString(), id);
  }
}
