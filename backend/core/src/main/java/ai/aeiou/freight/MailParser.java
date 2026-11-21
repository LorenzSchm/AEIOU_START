package ai.aeiou.freight;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

public final class MailParser {
  private static final String NUM = "(?<![\\w.,-])(\\d+(?:[.,]\\d+)*)(?![.,]\\d)";

  private static final Pattern STREET_POSTCODE =
      Pattern.compile(
          "(?iu:\\b(?:PLZ|post\\s*code|postal code|zip(?: code)?)\\s*(?:is\\s+|ist\\s+|[:#-]\\s*)?)([0-9]{5})\\b"
              + "|(?<![\\p{L}\\d.,])(?:D-)?([0-9]{5})\\h+\\(?(?=\\p{Lu}\\p{Ll})");

  public static Optional<String> pickupPostcode(String text, String zone) {
    var candidates =
        STREET_POSTCODE
            .matcher(text)
            .results()
            .map(m -> m.group(1) != null ? m.group(1) : m.group(2))
            .filter(code -> code.startsWith(zone))
            .distinct()
            .toList();
    return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
  }

  public static String authoredText(String body) {
    return body.split(
        "(?im)^\\h*(?:>|On .+ wrote:|Am .+ schrieb|[- ]{2,}(?:Original|Forwarded) Message[- ]{2,})",
        2)[0].strip();
  }

  public static String currentSubject(String subject, String body) {
    if (has(subject, "^\\s*(?:re:\\s*)*(?:fw|fwd|wg):")
        && !authoredText(body).equals(body.strip())) return "Air freight RFQ";
    return subject;
  }

  private static boolean selfDelivery(String body) {
    return has(body, "\\bwe(?: will|'ll|’ll) deliver[^.!?\\n]{0,100}\\bourselves\\b");
  }

  public static boolean requestsPickup(String subject, String body) {
    String text = subject + "\n" + body;
    if (selfDelivery(body)) text = text.replaceAll("(?iu)\\bready for pick[ -]?up\\b", "");
    return has(text, "pick[ -]?up|collect(?:ion)?\\b|truck(?:ing| pickup)? from|arrange (?:the |a )?truck|door["
        + " -]to|abhol|vorlauf|ex[ -]?works|\\bEXW\\b");
  }

