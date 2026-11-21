package ai.aeiou.forwarder.domain;

import static ai.aeiou.forwarder.domain.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Pattern;

public final class Clarification {
  private Clarification() {}

  public static List<String> missing(JsonNode shipment) {
    var known = shipment.path("inputs");
    var session = shipment.path("session");
    var fields = new ArrayList<String>();
    boolean pickup = "DOOR_TO_PORT".equals(str(session, "scope"));
    if (pickup
        && (!known.hasNonNull("pickupPostcode") || known.path("pickupAmbiguous").asBoolean()))
      fields.add("pickupPostcode");
    else if (!str(session, "origin").matches("[A-Z]{3}")) fields.add("origin");
    if (!str(session, "destination").matches("[A-Z]{3}")) fields.add("destination");
    if (!known.hasNonNull("grossKg") || Pricing.decimal(known, "grossKg").signum() <= 0)
      fields.add("grossKg");
    if (!known.hasNonNull("volumeCbm")) fields.add("volumeCbm");
    if (!Set.of("CHEAPEST", "FASTEST").contains(str(session, "objective"))) fields.add("objective");
    if (!Set.of("AIRPORT_TO_AIRPORT", "DOOR_TO_PORT").contains(str(session, "scope")))
      fields.add("scope");
    return fields;
  }

  public static String question(List<String> fields, boolean de) {
    var labels = new ArrayList<String>();
    for (var field : fields)
      labels.add(
          switch (field) {
            case "pickupPostcode" ->
                de ? "die fünfstellige Abhol-PLZ" : "the full five-digit pickup postcode";
            case "origin" -> de ? "den Abflughafen" : "the departure airport";
            case "destination" -> de ? "den Zielflughafen" : "the destination airport";
            case "grossKg" ->
                de ? "das gesamte Bruttogewicht in kg" : "the total gross weight in kg";
            case "volumeCbm" -> de ? "das Gesamtvolumen in m³" : "the total volume in cubic metres";
            case "objective" ->
                de
                    ? "ob Preis oder Laufzeit Vorrang hat"
                    : "whether you prefer the lowest price or fastest transit";
            case "scope" ->
                de
                    ? "ob Sie selbst zum Flughafen liefern oder eine Abholung benötigen"
                    : "whether you will deliver to the airport or need collection";
            default -> throw new IllegalArgumentException(field);
          });
    return (de ? "Könnten Sie bitte Folgendes bestätigen: " : "Could you please confirm ")
        + String.join(", ", labels)
        + "?";
  }

  public static ObjectNode answer(String text, JsonNode requested) {
    String fresh = ShipmentFacts.fresh(text);
    var fields = ShipmentFacts.extract(fresh);
    var accepted = new HashSet<String>(List.of("dgReference", "constraints"));
    requested.forEach(k -> accepted.add(k.asText()));
    if (accepted.contains("pickupPostcode") || accepted.contains("scope")) {
      accepted.addAll(List.of("zone", "pickupPostcode", "scope", "pickupAmbiguous"));
      if (fresh.matches("\\d{5}[.!]?")) {
        String code = fresh.substring(0, 5);
        fields.put("zone", code.substring(0, 2)).put("pickupPostcode", code);
      }
      if (fields.has("pickupPostcode") && !fields.has("scope")) fields.put("scope", "DOOR_TO_PORT");
    }
    if (accepted.contains("scope") && !fields.has("scope")) {
      if (fresh.matches(
          "(?is).*(?:self[- ]delivery|(?:we(?:.ll| will)? (?:deliver|bring)|drop off)"
              + " .{0,45}(?:airport|flughafen)).*"))
        fields.put("scope", "AIRPORT_TO_AIRPORT");
      else if (fresh.matches("(?is).*(?:need|require|arrange) (?:warehouse )?collection.*"))
        fields.put("scope", "DOOR_TO_PORT");
    }
    for (String key : List.of("origin", "destination")) {
      if (!accepted.contains(key)) continue;
      String label =
          key.equals("origin")
              ? "origin|departure airport|Abflughafen"
              : "destination(?: airport)?|Zielflughafen";
      var m =
          Pattern.compile("(?i:\\b(?:" + label + ")\\s*(?:is\\s+|[:=]\\s*)?)([A-Z]{3})\\b")
              .matcher(fresh);
      if (m.find()) fields.put(key, m.group(1));
      else if (requested.size() == 1 && fresh.matches("[A-Z]{3}[.!]?"))
        fields.put(key, fresh.substring(0, 3));
    }
    fields.retain(accepted);
    return fields;
  }
}
