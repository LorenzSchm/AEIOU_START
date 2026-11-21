package ai.aeiou.forwarder.domain;

import static ai.aeiou.forwarder.domain.Json.*;

import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.util.*;
import java.util.regex.*;

public class ShipmentFacts {
  private static final String N = "(\\d+(?:[.,]\\d{3})*(?:[.,]\\d+)?)";

  private static BigDecimal number(String s) {
    if (s.contains(",") && s.contains("."))
      s =
          s.lastIndexOf(',') > s.lastIndexOf('.')
              ? s.replace(".", "").replace(',', '.')
              : s.replace(",", "");
    else s = s.replace(',', '.');
    return new BigDecimal(s);
  }

  public static String fresh(String text) {
    return text.split("(?im)^\\s*(?:>|On .+ wrote:|Am .+ schrieb|-----Original Message-----)", 2)[0]
        .strip();
  }

  private static List<BigDecimal> measures(String text, String units) {
    var m = Pattern.compile("(?i)" + N + "\\s*(" + units + ")\\b").matcher(text);
    var values = new ArrayList<BigDecimal>();
    while (m.find()) {
      var x = number(m.group(1));
      if (m.group(2).toLowerCase(Locale.ROOT).startsWith("lb"))
        x = x.multiply(new BigDecimal("0.45359237"));
      values.add(x.stripTrailingZeros());
    }
    return values;
  }

