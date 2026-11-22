package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class TrackingReplies {
  private static final Logger log = LoggerFactory.getLogger(TrackingReplies.class);
  private static final Pattern REFERENCE =
      Pattern.compile("(?i)\\b(?:BK-[A-Z0-9_-]+|AEIOU-[0-9]+)\\b");
  private static final Pattern WEIGHT = Pattern.compile("\\b(\\d+(?:[.,]\\d+)?)\\s*KG\\b");
  private static final Pattern STATUS = Pattern.compile("(?im)^Status:[ \\t]*([^\\r\\n]{1,150})$");
  private final Ledger ledger;
  private final WaveWorker worker;
  private final Catalog catalog;
  private final Clock clock;
  private final boolean automatic;

  TrackingReplies(
      Ledger ledger,
      WaveWorker worker,
      Catalog catalog,
      Clock clock,
      @Value("#{${dispatch.auto} and !${dispatch.read-only}}") boolean automatic) {
    this.ledger = ledger;
    this.worker = worker;
    this.catalog = catalog;
    this.clock = clock;
    this.automatic = automatic;
  }

  @Scheduled(fixedDelay = 2500)
  void answer() {
    if (!automatic) return;
    var questions =
        ledger
            .jdbc()
            .queryForList(
                """
                SELECT m.* FROM mail m WHERE kind='tracking' AND sender='tracking@hackloop.mail'
                AND NOT EXISTS(SELECT 1 FROM deliveries WHERE action='reply:'||m.id)
                ORDER BY m.id
                """);
    for (var q : questions) {
      long id = Ledger.id(q.get("id"));
      try {
        String body = answerFor(q);
        if (body != null) worker.reply(id, body);
        else if (!"tracking review".equals(q.get("stage")))
          ledger.stage(id, "tracking review", "Match this question to a verified booking.");
      } catch (WaveWorker.RetryLater wait) {
        // The existing send path honors the mailbox Retry-After header.
      } catch (WaveWorker.Conflict review) {
        if (!"tracking review".equals(q.get("stage"))) ledger.stage(id, "tracking review", review.getMessage());
      } catch (RuntimeException e) {
        log.warn("Tracking message {}: {}", id, e.getClass().getSimpleName());
      }
    }
  }

  private String answerFor(Map<String, Object> q) {
    String question =
        Ledger.str(q, "subject")
            + "\n"
            + Ledger.str(q, "body")
                .split("(?im)^\\s*(?:>|On .+ wrote:|-----Original Message-----)", 2)[0];
    String upper = question.toUpperCase(Locale.ROOT);
    var refs = REFERENCE.matcher(upper).results().map(m -> m.group()).distinct().toList();
    List<Map<String, Object>> matches = new ArrayList<>();
    for (var m :
        ledger
            .jdbc()
            .queryForList(
                "SELECT id,fields,booking,proposal FROM mail WHERE kind='request' AND booking IS"
                    + " NOT NULL")) {
      Map<?, ?> b = ledger.read(m.get("booking"), Map.class);
      String reference = Ledger.str(b, "booking_reference").toUpperCase(Locale.ROOT);
      String team = Ledger.str(b, "team_reference").toUpperCase(Locale.ROOT);
      if (reference.isBlank()
          || !Set.of("booked", "confirmed")
              .contains(Ledger.str(b, "status").toLowerCase(Locale.ROOT))) continue;
      var s = ledger.read(m.get("fields"), Shipment.class);
      boolean lane =
          Pattern.compile(
                  "\\b"
                      + Pattern.quote(s.origin())
                      + "\\b.{0,55}\\b"
                      + Pattern.quote(s.destination())
                      + "\\b")
              .matcher(upper)
              .find();
      // Customers often name the pickup postcode instead of the origin airport ("70498 to EZE,
      // 544 kg"); accept it when the postcode lies in the booked pickup zone and the weight agrees.
      boolean pickupLane =
          s.pickupZone() != null
              && !s.pickupZone().isBlank()
              && Pattern.compile(
                      "\\b"
                          + Pattern.quote(s.pickupZone())
                          + "\\d{"
                          + Math.max(0, 5 - s.pickupZone().length())
                          + "}\\b.{0,55}\\b"
                          + Pattern.quote(s.destination())
                          + "\\b")
                  .matcher(upper)
                  .find()
              && weighs(upper, s);
      boolean sameThread = Objects.equals(q.get("root"), m.get("id"));
      if (refs.isEmpty()
          ? lane || pickupLane || sameThread
          : refs.stream().allMatch(ref -> ref.equals(reference) || ref.equals(team)))
        matches.add(m);
    }
    // Repeat bookings on one lane ("MUC to PVG, 134 kg"): the stated weight picks the shipment.
    if (matches.size() > 1) {
      var weighed =
          matches.stream()
              .filter(m -> weighs(upper, ledger.read(m.get("fields"), Shipment.class)))
              .toList();
      if (!weighed.isEmpty()) matches = weighed;
    }
    if (matches.size() != 1) return null;
    var m = matches.getFirst();
    var s = ledger.read(m.get("fields"), Shipment.class);
    Map<?, ?> b = ledger.read(m.get("booking"), Map.class);
    String reference = Ledger.str(b, "booking_reference"), airline = Ledger.str(b, "airline");
    String status = Ledger.str(b, "status");
    for (var update :
        ledger
            .jdbc()
            .queryForList(
                "SELECT sender,body FROM mail WHERE kind='carrier' AND instr(body,?)>0 ORDER BY id"
                    + " DESC",
                reference)) {
      if (catalog
          .contact(Ledger.str(update, "sender"))
          .filter(c -> c.name().equalsIgnoreCase(airline))
          .isEmpty()) continue;
      var match = STATUS.matcher(Ledger.str(update, "body"));
      if (match.find()) {
        status = match.group(1).strip();
        break;
      }
    }
    String detail = "";
    if (Pattern.compile("(?i)\\b(?:price|cost|rate|amount|paid|preis)\\b").matcher(question).find())
      detail =
          " The airline booking price is "
              + Ledger.str(b, "currency")
              + " "
              + Ledger.str(b, "price")
              + ".";
    if (Pattern.compile("(?i)\\b(?:weight|kg|gewicht)\\b").matcher(question).find())
      detail +=
          " The gross weight is " + s.weightKg().stripTrailingZeros().toPlainString() + " kg.";
    // Written in English, then rendered and verified in the language of the question.
    String approved = Greeting.at(q, clock.instant())
        + "\n\nThe shipment from "
        + s.origin()
        + " to "
        + s.destination()
        + " is booked with "
        + airline
        + " under reference "
        + reference
        + " (our reference "
        + Ledger.str(b, "team_reference")
        + "). "
        + "The latest status we have is “"
        + status
        + "”."
        + detail
        + "\n\nBest regards,\nTeam AEIOU";
    return worker.inCustomerLanguage("reply:" + Ledger.id(q.get("id")), q, approved, List.of(airline));
  }

  private static boolean weighs(String question, Shipment s) {
    return s.weightKg() != null
        && WEIGHT
            .matcher(question)
            .results()
            .anyMatch(w -> new BigDecimal(w.group(1).replace(',', '.'))
                .compareTo(s.weightKg()) == 0);
  }
}