  private static Pattern pattern(String s) {
    return Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  private static boolean has(String s, String regex) {
    return pattern(regex).matcher(s).find();
  }

  private static List<String> captures(String text, String regex) {
    return pattern(regex).matcher(text).results().map(m -> m.group(1)).toList();
  }

  private static BigDecimal number(String raw) {
    if (raw == null) return null;
    if (raw.matches("[1-9]\\d*[.,]\\d{3}")) return null;
    if (raw.matches("\\d{1,3}(,\\d{3})+\\.\\d{1,2}")) raw = raw.replace(",", "");
    if (raw.matches("\\d{1,3}(\\.\\d{3})+,\\d{1,2}")) raw = raw.replace(".", "");
    try {
      return new BigDecimal(raw.replace(',', '.'));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String only(Collection<String> values) {
    var unique = new HashSet<>(values);
    return unique.size() == 1 ? unique.iterator().next() : null;
  }

  private static BigDecimal quantity(String text, String unit) {
    var values = captures(text, NUM + "\\s*(?:" + unit + ")\\b");
    return values.size() == 1 ? number(values.getFirst()) : null;
  }

  private static BigDecimal dimensions(String text) {
    var dims =
        pattern(
                "(\\d+(?:[.,]\\d+)?)\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?)\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?)\\s*(cm|mm|m)\\b")
            .matcher(text)
            .results()
            .toList();
    var counts =
        captures(
            text,
            "\\b(\\d+)\\s*(?:pallets?|pieces?|pcs|crates?|cartons?|boxes|box|colli|paletten|kisten)\\b");
    if (dims.size() != 1 || counts.size() != 1) return null;
    var match = dims.getFirst();
    int scale =
        switch (match.group(4).toLowerCase(Locale.ROOT)) {
          case "mm" -> 9;
          case "cm" -> 6;
          default -> 0;
        };
    var total = new BigDecimal(counts.getFirst());
    for (int i = 1; i <= 3; i++) {
      var n = number(match.group(i));
      if (n == null) return null;
      total = total.multiply(n);
    }
    return total.movePointLeft(scale);
  }

  private static final Map<String, String> CITIES =
      Map.ofEntries(
          Map.entry("Frankfurt", "FRA"),
          Map.entry("Munich|München", "MUC"),
          Map.entry("Stuttgart", "STR"),
          Map.entry("Hamburg", "HAM"),
          Map.entry("Düsseldorf|Duesseldorf", "DUS"),
          Map.entry("Cologne|Köln", "CGN"),
          Map.entry("Los Angeles", "LAX"),
          Map.entry("Chicago", "ORD"),
          Map.entry("Miami", "MIA"),
          Map.entry("Toronto", "YYZ"),
          Map.entry("Dallas", "DFW"),
          Map.entry("Hong Kong", "HKG"),
          Map.entry("Singapore", "SIN"),
          Map.entry("Nairobi", "NBO"),
          Map.entry("Johannesburg", "JNB"),
          Map.entry("Lima", "LIM"),
          Map.entry("Bangkok", "BKK"),
          Map.entry("Dubai", "DXB"),
          Map.entry("Sydney", "SYD"),
          Map.entry("Melbourne", "MEL"),
          Map.entry("Mexico City", "MEX"));

  private static List<String> cityCodes(String text, Collection<String> valid) {
    return CITIES.entrySet().stream()
        .filter(e -> valid.contains(e.getValue()) && has(text, "\\b(?:" + e.getKey() + ")\\b"))
        .map(Map.Entry::getValue)
        .distinct()
        .toList();
  }

  private record LotTotals(BigDecimal kg, BigDecimal cbm) {}

  // Weight and volume have independent scopes: "together" is already a line total.
  private static final String LOT_NUMBER = "(\\d+(?:[.,]\\d+)?)";
  private static final Pattern PACKED_LOT =
      pattern(
          "Lot \\d+:\\h*([1-9]\\d*)\\h+(?:drums?|cartons?|cases?|pieces?|pallets?|crates?|boxes)\\h*,\\h*"
              + "(each|together)\\h+"
              + LOT_NUMBER
              + "\\h*kg\\h+gross(?:\\h*\\(\\d+(?:[.,]\\d+)?\\h*kg\\h+net\\))?\\h*,\\h*"
              + "(each|together)\\h+"
              + LOT_NUMBER
              + "(?:\\h*(cbm|m3|m³)|\\h*[x×]\\h*"
              + LOT_NUMBER
              + "\\h*[x×]\\h*"
              + LOT_NUMBER
              + "\\h*(cm|mm|m))\\h*[.]?");

  private static LotTotals lotTotals(String line) {
    var packed = PACKED_LOT.matcher(line.strip());
    if (packed.matches()) {
      var count = new BigDecimal(packed.group(1));
      var kg = number(packed.group(3));
      var cbm = number(packed.group(5));
      if (kg == null || cbm == null) return null;
      if (packed.group(6) == null) {
        var width = number(packed.group(7));
        var height = number(packed.group(8));
        if (width == null || height == null) return null;
        int scale =
            switch (packed.group(9).toLowerCase(Locale.ROOT)) {
              case "cm" -> 6;
              case "mm" -> 9;
              default -> 0;
            };
        cbm = cbm.multiply(width).multiply(height).movePointLeft(scale);
      }
      return new LotTotals(
          packed.group(2).equalsIgnoreCase("each") ? kg.multiply(count) : kg,
          packed.group(4).equalsIgnoreCase("each") ? cbm.multiply(count) : cbm);
    }
    if (has(line, "\\b(?:each|per|net)\\b")) return null;
    var kg = quantity(line, "kg|kgs|kilograms?");
    var cbm = quantity(line, "cbm|m³|m3|cubic met(?:er|re)s?");
    return kg == null || cbm == null ? null : new LotTotals(kg, cbm);
  }

  public record Parsed(Shipment shipment, String issue) {}

  public static Parsed request(String subject, String body, boolean attachments, Catalog catalog) {
    return request(subject, body, attachments, catalog, null);
  }

  public static Parsed request(
      String subject, String body, boolean attachments, Catalog catalog, String confirmedPickupZone) {
    return request(subject, body, attachments, catalog, confirmedPickupZone, null);
  }

  public static Parsed request(String subject, String body, boolean attachments, Catalog catalog,
      String confirmedPickupZone, String customerDeliveryAirport) {
    subject = currentSubject(subject, body);
    body = authoredText(body);
    String text = subject + "\n" + body;
    var codes = captures(text.toUpperCase(Locale.ROOT), "\\b([A-Z]{3})\\b");
    var origins = codes.stream().filter(catalog.zones::containsValue).distinct().toList();
    var destinations = codes.stream().filter(catalog.destinations::contains).distinct().toList();
    if (origins.isEmpty()) origins = cityCodes(text, catalog.zones.values());
    if (destinations.isEmpty()) destinations = cityCodes(text, catalog.destinations);
    boolean selfDelivery = selfDelivery(body);
    boolean pickup = requestsPickup(subject, body);
    var zones =
        new ArrayList<>(
            captures(
                text,
                "\\b(?:zone|PLZ|postal code|post ?code|zip(?:"
                    + " code)?)\\s*[:#-]?\\s*(\\d{2})(?:\\d{3})?\\b"));
    if (pickup) zones.addAll(captures(text, "\\b(\\d{2})\\d{3}\\b"));
    String zone = confirmedPickupZone == null ? only(zones) : confirmedPickupZone;
    String origin = origins.isEmpty() ? catalog.zones.get(zone) : only(origins);
    if (customerDeliveryAirport != null) {
      origin = customerDeliveryAirport;
      zone = null;
    }
    boolean fast = has(text, "fastest|quickest|shortest|schnellst|prioriti[sz]e speed");
    boolean cheap =
        has(
            text,
            "cheapest|lowest|economical|günstig|preiswert|best price|most economical|prioriti[sz]e"
                + " (?:price|cost)");
    var volume = quantity(body, "cbm|m³|m3|cubic met(?:er|re)s?");
    if (volume == null && !has(body, "\\b(?:cbm|m³|m3)\\b")) volume = dimensions(body);
    var weight = quantity(body, "kg|kgs|kilograms?");
    var lotLines = pattern("(?m)^\\h*Lot \\d+:[^\\r\\n]*").matcher(body);
    var lots = lotLines.results().map(m -> m.group()).toList();
    boolean incompleteLots = false;
    if (lots.size() >= 2) {
      weight = null;
      volume = null;
      incompleteLots = true;
      var outside = lotLines.reset().replaceAll("");
      if (has(body, "together as (?:one|a single) consignment|under (?:one|a single) air waybill")
          && !has(outside, "\\b(?:kg|kgs|kilograms?|cbm|m3|m³)")) {
        BigDecimal kg = BigDecimal.ZERO, cbm = BigDecimal.ZERO;
        for (String line : lots) {
          var lot = lotTotals(line);
          if (lot == null || lot.kg().signum() <= 0 || lot.cbm().signum() <= 0) {
            kg = null;
            break;
          }
          kg = kg.add(lot.kg());
          cbm = cbm.add(lot.cbm());
        }
        if (kg != null) {
          weight = kg;
          volume = cbm;
          incompleteLots = false;
        }
      }
    }
    var shipment =
        new Shipment(
            origin,
            only(destinations),
            weight,
            volume,
            fast == cheap ? null : fast ? "fastest" : "cheapest",
            zone,
            null,
            0);
    String issue = catalog.problem(shipment);
    if (attachments) issue = "Review the attached shipment specifications.";
    else if (has(
        text,
        "dangerous|hazardous|lithium|temperature.control|dry ice|gefahrgut|perishable|refrigerat"))
      issue = "Special handling needs a confirmed price.";
    else if (customerDeliveryAirport == null && selfDelivery && (pickup || zone != null))
      issue = "Confirm whether collection or customer delivery to the airport is required.";
    else if (customerDeliveryAirport == null && (pickup || !zones.isEmpty()) && zone == null)
      issue = "Pickup requested. Confirm the pickup postcode or zone.";
    else if (incompleteLots) issue = "Confirm gross weight and volume for every lot.";
    else if (lots.size() < 2
        && has(
            body,
            "(?:\\d+\\s*[x×]\\s*\\d+(?:[.,]\\d+)?\\s*kg)|(?:kg|kgs)\\s*(?:each|per"
                + " (?:piece|pallet|crate))|(?:each|per piece)\\s*[:=]?\\s*\\d+[.,\\d]*\\s*kg"))
      issue = "Confirm total gross weight. The request gives a per-piece weight.";
    else if (!has(
        text,
        "\\b(?:rfq|quot(?:e|ation)|rat(?:e|es)|pric(?:e|ing)|angebot|anfrage|freight|airfreight|versand)\\b"))
      issue = "Confirm that this message requests a freight quote.";
    return new Parsed(shipment, issue);
  }

  public record Bid(BigDecimal euros, Double days) {}

  private static final Pattern EXCLUDES_COLLECTION_WAIT =
      pattern("(?:\\h*[,;]\\h*)?\\bexcluding\\h+(?:(?:the|any)\\h+)?wait(?:ing\\h+time)?"
          + "\\h+(?:before|until)\\h+(?:pickup|collection)\\b");

  public static boolean confirmsPickupArea(String body, String area) {
    String text = EXCLUDES_COLLECTION_WAIT.matcher(authoredText(body)).replaceAll("");
    if (!has(text, "\\ball[- ]in\\b")) return false;
    if (has(text, "\\b(?:except|excluding|only|subject to|depending on|conditional|"
        + "(?:postcode|address) surcharge (?:applies|may apply))\\b")) return false;
    String town = Pattern.quote(area);
    return has(text, "(?:^|[.!?]\\h+|\\R)\\h*(?:(?:this|the|our)\\h+"
        + "(?:(?:all-in|fixed|firm)\\h+)?(?:rate|price|quote)(?:\\h+and\\h+transit(?:\\h+time)?)?\\h+)?"
        + "(?:(?:is\\h+)?valid\\h+(?:for\\h+all\\h+of|throughout)|"
        + "(?:apply|applies|covers)\\h+(?:to\\h+)?"
        + "(?:all\\h+addresses\\h+in|all\\h+of|anywhere\\h+in|throughout))\\h+" + town
        + "(?:,\\h*(?:Germany|Deutschland))?\\h*(?:[.!]|$|\\R)");
  }

  private static final String FROM_PICKUP_TO_DELIVERY =
      "(?:from (?:actual )?(?:pickup|collection) to (?:airport )?delivery|"
          + "(?:pickup|collection)[ -]to[ -](?:airport[ -])?delivery(?: time)?)";
  private static final String DURATION =
      "(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*(hours?|hrs?|h|minutes?|mins?|days?|tage?)\\b";
  private static final Pattern TRUCK_TRANSIT =
      pattern("\\b(?:(?:(?:road|truck(?:ing)?) )?transit(?: time)?(?: "
          + FROM_PICKUP_TO_DELIVERY + ")?|" + FROM_PICKUP_TO_DELIVERY + ")"
          + "\\s*(?:is\\s+|of\\s+|takes\\s+|[:=]\\s*)?" + DURATION);
  private static final Pattern TRUCK_TRANSIT_AFTER_DURATION =
      pattern(DURATION + "\\s+" + FROM_PICKUP_TO_DELIVERY);

  private static Double truckTransit(String text) {
    var durations = new HashSet<Double>();
    for (var expression : List.of(TRUCK_TRANSIT, TRUCK_TRANSIT_AFTER_DURATION)) {
      for (var match : expression.matcher(text).results().toList()) {
        double duration = Double.parseDouble(match.group(1).replace(',', '.'));
        String unit = match.group(2).toLowerCase(Locale.ROOT);
        if (unit.startsWith("h")) duration /= 24;
        else if (unit.startsWith("min")) duration /= 24 * 60;
        durations.add(duration);
      }
    }
    if (durations.size() != 1) return null;
    double days = durations.iterator().next();
    return days <= 90 ? days : null;
  }

  /**
   * Never promote a per-kg, provisional, mixed-currency or multi-option number to an all-in price.
   */
  public static Optional<Bid> bid(String body, boolean truck) {
    String text = authoredText(body);
    String priceTerms = truck ? text.replaceAll(
        "(?iu)\\b(?:cannot|can[’']t|unable to) confirm[^.!?\\r\\n]*(?:\\b(?:time|transit|hours?)\\b|"
            + "\\bestimate " + FROM_PICKUP_TO_DELIVERY + ")",
        "") : text;
    if (truck) priceTerms = EXCLUDES_COLLECTION_WAIT.matcher(priceTerms).replaceAll("");
    if (has(
        priceTerms,
        "\\b(?:USD|GBP|CHF|plus|excluding|excluded|estimated (?:price|cost|rate|total)|"
            + "estimate(?:d)? (?:of )?(?:EUR|price|rate|cost)|(?:price|rate|cost) (?:is )?(?:an )?estimate|"
            + (!truck ? "estimate|" : "")
            + "indicative|unavailable|unable|cannot|can[’']t|decline|separately|additional"
            + " charges|starting at|extras"
            + " apply)\\b|\\+\\s*(?:VAT|fuel|security)|(?:per|/)\\s*(?:kg|km|pallet)|\\bsubject"
            + " to\\b")) return Optional.empty();
    var amounts = new ArrayList<>(captures(text, "(?:\\bEUR\\s*|€\\s*)" + NUM));
    amounts.addAll(captures(text, NUM + "\\s*(?:EUR\\b|€)"));
    var price = number(only(amounts));
    if (price == null || price.signum() <= 0) return Optional.empty();
    // A range, or separate options with different transit times, requires review.
    if (has(text,
        "\\d+\\s*(?:-|–|to)\\s*\\d+\\s*(?:business |working )?(?:days?|tage?|hours?|hrs?|h)"))
      return truck ? Optional.of(new Bid(price, null)) : Optional.empty();
    String timing =
        pattern(
                "(?:valid(?:ity)?|gültig)\\s*(?:for|of|:)?\\s*\\d+\\s*(?:days?|tage?|hours?|hrs?|h)\\b")
            .matcher(priceTerms)
            .replaceAll("");
    if (truck) return Optional.of(new Bid(price, truckTransit(timing)));
    var transit =
        captures(
            timing, "\\b(\\d{1,2})\\s*(?:business |working |calendar )?(?:days?|tag(?:e|en)?)\\b");
    if (transit.isEmpty()) transit = captures(timing, "\\bD\\+(\\d{1,2})\\b");
    if (transit.isEmpty() && has(timing, "same[ -]day|am selben tag")) transit = List.of("0");
    var hours =
        captures(
            timing,
            "\\b(?:pickup|collection|delivery|transit|lead)(?:\\s+time)?\\s*(?:is|of|in|:|=)?\\s*(\\d{1,4})\\s*(?:hours?|hrs?|h)\\b");
    if (!hours.isEmpty()) {
      if (hours.size() != 1 || !transit.isEmpty()) return Optional.empty();
      int duration = Integer.parseInt(hours.getFirst());
      return duration <= 90 * 24 ? Optional.of(new Bid(price, duration / 24.0)) : Optional.empty();
    }
    if (transit.size() != 1) return Optional.empty();
    int days = Integer.parseInt(transit.getFirst());
    return days <= 90 ? Optional.of(new Bid(price, (double) days)) : Optional.empty();
  }
}