  public static ObjectNode extract(String body) {
    String text = fresh(body), lower = text.toLowerCase(Locale.ROOT);
    var result = obj();
    var route =
        Pattern.compile("\\b([A-Z]{3})\\s*(?:→|[-–>]+|to|nach|bis)\\s*([A-Z]{3})\\b").matcher(text);
    if (route.find()) result.put("origin", route.group(1)).put("destination", route.group(2));
    else {
      var destination = Pattern.compile("\\b(?:to|nach)\\s+([A-Z]{3})\\b").matcher(text);
      if (destination.find()) result.put("destination", destination.group(1));
    }
    var weights = measures(text, "kg|lbs?|kilograms?");
    var volumes = measures(text, "cbm|m³|m3");
    if (weights.size() == 1) result.put("grossKg", weights.getFirst().toPlainString());
    if (weights.size() > 1) {
      var totals =
          Pattern.compile("(?i)" + N + "\\s*(kg|lbs?)\\s*(?:gross\\s+)?(?:total|gesamt)\\b")
              .matcher(text);
      if (totals.find())
        result.put("grossKg", measures(totals.group(), "kg|lbs?").getFirst().toPlainString());
      else
        result.set(
            "grossAlternatives",
            M.valueToTree(weights.stream().map(BigDecimal::toPlainString).distinct().toList()));
    }
    if (volumes.size() == 1) result.put("volumeCbm", volumes.getFirst().toPlainString());
    var dimensions =
        Pattern.compile(
                "(?i)(?:(\\d+)\\s*(?:pieces?|pcs|colli|stück|packages?)\\s*[,;:]?\\s*(?:each|je|à)?\\s*)?"
                    + N
                    + "\\s*[x×]\\s*"
                    + N
                    + "\\s*[x×]\\s*"
                    + N
                    + "\\s*(cm|inches|in|m)\\b")
            .matcher(text);
    BigDecimal cube = BigDecimal.ZERO;
    int pieces = 0;
    var groups = arr();
    while (dimensions.find()) {
      int count = dimensions.group(1) == null ? 1 : Integer.parseInt(dimensions.group(1));
      var unit =
          switch (dimensions.group(5).toLowerCase(Locale.ROOT)) {
            case "cm" -> new BigDecimal(".01");
            case "m" -> BigDecimal.ONE;
            default -> new BigDecimal(".0254");
          };
      cube =
          cube.add(
              number(dimensions.group(2))
                  .multiply(number(dimensions.group(3)))
                  .multiply(number(dimensions.group(4)))
                  .multiply(unit.pow(3))
                  .multiply(BigDecimal.valueOf(count)));
      groups.add(
          obj(
              "count",
              count,
              "lengthM",
              number(dimensions.group(2)).multiply(unit).toPlainString(),
              "widthM",
              number(dimensions.group(3)).multiply(unit).toPlainString(),
              "heightM",
              number(dimensions.group(4)).multiply(unit).toPlainString(),
              "phrase",
              dimensions.group()));
      pieces += count;
    }
    if (pieces > 0 && volumes.isEmpty())
      result.put("volumeCbm", cube.stripTrailingZeros().toPlainString());
    if (pieces > 0) {
      result.put("pieces", pieces);
      result.set("packages", groups);
    }
    if (pieces > 0 && volumes.size() == 1 && cube.compareTo(volumes.getFirst()) != 0) {
      result.remove("volumeCbm");
      result.set(
          "volumeAlternatives", arr(volumes.getFirst().toPlainString(), cube.toPlainString()));
    }
    if (pieces > 0
        && weights.size() == 1
        && lower.matches("(?s).*(?:kg|lb)\\s*(?:per piece|each|je stück).*"))
      result.put(
          "grossKg", weights.getFirst().multiply(BigDecimal.valueOf(pieces)).toPlainString());
    if (!result.has("pieces")) {
      var count =
          Pattern.compile("(?i)\\b(\\d{1,6})\\s+(?:pieces?|pcs|packages?|stück)\\b").matcher(text);
      if (count.find()) result.put("pieces", Integer.parseInt(count.group(1)));
    }
    if (lower.matches("(?s).*(cheapest|lowest (?:price|cost)|günstigst|preiswert|billigst).*"))
      result.put("objective", "CHEAPEST");
    if (lower.matches("(?s).*(fastest|schnellst).*")) result.put("objective", "FASTEST");
    if (lower.matches("(?s).*(airport[- ]to[- ]airport|flughafen zu flughafen|airport only).*"))
      result.put("scope", "AIRPORT_TO_AIRPORT");
    if (lower.matches(
        "(?s).*(door[- ]to[- ]port|pick[- ]?up|abholung|collection from|arrange (?:the"
            + " )?(?:truck|collection)).*")) result.put("scope", "DOOR_TO_PORT");
    if (lower.matches(
        "(?s).*\\b(?:we (?:will |can )?(?:deliver|bring)"
            + " [^.]{0,70}ourselves|self.delivery|selbstanlieferung)\\b.*"))
      result.put("scope", "AIRPORT_TO_AIRPORT");
    var ready =
        Pattern.compile(
                "(?i)\\b(?:ready|abholbereit)\\b[^.!?\\n]{0,140}?\\b(\\d{4}-\\d{2}-\\d{2})\\b")
            .matcher(text);
    if (ready.find()) {
      try {
        result.put("readyDate", java.time.LocalDate.parse(ready.group(1)).toString());
      } catch (java.time.DateTimeException ignored) {
      }
    }
    if (lower.matches("(?s).*(?:not|nicht)\\s+(?:the |die )?(?:fastest|schnellste).*")
        && lower.matches("(?s).*(cheapest|günstigst).*")) result.put("objective", "CHEAPEST");
    if (lower.matches("(?s).*(?:not|nicht)\\s+(?:the |die )?(?:cheapest|günstigste).*")
        && lower.matches("(?s).*(fastest|schnellst).*")) result.put("objective", "FASTEST");
    String hazards =
        lower.replaceAll(
            "\\b(?:non[- ](?:dangerous|hazardous)(?: goods)?|not (?:dangerous|hazardous)(?:"
                + " goods)?|(?:dangerous goods|gefahrgut)\\h*[:=]\\h*(?:no|none|nein|n/a|not"
                + " regulated))\\b",
            "");
    if (hazards.matches(
        "(?s).*(dangerous goods|danger"
            + " goods|lithium|temperature.controlled|hazardous|gefahrgut|kühlkette).*"))
      result.put("constraints", text.substring(0, Math.min(text.length(), 400)));
    var due =
        Pattern.compile(
                "(?i)(?:quote by|quote before|angebot bis|delivery by|deliver by|zustellung"
                    + " bis)\\s*:?\\s*(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:\\d{2}))")
            .matcher(text);
    while (due.find())
      try {
        String stamp = java.time.OffsetDateTime.parse(due.group(1)).toInstant().toString();
        boolean quote = due.group().toLowerCase(Locale.ROOT).matches("(?s)^(?:quote|angebot).*");
        result.put(quote ? "quoteDeadline" : "deliveryDeadline", stamp);
      } catch (java.time.DateTimeException ignored) {
      }
    String addressText =
        text.split(
                "(?im)^\\h*(?:best (?:greetings|regards|wishes)|kind regards|regards|cheers|viele"
                    + " gr(?:ü|ue)|mit freundlichen|bestens|--)(?:\\b|\\h*$)",
                2)[0];
    var postal =
        Pattern.compile(
                "(?iu:\\b(?:plz|post\\s*code|postal code|zip(?:"
                    + " code)?|from|ab)\\s*[:=]?\\s*)(\\d{5})\\b"
                    + "|(?<![\\p{L}\\d.,-])(?:D-)?(\\d{5})\\h+\\(?(?=\\p{Lu}\\p{Ll})")
            .matcher(addressText);
    var postcodes = new HashSet<String>();
    while (postal.find())
      postcodes.add(postal.group(1) != null ? postal.group(1) : postal.group(2));
    if (postcodes.size() == 1) {
      String code = postcodes.iterator().next();
      result.put("zone", code.substring(0, 2)).put("pickupPostcode", code);
    } else if (postcodes.size() > 1) result.put("pickupAmbiguous", true);
    result.put(
        "language", lower.matches("(?s).*(bitte|günstig|schnellst|abholung).*") ? "de" : "en");
    if (lower.matches("(?s).*\\bwe(?:['’]ll| will) (?:deliver|bring) [^.]{0,90}\\bourselves\\b.*"))
      result.put("scope", "AIRPORT_TO_AIRPORT");
    var dg = Pattern.compile("(?i)\\bUN[ -]?(\\d{4})\\b").matcher(text);
    if (dg.find()) result.put("dgReference", "UN" + dg.group(1));
    else if (PackingReview.declaresDangerousGoods(text))
      result.put("dgReference", "dangerous goods");
    aggregateLots(text, result);
    return result;
  }

