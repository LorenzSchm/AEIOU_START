package ai.aeiou.dispatch;

import ai.aeiou.freight.Catalog;
import ai.aeiou.freight.MailParser;
import ai.aeiou.freight.Shipment;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class CustomerClarifications {
  static final String WAITING = "waiting for customer";
  static final String PICKUP_ISSUE = "Pickup requested. Confirm the pickup postcode or zone.";
  private static final Pattern SHIPMENT_REFERENCE = Pattern.compile(
      "(?iu)\\b(?:could you (?:please )?|please )quote (?:for )?the same shipment as in my (?:earlier )?request,?\\s+"
          + "[\"“]([^\"”\\r\\n]+)[\"”],\\s+sent on (\\d{4}-\\d{2}-\\d{2}) at (\\d{2}:\\d{2}) UTC"
          + " for (?:destination )?([a-z]{3})[.?]\\s+The cargo and pickup details are unchanged"
          + "(?:,? and you (?:already )?have them from (?:that|my previous|my earlier) request)?\\.");
  private static final Pattern REPEATED_SHIPMENT_REFERENCE = Pattern.compile(
      "(?iu)\\b(?:could you (?:please )?|please )quote the same shipment again[.?]\\s+My earlier request,?\\s+"
          + "[\"“]([^\"”\\r\\n]+)[\"”],\\s+was sent on (\\d{4}-\\d{2}-\\d{2}) at (\\d{2}:\\d{2}) UTC"
          + " and was for ([a-z]{3})[.?]\\s+The cargo and pickup details are unchanged"
          + "(?:,? and you (?:already )?have them from that request)?\\.");
  private static final Pattern UNCHANGED_CARGO_PICKUP = Pattern.compile(
      "(?iu)\\b(?:(?:cargo|shipment)\\s+and\\s+(?:the\\s+)?(?:pickup|collection)(?:\\s+details)?"
          + "\\s+(?:are|remain|stay)\\s+(?:unchanged|the same)"
          + "|(?:the same|unchanged)\\s+(?:cargo|shipment)\\s+and\\s+(?:the\\s+)?(?:pickup|collection)(?:\\s+details)?)\\b");
  private static final Pattern PRIOR_REQUEST = Pattern.compile("(?iu)\\b(?:earlier|prior|previous)\\s+(?:request|email|rfq)\\b");
  private static final Pattern UTC_MINUTE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}");
  private static final Pattern DELIVERY_APPROVAL = Pattern.compile(
      "(?iu)(?:yes[,!]\\h*)?we(?: will|['’]ll) deliver(?: the (?:freight|shipment|cargo))? to "
          + "([a-z]{3}) ourselves(?:,\\h*so\\h+(?:there(?:['’]s| is)\\h+)?no\\h+"
          + "(?:warehouse collection|pick[ -]?up postcode(?: or town)?)\\h+(?:is\\h+)?needed)?[.!]?"
          + "|(?:oui[,!]\\h*)?nous (?:la |le |les )?(?:d[ée]poserons|livrerons|apporterons)"
          + "(?: (?:le fret|la marchandise|l['’]envoi))? (?:nous-mêmes )?"
          + "[àa] (?:l['’]a[ée]roport (?:de )?[^().!?\\r\\n]*?\\(?([a-z]{3})\\)?|([a-z]{3}))"
          + "(?: nous-mêmes)?[.!]?"
          + "|(?:ja[,!]\\h*)?wir (?:liefern|bringen) (?:die (?:sendung|ware)|das frachtgut) "
          + "(?:selbst (?:nach|zum) ([a-z]{3})|(?:nach|zum) ([a-z]{3}) selbst)[.!]?"
          + "|(?:我们|我們)(?:会|會|将|將)?(?:自行|自己)(?:把货物|把貨物)?(?:送到|送至)"
          + "\\h*([a-z]{3})(?:\\h*机场|\\h*機場)?[。.!]?");
  private static final Pattern DELIVERY_QUALIFIER = Pattern.compile(
      "(?iu)\\b(?:if|unless|until|only|not|but|cancel|sauf|seulement|sinon|si|mais|"
          + "wenn|falls|sofern|aber|stornieren)\\b|如果|除非|但是|取消");
  private static final Pattern PICKUP_REQUEST = Pattern.compile(
      "(?imu)(?:^|(?<=[.!?])\\h+)\\h*(?:please (?:arrange|organize) (?:warehouse )?(?:pick[ -]?up|collection)|"
          + "we (?:still )?need (?:warehouse )?(?:pick[ -]?up|collection)|"
          + "bitte (?:organisieren sie (?:die |eine )?)?abholung|"
          + "merci d['’]organiser (?:l['’]enlèvement|la collecte))\\b|(?:^|[。！？])\\h*"
          + "(?:请|請)(?:您)?安排(?:上门提货|上門提貨)");

  record Question(String text, String missing) {
    Question(String text) {
      this(text, null);
    }
  }

  record Details(Map<String, Object> message, String postcode, String area, String zone,
      Long pickupReply, Long latestReply, String deliveryAirport, CustomerLanguage language) {
    String location(String zone) {
      return location(zone, CustomerLanguage.ENGLISH);
    }

    String location(String zone, CustomerLanguage language) {
      if (postcode != null && postcode.startsWith(zone))
        return language.text("postcode ", "code postal ", "PLZ ", "邮编 ") + postcode;
      return area != null && zone.equals(this.zone) ? area
          : language.text("pickup zone ", "zone d’enlèvement ", "Abholgebiet ", "提货区域 ") + zone;
    }
  }

  private record ReferencedShipment(String body, Shipment shipment) {
    String normalizeConfirmedWeight(String reply) {
      var weight = shipment.weightKg().stripTrailingZeros();
      if (weight.scale() > 0 || weight.compareTo(BigDecimal.valueOf(1000)) < 0) return reply;
      String grouped = NumberFormat.getIntegerInstance(Locale.US).format(weight);
      return reply.replaceAll("(?iu)(?<![\\d.,])" + Pattern.quote(grouped) + "(?=\\h+(?:kg|kgs|kilograms?)\\b)",
          weight.toPlainString());
    }
  }

  private final Ledger ledger;
  private final Catalog catalog;
  private final RfqGateway gateway;

  CustomerClarifications(Ledger ledger, Catalog catalog, RfqGateway gateway) {
    this.ledger = ledger;
    this.catalog = catalog;
    this.gateway = gateway;
  }

  Details details(long id, Map<String, Object> original,
      UnaryOperator<Map<String, Object>> readAttachment) {
    String raw = Ledger.str(original, "body");
    String subject = MailParser.currentSubject(Ledger.str(original, "subject"), raw);
    String body = MailParser.authoredText(raw);
    var referenced = referencedShipment(original, body);
    if (referenced != null) body = referenced.body();
    String postcode = MailParser.pickupPostcode(subject + "\n" + body, "").orElse(null);
    Long pickupReply = null, latest = null;
    String deliveryAirport = null;
    Map.Entry<String, String> area = null;
    var replies = ledger.jdbc().queryForList(
        """
        SELECT DISTINCT f.* FROM mail f JOIN deliveries d
          ON d.action IN ('pickup-postcode:'||?,'rfq-info:'||?,'airport-delivery:'||?,
                          'packing-copy:'||?,'packing-correction:'||?)
          OR d.action LIKE 'pickup-postcode:'||?||':%'
          OR d.action LIKE 'rfq-info:'||?||':%'
        WHERE f.kind='follow-up' AND f.sender=? AND f.id>?
          AND (f.root=json_extract(d.response,'$.id') OR (f.root=? AND
            (d.response IS NULL OR f.id>json_extract(d.response,'$.id'))))
        ORDER BY f.id
        """,
        id, id, id, id, id, id, id, original.get("sender"), id, id);
    var merged = new StringBuilder(body);
    for (var reply : replies) {
      latest = Ledger.id(reply.get("id"));
      String answer = MailParser.authoredText(Ledger.str(readAttachment.apply(reply), "body"));
      if (referenced != null) answer = referenced.normalizeConfirmedWeight(answer);
      String approvedAirport = deliveryApproval(answer);
      if (approvedAirport != null) {
        deliveryAirport = approvedAirport;
        postcode = null;
        area = null;
        pickupReply = null;
        merged.append("\n\n").append(answer);
        continue;
      }
      String candidate = MailParser.pickupPostcode(answer, "").orElse(null);
      if (candidate == null && answer.matches("[0-9]{5}[.!]?")) candidate = answer.substring(0, 5);
      if (candidate != null && !candidate.equals(postcode)) {
        deliveryAirport = null;
        postcode = candidate;
        pickupReply = latest;
        area = null;
      } else if (postcode == null) {
        var confirmed = pickupArea(answer);
        if (confirmed != null && !confirmed.equals(area)) {
          deliveryAirport = null;
          area = confirmed;
          pickupReply = latest;
        }
      }
      if (deliveryAirport != null && PICKUP_REQUEST.matcher(answer).find()) deliveryAirport = null;
      merged.append("\n\n").append(answer);
    }
    String zone = postcode == null ? (area == null ? null : area.getKey()) : postcode.substring(0, 2);
    if (pickupReply != null) merged.append("\n\nPickup zone ").append(zone).append('.');
    var message = new HashMap<>(original);
    message.put("subject", subject);
    message.put("body", merged.toString());
    return new Details(message, postcode, area == null ? null : area.getValue(), zone, pickupReply,
        latest, deliveryAirport, CustomerLanguage.of(original));
  }

  private ReferencedShipment referencedShipment(Map<String, Object> current, String body) {
    var references = Stream.concat(SHIPMENT_REFERENCE.matcher(body).results(),
        REPEATED_SHIPMENT_REFERENCE.matcher(body).results()).limit(2).toList();
    if (references.size() > 1 || !ledger.read(current.get("attachments"), List.class).isEmpty()) return null;
    Reference reference;
    if (references.isEmpty()) {
      reference = gatewayReference(current, body);
      if (reference == null) return null;
    } else {
      var exact = references.getFirst();
      reference = new Reference(exact.group(1), exact.group(2) + "T" + exact.group(3), exact.group(4),
          exact.start(), exact.end(), "");
    }
    var matches = ledger.jdbc().queryForList("""
        SELECT * FROM mail WHERE kind='request' AND root=id AND sender=? AND subject=?
          AND substr(received,1,16)=? AND received<?
        """, current.get("sender"), reference.subject(), reference.sentMinute(),
        current.get("received"));
    if (matches.size() != 1) return null;
    var previous = matches.getFirst();
    if (!ledger.read(previous.get("attachments"), List.class).isEmpty()) return null;
    String priorBody = MailParser.authoredText(Ledger.str(previous, "body"));
    var parsed = MailParser.request(Ledger.str(previous, "subject"), priorBody, false, catalog);
    var shipment = parsed.shipment();
    if (parsed.issue() != null || !reference.destination().equalsIgnoreCase(shipment.destination())) return null;
    try {
      QuoteChecks.preflight(previous);
      if (CargoInsurance.from(previous) != null) return null;
    } catch (WaveWorker.Conflict ex) {
      return null;
    }
    String pickup;
    if (shipment.pickupZone() != null) {
      var postcode = MailParser.pickupPostcode(priorBody, shipment.pickupZone());
      if (postcode.isEmpty()) return null;
      pickup = "Please arrange pickup from postcode " + postcode.get() + " to " + shipment.origin() + ".";
    } else {
      if (!MailParser.selfDelivery(priorBody)) return null;
      pickup = "We will deliver the cargo to " + shipment.origin() + " ourselves.";
    }
    return new ReferencedShipment(body.substring(0, reference.start()) + reference.remainingText()
        + "\nThe shipment has a total gross weight of "
        + shipment.weightKg().toPlainString() + " kg and a total volume of "
        + shipment.volumeCbm().toPlainString() + " cbm. " + pickup + body.substring(reference.end()), shipment);
  }

  private record Reference(String subject, String sentMinute, String destination, int start, int end,
      String remainingText) {}

  private Reference gatewayReference(Map<String, Object> current, String body) {
    if (!PRIOR_REQUEST.matcher(body).find() || !UNCHANGED_CARGO_PICKUP.matcher(body).find()) return null;
    var proposed = gateway.earlierShipmentReference(current, body);
    if (proposed == null || proposed.subject() == null || proposed.subject().isBlank() || proposed.sentMinute() == null
        || !UTC_MINUTE.matcher(proposed.sentMinute()).matches() || proposed.destination() == null
        || !catalog.destinations.contains(proposed.destination())) return null;
    String title = proposed.subject();
    var titlePattern = Pattern.compile("[\"“‘']" + Pattern.quote(title) + "[\"”’']");
    var titles = titlePattern.matcher(body).results().limit(2).toList();
    if (titles.size() != 1) return null;
    var match = titles.getFirst();
    int paragraph = body.lastIndexOf("\n\n", match.start());
    int start = paragraph < 0 ? 0 : paragraph + 2;
    int after = body.indexOf("\n\n", match.end());
    int end = after < 0 ? body.length() : after;
    String source = body.substring(start, end);
    String withoutTitle = titlePattern.matcher(source).replaceAll("earlier request");
    var unchanged = UNCHANGED_CARGO_PICKUP.matcher(withoutTitle);
    if (!unchanged.find()) return null;
    String date = proposed.sentMinute().substring(0, 10), minute = proposed.sentMinute().substring(11);
    var timestamp = Pattern.compile("(?i)" + Pattern.quote(date) + "[T\\s,]+(?:at\\s+)?"
        + Pattern.quote(minute) + "(?::[0-5]\\d)?\\s*(?:UTC|Z)\\b").matcher(withoutTitle);
    var destination = Pattern.compile("\\b" + Pattern.quote(proposed.destination()) + "\\b");
    if (!timestamp.find() || !destination.matcher(source).find()) return null;
    // Remove only the verified old title, timestamp, airport and unchanged-details phrase.
    String remaining = destination.matcher(timestamp.replaceAll("earlier that day")).replaceAll("");
    remaining = UNCHANGED_CARGO_PICKUP.matcher(remaining).replaceFirst("");
    return new Reference(title, proposed.sentMinute(), proposed.destination(), start, end, remaining);
  }

  private String deliveryApproval(String answer) {
    if (DELIVERY_QUALIFIER.matcher(answer).find() || PICKUP_REQUEST.matcher(answer).find()) return null;
    String approved = null;
    for (String sentence : answer.split("(?<=[.!?])\\h+|(?<=[。！？])|\\R+")) {
      var match = DELIVERY_APPROVAL.matcher(sentence.strip());
      if (!match.matches()) continue;
      for (int group = 1; group <= match.groupCount(); group++) {
        String code = match.group(group);
        if (code == null) continue;
        String airport = code.toUpperCase(Locale.ROOT);
        if (!catalog.zones.containsValue(airport) || (approved != null && !approved.equals(airport)))
          return null;
        approved = airport;
      }
    }
    return approved;
  }

  private Map.Entry<String, String> pickupArea(String answer) {
    var matches = catalog.pickupAreas.entrySet().stream().filter(area -> Pattern.compile(
        "(?iu)\\b(?:pickup(?: location| address)?\\h*(?:(?:is|will be)\\h*(?:in\\h+|at\\h+)?|"
            + "in\\h+|at\\h+|:\\h*)|warehouse in\\h+)"
            + Pattern.quote(area.getValue()) + "(?=\\h*(?:[,!.\\r\\n]|$|Germany\\b|Deutschland\\b))")
        .matcher(answer).find()).toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  boolean needsPostcode(Details details, Shipment shipment, String issue) {
    return details.deliveryAirport() == null && details.zone() == null
        && (shipment.pickupZone() != null || PICKUP_ISSUE.equals(issue)
            || "Confirm the pickup requirement and postcode.".equals(issue)
            || MailParser.requestsPickup(Ledger.str(details.message(), "subject"),
                Ledger.str(details.message(), "body")));
  }

  Question question(Shipment shipment, String issue, boolean needsPostcode, CustomerLanguage language) {
    if ("Confirm whether collection or customer delivery to the airport is required.".equals(issue))
      return new Question(language.text(
          "Could you confirm whether you need warehouse collection or will deliver the shipment"
              + " to the airport? If you need collection, please include the full five-digit postcode.",
          "Pouvez-vous confirmer si vous avez besoin d’un enlèvement à l’entrepôt ou si vous déposerez"
              + " l’envoi à l’aéroport ? Pour un enlèvement, merci d’indiquer le code postal à cinq chiffres.",
          "Bitte bestätigen Sie, ob wir die Sendung am Lager abholen sollen oder ob Sie sie selbst"
              + " zum Flughafen bringen. Für eine Abholung teilen Sie uns bitte die fünfstellige PLZ mit.",
          "请确认您需要我们上门提货，还是会自行把货物送到机场。如需上门提货，请提供五位数邮编。"));
    if (issue != null && (issue.contains("Special handling") || issue.startsWith("Review the attached"))) return null;
    var missing = new ArrayList<String>();
    if (needsPostcode) missing.add(language.text("the full five-digit pickup postcode",
        "le code postal d’enlèvement à cinq chiffres", "die fünfstellige Abhol-PLZ", "五位数提货邮编"));
    else if (shipment.pickupZone() != null && !catalog.zones.containsKey(shipment.pickupZone()))
      missing.add(language.text("the full five-digit postcode of the pickup address",
          "le code postal d’enlèvement à cinq chiffres", "die fünfstellige Abhol-PLZ", "五位数提货邮编"));
    else if (shipment.origin() == null && shipment.pickupZone() == null)
      missing.add(language.text("the departure airport", "l’aéroport de départ", "den Abflughafen", "出发机场"));
    if (shipment.destination() == null)
      missing.add(language.text("the destination airport", "l’aéroport d’arrivée", "den Zielflughafen", "目的地机场"));
    if (shipment.weightKg() == null || shipment.weightKg().signum() <= 0)
      missing.add(language.text("the total gross weight in kg", "le poids brut total en kg",
          "das gesamte Bruttogewicht in kg", "总毛重（公斤）"));
    if (shipment.volumeCbm() == null || shipment.volumeCbm().signum() < 0)
      missing.add(language.text("the total volume in cubic metres", "le volume total en mètres cubes",
          "das Gesamtvolumen in Kubikmetern", "总体积（立方米）"));
    if (shipment.preference() == null)
      missing.add(language.text("whether you prefer the lowest price or fastest service",
          "si vous préférez le tarif le plus bas ou le service le plus rapide",
          "ob Sie den günstigsten Preis oder die schnellste Verbindung bevorzugen", "您优先考虑价格还是时效"));
    if (!missing.isEmpty()) {
      String details = String.join(language.text(", ", ", ", ", ", "、"), missing);
      return new Question(language.text("Could you please confirm %s?", "Pourriez-vous confirmer %s ?",
          "Könnten Sie bitte Folgendes bestätigen: %s?", "请确认以下信息：%s。")
          .formatted(details), details);
    }
    if (issue == null) return null;
    if (issue.equals(MailParser.ARRIVAL_DATES_ISSUE)) return new Question(language.text(
        "Could you confirm the cargo ready date and the latest arrival date at the destination?",
        "Pouvez-vous confirmer la date de disponibilité du fret et la date limite d’arrivée à destination ?",
        "Könnten Sie das Bereitstellungsdatum der Ware und das späteste Ankunftsdatum bestätigen?",
        "请确认货物备妥日期和最迟到达日期。"));
    if (issue.equals("Confirm the pickup zone and matching airport.")) return new Question(language.text(
        "Could you confirm the five-digit pickup postcode, town and departure airport?",
        "Pouvez-vous confirmer le code postal à cinq chiffres, la ville d’enlèvement et l’aéroport de départ ?",
        "Bitte bestätigen Sie die fünfstellige Abhol-PLZ, den Ort und den Abflughafen.",
        "请确认提货城市、五位数邮编和出发机场。"));
    if (issue.equals("Confirm origin and destination airports."))
      return new Question(language.text("Could you confirm the departure and destination airports?",
          "Pouvez-vous confirmer les aéroports de départ et d’arrivée ?",
          "Bitte bestätigen Sie den Abflug- und den Zielflughafen.", "请确认出发机场和目的地机场。"));
    if (issue.contains("lot")) return new Question(language.text(
        "Could you confirm the gross weight and volume for each lot and the total for the shipment?",
        "Pouvez-vous confirmer le poids brut et le volume de chaque lot ainsi que les totaux de l’envoi ?",
        "Bitte bestätigen Sie Bruttogewicht und Volumen je Teillieferung sowie für die gesamte Sendung.",
        "请确认各批货物的毛重和体积，以及整票货物的总毛重和总体积。"));
    if (issue.contains("per-piece")) return new Question(language.text(
        "Could you confirm the total gross weight for the shipment?",
        "Pouvez-vous confirmer le poids brut total de l’envoi ?",
        "Bitte bestätigen Sie das gesamte Bruttogewicht der Sendung.", "请确认整票货物的总毛重。"));
    return null;
  }
}
