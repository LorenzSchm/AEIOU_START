package ai.aeiou.dispatch;

import ai.aeiou.freight.Catalog;
import ai.aeiou.freight.MailParser;
import ai.aeiou.freight.Shipment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

final class CustomerClarifications {
  static final String WAITING = "waiting for customer";
  static final String PICKUP_ISSUE = "Pickup requested. Confirm the pickup postcode or zone.";
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

  private final Ledger ledger;
  private final Catalog catalog;

  CustomerClarifications(Ledger ledger, Catalog catalog) {
    this.ledger = ledger;
    this.catalog = catalog;
  }

  Details details(long id, Map<String, Object> original,
      UnaryOperator<Map<String, Object>> readAttachment) {
    String raw = Ledger.str(original, "body");
    String subject = MailParser.currentSubject(Ledger.str(original, "subject"), raw);
    String body = MailParser.authoredText(raw);
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