  private static final Pattern LOT =
      Pattern.compile("(?im)^\\s*Lot\\s+(\\d+)\\s*:\\s*([^\\r\\n]+)");
  private static final String Q = "(\\d+(?:[.,]\\d+)?)";
  private static final Pattern LINE =
      Pattern.compile(
          "(?i)^(\\d{1,5})\\s+(?:pieces?|pcs|drums?|cartons?|cases?|boxes|packages?),\\s*"
              + "(?:(each|together)\\s+)?(?:gross weight\\s+)?"
              + Q
              + "\\s*kg(?:\\s+gross)?(?:\\s*\\(\\d+(?:[.,]\\d+)?\\s*kg net\\))?,\\s*"
              + "(?:(each|together)\\s+)?(?:volume\\s+)?"
              + Q
              + "(?:\\s*(cbm|m³|m3)|\\s*[x×]\\s*"
              + Q
              + "\\s*[x×]\\s*"
              + Q
              + "\\s*(cm))\\s*[.;]?$");

  private static void aggregateLots(String text, ObjectNode facts) {
    var rows = LOT.matcher(text);
    if (!rows.find()) return;
    // A partial lot is never a shipment total. Only publish the complete manifest.
    facts.remove(
        List.of(
            "grossKg",
            "grossAlternatives",
            "volumeCbm",
            "volumeAlternatives",
            "pieces",
            "packages"));
    facts.put("lotManifestIncomplete", true);
    var numbers = new HashSet<String>();
    int count = 0;
    BigDecimal kg = BigDecimal.ZERO, cube = BigDecimal.ZERO;
    do {
      var row = LINE.matcher(rows.group(2).strip());
      if (!row.matches() || !numbers.add(rows.group(1))) return;
      int pieces = Integer.parseInt(row.group(1));
      if (pieces < 1) return;
      var weight = number(row.group(3));
      var volume = number(row.group(5));
      if (row.group(6) == null) {
        if (pieces > 1 && !"each".equalsIgnoreCase(row.group(4))) return;
        volume =
            volume.multiply(number(row.group(7))).multiply(number(row.group(8))).movePointLeft(6);
      }
      kg =
          kg.add(
              "each".equalsIgnoreCase(row.group(2))
                  ? weight.multiply(BigDecimal.valueOf(pieces))
                  : weight);
      cube =
          cube.add(
              "each".equalsIgnoreCase(row.group(4))
                  ? volume.multiply(BigDecimal.valueOf(pieces))
                  : volume);
      count += pieces;
    } while (rows.find());
    facts
        .put("lotManifestIncomplete", false)
        .put("pieces", count)
        .put("grossKg", kg.stripTrailingZeros().toPlainString())
        .put("volumeCbm", cube.stripTrailingZeros().toPlainString());
  }
}
