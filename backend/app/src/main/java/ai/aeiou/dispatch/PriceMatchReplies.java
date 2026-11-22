package ai.aeiou.dispatch;

import ai.aeiou.freight.MailParser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Answers a customer who asks us to match or lower a quote we sent. Our price is the carrier's
 * verified all-in rate passed through, so we never go below it: we explain that once, offer to
 * book at the quoted price and let the normal booking approval take over.
 */
@Service
class PriceMatchReplies {
  private static final Logger log = LoggerFactory.getLogger(PriceMatchReplies.class);
  /** Only fresh requests: an old one that sat unanswered is left for the desk. */
  private static final long ANSWER_WITHIN_SECONDS = 1800;
  private static final DateTimeFormatter VALIDITY =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
  private static final Pattern PRICE_MATCH = Pattern.compile(
      "(?iu)\\b(?:match|beat)(?:es|ing)?\\h+(?:(?:their|the|that|this|his|her|its|our|a)\\h+)?"
          + "(?:(?:other|competitor['’]s|competing|lower|cheaper)\\h+)?(?:rate|price|offer|quote|quotation)s?\\b"
          + "|\\b\\d+(?:[.,]\\d+)?\\h*(?:%|percent|per\\h+cent)\\h+(?:less|lower|cheaper|below)\\b"
          + "|\\b(?:lower|reduce|drop|improve|revisit)\\h+(?:(?:your|the|this|that|our)\\h+)?(?:price|rate|offer)\\b"
          + "|\\b(?:a|any|some)\\h+(?:room\\h+for\\h+(?:a\\h+)?)?discount\\b"
          + "|\\b(?:cheaper|lower)\\h+(?:rate|price|offer|quote)\\b"
          // "Can you match 1450 EUR?", "Can you beat EUR 900?", "Can you do better?", "any flexibility?"
          + "|\\b(?:match|beat)\\h+(?:EUR|€|USD|\\$)?\\h*\\d"
          + "|\\b(?:can|could)\\h+you\\h+do\\h+(?:any\\h+|a\\h+(?:bit|little)\\h+)?better\\b"
          + "|\\b(?:any|some)\\h+(?:flexibility\\b|room\\h+(?:on|in|with)\\h+(?:the\\h+|your\\h+)?(?:price|rate)\\b)");
  private static final Pattern DECLINE = Pattern.compile(
      "(?iu)\\b(?:we|i)\\h+(?:will not|won['’]t|cannot|can['’]t|do not|don['’]t)\\h+"
          + "(?:go ahead|proceed|book|accept)\\b");
  private static final String CASES = """
      SELECT m.*, json_extract(q.response,'$.id') AS quote_id
      FROM mail m JOIN deliveries q ON q.action='quote:'||m.id AND q.state='sent'
      WHERE m.kind='request' AND m.booking IS NULL AND m.sender<>'' AND m.proposal IS NOT NULL
        AND json_extract(q.response,'$.id') IS NOT NULL
        AND julianday(m.received)>=julianday(?)
        AND NOT EXISTS (SELECT 1 FROM deliveries u
          WHERE u.action='requote:'||m.id OR u.action LIKE 'requote:'||m.id||':%')
        AND NOT EXISTS (SELECT 1 FROM booking_attempt b WHERE b.request_id=m.id AND b.submitted<>0)
        AND EXISTS (SELECT 1 FROM mail f WHERE f.kind='follow-up' AND f.sender=m.sender
          AND f.id>json_extract(q.response,'$.id') AND julianday(f.received)>julianday(?))
      ORDER BY m.id
      """;

  private final Ledger ledger;
  private final WaveWorker worker;
  private final RfqGateway gateway;
  private final Clock clock;
  private final boolean automatic;
  private final Instant start;

