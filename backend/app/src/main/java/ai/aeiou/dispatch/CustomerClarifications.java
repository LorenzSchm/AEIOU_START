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
          + "([a-z]{3}) ourselves(?:,\\h*so\\h+(?:there['’]s\\h+)?no\\h+"
          + "(?:warehouse collection|pick[ -]?up postcode(?: or town)?)\\h+(?:is\\h+)?needed)?[.!]?"
          + "|(?:oui[,!]\\h*)?nous (?:la |le |les )?(?:d[ée]poserons|livrerons|apporterons)"
          + "(?: (?:le fret|la marchandise|l['’]envoi))? (?:nous-mêmes )?"
          + "[àa] (?:l['’]a[ée]roport (?:de )?[^().!?\\r\\n]*?\\(?([a-z]{3})\\)?|([a-z]{3}))"
          + "(?: nous-mêmes)?[.!]?"
          + "|(?:ja[,!]\\h*)?wir (?:liefern|bringen) (?:die (?:sendung|ware)|das frachtgut) "
          + "(?:selbst (?:nach|zum) ([a-z]{3})|(?:nach|zum) ([a-z]{3}) selbst)[.!]?");
  private static final Pattern DELIVERY_QUALIFIER = Pattern.compile(
      "(?iu)\\b(?:if|unless|until|only|not|but|cancel|sauf|seulement|sinon|si|mais|"
          + "wenn|falls|sofern|aber|stornieren)\\b");
  private static final Pattern PICKUP_REQUEST = Pattern.compile(
      "(?iu)^(?:please (?:arrange|organize) (?:warehouse )?(?:pick[ -]?up|collection)|"
          + "we (?:still )?need (?:warehouse )?(?:pick[ -]?up|collection)|"
          + "bitte (?:organisieren sie (?:die |eine )?)?abholung|"
          + "merci d['’]organiser (?:l['’]enlèvement|la collecte))\\b");

  record Details(Map<String, Object> message, String postcode, String area, String zone,
      Long pickupReply, Long latestReply, String deliveryAirport) {
    String location(String zone) {
      if (postcode != null && postcode.startsWith(zone)) return "postcode " + postcode;
      return area != null && zone.equals(this.zone) ? area : "pickup zone " + zone;
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
        latest, deliveryAirport);
  }

  private String deliveryApproval(String answer) {
    if (DELIVERY_QUALIFIER.matcher(answer).find()) return null;
    String approved = null;
    for (String sentence : answer.split("(?<=[.!?])\\h+|\\R+")) {
      sentence = sentence.strip();
      if (PICKUP_REQUEST.matcher(sentence).find()) return null;
      var match = DELIVERY_APPROVAL.matcher(sentence);
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

  String question(Shipment shipment, String issue, boolean needsPostcode) {
    if ("Confirm whether collection or customer delivery to the airport is required.".equals(issue))
      return "Could you confirm whether you need warehouse collection or will deliver the shipment"
          + " to the airport? If you need collection, please include the full five-digit postcode.";
    if (issue != null && (issue.contains("Special handling") || issue.startsWith("Review the attached"))) return null;
    var missing = new ArrayList<String>();
    if (needsPostcode) missing.add("the full five-digit pickup postcode and town");
    else if (shipment.pickupZone() != null && !catalog.zones.containsKey(shipment.pickupZone()))
      missing.add("the full five-digit postcode and town of the pickup address");
    else if (shipment.origin() == null && shipment.pickupZone() == null)
      missing.add("the departure airport");
    if (shipment.destination() == null) missing.add("the destination airport");
    if (shipment.weightKg() == null || shipment.weightKg().signum() <= 0)
      missing.add("the total gross weight in kg");
    if (shipment.volumeCbm() == null || shipment.volumeCbm().signum() < 0)
      missing.add("the total volume in cubic metres");
    if (shipment.preference() == null) missing.add("whether you prefer the lowest price or fastest service");
    if (!missing.isEmpty()) return "Could you please confirm " + String.join(", ", missing) + "?";
    if (issue == null) return null;
    if (issue.contains("pickup")) return "Could you confirm the five-digit pickup postcode, town and departure airport?";
    if (issue.equals("Confirm origin and destination airports."))
      return "Could you confirm the departure and destination airports?";
    if (issue.contains("lot")) return "Could you confirm the gross weight and volume for each lot and the total for the shipment?";
    if (issue.contains("per-piece")) return "Could you confirm the total gross weight for the shipment?";
    return "Could you confirm the departure and destination airports, total gross weight in kg, total"
        + " volume in cubic metres, and whether you prefer the lowest price or fastest service?";
  }
}