  PriceMatchReplies(Ledger ledger, WaveWorker worker, RfqGateway gateway, Clock clock,
      @Value("#{${dispatch.auto} and !${dispatch.read-only}}") boolean automatic,
      @Value("${dispatch.start-at}") Instant start) {
    this.ledger = ledger;
    this.worker = worker;
    this.gateway = gateway;
    this.clock = clock;
    this.automatic = automatic;
    this.start = start;
  }

  /** A customer's own words asking us to match, beat or lower the quoted price (English fallback). */
  static boolean requested(String body) {
    String authored = MailParser.authoredText(body == null ? "" : body);
    return !DECLINE.matcher(authored).find() && PRICE_MATCH.matcher(authored).find();
  }

  /** Message ids of the price-match answers sent for {@code request}, as an SQL subquery. */
  static String sentAnswers(String request) {
    return "(SELECT json_extract(pm.response,'$.id') FROM deliveries pm WHERE pm.state='sent'"
        + " AND pm.action LIKE 'price-match:'||" + request + "||':%')";
  }

  @Scheduled(fixedDelay = 2500)
  void answer() {
    var now = clock.instant();
    if (!automatic || now.isBefore(start)) return;
    for (var request : ledger.jdbc().queryForList(CASES, start.toString(),
        now.minusSeconds(ANSWER_WITHIN_SECONDS).toString())) {
      long id = Ledger.id(request.get("id"));
      try {
        answer(id, request, now);
      } catch (WaveWorker.RetryLater ex) {
        // The next pass retries after the mailbox or language rendering is ready.
      } catch (WaveWorker.Conflict ex) {
        log.info("RFQ {} price-match reply: {}", id, ex.getMessage());
      } catch (RuntimeException ex) {
        log.warn("Could not answer the price-match request for RFQ {} ({})", id, ex.getClass().getSimpleName());
      }
    }
  }

  private void answer(long id, Map<String, Object> request, Instant now) {
    String sender = Ledger.str(request, "sender");
    long quoteId = Ledger.id(request.get("quote_id"));
    var replies = ledger.quoteReplies(id, sender, quoteId);
    if (replies.isEmpty()) return;
    var reply = replies.getFirst();
    long replyId = Ledger.id(reply.get("id"));
    Instant received = Ledger.instant(reply.get("received"));
    if (received == null || received.isBefore(now.minusSeconds(ANSWER_WITHIN_SECONDS))) return;
    String action = "price-match:" + id + ":" + replyId;
    if (ledger.delivery(action) != null) return;
    String body = Ledger.str(reply, "body");
    if (PortalBookings.accepted(body) || QuoteChecks.changesQuote(body)) return;
    if (!requested(body) && !gateway.priceRequest(replyId, body)) return;
    var draft = ledger.read(request.get("proposal"), WaveWorker.Draft.class);
    if (draft == null || draft.offer() == null || draft.offer().totalEur() == null) return;

    String tag = gateway.threadLanguage(request);
    var language = tag == null ? CustomerLanguage.of(request) : CustomerLanguage.forTag(tag);
    Instant validUntil = draft.offer().validUntil();
    boolean valid = validUntil != null && validUntil.isAfter(now.plusSeconds(60));
    String approved = Greeting.at(request, now, language) + "\n\n"
        + text(language, "EUR " + draft.offer().totalEur().toPlainString(),
            valid ? VALIDITY.format(validUntil) : null)
        + "\n\n" + language.signoff();
    var copy = gateway.customerCopy(action, request, language, approved, List.of());
    var mail = Map.<String, Object>of(
        "to", sender,
        "in_reply_to", replyId,
        "subject", "Re: " + request.get("subject"),
        "body", copy.body());
    worker.send(action, mail, () -> ledger.transactions().execute(tx -> {
      var current = ledger.quoteReplies(id, sender, quoteId);
      if (current.isEmpty() || Ledger.id(current.getFirst().get("id")) != replyId) return 0;
      return ledger.jdbc().update("""
          INSERT OR IGNORE INTO deliveries(action,state,payload,attempted_at)
          SELECT ?,'pending',?,? FROM mail WHERE id=? AND booking IS NULL
            AND NOT EXISTS (SELECT 1 FROM booking_attempt WHERE request_id=? AND submitted<>0)
          """, action, ledger.write(mail), clock.instant().toString(), id, id);
    }));
    log.info("RFQ {}: answered the price-match request {}", id, replyId);
  }

  private static String text(CustomerLanguage language, String price, String validUntil) {
    String thanks = language.text(
        "Thank you for your reply and for considering us for this shipment.",
        "Merci pour votre réponse et pour l’intérêt que vous portez à notre offre.",
        "Vielen Dank für Ihre Antwort und Ihr Interesse an unserem Angebot.",
        "感谢您的回复，也感谢您考虑与我们合作。",
        "Yanıtınız ve bu gönderi için bizi düşündüğünüz için teşekkür ederiz.");
    String rate = language.text(
        "The price we quoted, %s all-in, is the carrier’s confirmed all-in rate for this shipment,"
            + " so unfortunately we cannot go below it.",
        "Le prix indiqué, %s tout compris, correspond au tarif tout compris confirmé par le transporteur"
            + " pour cet envoi ; nous ne pouvons malheureusement pas descendre en dessous.",
        "Der angebotene Preis von %s ist der vom Carrier bestätigte Gesamtpreis für diese Sendung;"
            + " darunter können wir leider nicht gehen.",
        "我们报给您的全包价 %s 是承运商为这票货物确认的全包运价，因此很抱歉我们无法再降低价格。",
        "Teklif ettiğimiz %s tutarındaki fiyat, taşıyıcının bu gönderi için onayladığı her şey dahil"
            + " fiyattır; bu nedenle maalesef bunun altına inemiyoruz.").formatted(price);
    String offer = language.text(
        "We would be glad to book it for you at the quoted price right away.",
        "Nous pouvons réserver l’envoi immédiatement au prix indiqué.",
        "Gern buchen wir die Sendung sofort zum angebotenen Preis.",
        "我们可以立即按报价为您安排订舱。",
        "Gönderiyi teklif ettiğimiz fiyattan hemen rezerve edebiliriz.");
    String validity = validUntil == null ? "" : " " + language.text(
        "The quoted rate is valid until %s.",
        "Le tarif est valable jusqu’au %s.",
        "Der Tarif ist gültig bis %s.",
        "该报价有效期至 %s。",
        "Teklif %s tarihine kadar geçerlidir.").formatted(validUntil);
    String instruction = " " + language.text(
        "If you would like to go ahead, please reply “Please go ahead and book it.”",
        "Si vous le souhaitez, merci de répondre « Veuillez procéder à la réservation. »",
        "Wenn Sie einverstanden sind, antworten Sie bitte mit „Bitte so buchen.“",
        "如需订舱，请回复“请安排订舱”。",
        "Onaylıyorsanız lütfen “Lütfen rezervasyonu yapın.” şeklinde yanıt verin.");
    String recheck = validUntil != null ? "" : " " + language.text(
        "We’ll check the carrier’s current rate before booking and ask you to approve any change.",
        "Nous vérifierons le tarif actuel du transporteur avant la réservation et vous demanderons"
            + " de valider tout changement.",
        "Vor der Buchung prüfen wir den aktuellen Tarif des Carriers und bitten Sie bei Änderungen"
            + " um Bestätigung.",
        "订舱前我们会核实承运商的当前运价，如有变动会先请您确认。",
        "Rezervasyondan önce taşıyıcının güncel fiyatını kontrol edecek, olası değişiklikleri"
            + " onayınıza sunacağız.");
    String closing = offer + validity + instruction + recheck;
    if (language == CustomerLanguage.CHINESE) closing = closing.replace("。 ", "。");
    return thanks + "\n\n" + rate + "\n\n" + closing;
  }
}
