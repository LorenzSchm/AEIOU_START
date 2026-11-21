package ai.aeiou.forwarder.domain;

import static ai.aeiou.forwarder.domain.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

public final class PackingReview {
  private PackingReview() {}

  private static final Pattern DG =
      Pattern.compile(
          "(?iu)\\bUN[\\s-]*[0-9]{4}\\b|\\b(?:dangerous\\s+goods|hazmat|gefahrgut)\\s*[:=]?\\s*(?:yes|ja|class\\s+[1-9]|klasse\\s+[1-9])\\b|\\b(?:hazard|DG)\\s+class\\s*[:=]?\\s*[1-9]");

  static boolean declaresDangerousGoods(String text) {
    return DG.matcher(text).find();
  }

  enum DocumentType {
    GENERAL,
    DANGEROUS,
    UNKNOWN
  }

  private static final Pattern GENERAL =
      Pattern.compile(
          "(?im)^\\h*(?:dangerous goods|hazmat|gefahrgut)\\h*[:=]?\\h*(?:no|none|nein|n/a|not"
              + " regulated|non-dangerous)\\h*[.]?\\h*$|\\b(?:general cargo\\s*,?\\s*non[-"
              + " ]dangerous|non[- ]dangerous\\s+goods)\\b");
  private static final Pattern PACKING =
      Pattern.compile(
          "(?iu)\\b(?:packing (?:list|specification)|shipper.s letter of"
              + " instruction|packliste)\\b");
  private static final Pattern DESTINATION =
      Pattern.compile("(?im)^\\h*destination airport\\h*[:=]?\\h*([A-Z]{3})\\b");
  private static final Pattern GROSS =
      Pattern.compile(
          "(?im)^\\h*total gross weight\\h*\\(kg\\)\\h*[:=]?\\h*([0-9]+(?:[.,][0-9]+)?)\\h*$");
  private static final Pattern VOLUME =
      Pattern.compile(
          "(?im)^\\h*total volume\\h*\\((?:cbm|m3|m³)\\)\\h*[:=]?\\h*([0-9]+(?:[.,][0-9]+)?)\\h*$");
  private static final Pattern UN_NUMBER = Pattern.compile("(?iu)\\bUN[\\s-]*([0-9]{4})\\b");

  record Document(String text) {
    DocumentType type() {
      if (declaresDangerousGoods(text)) return DocumentType.DANGEROUS;
      if (PACKING.matcher(text).find() && GENERAL.matcher(text).find()) return DocumentType.GENERAL;
      return DocumentType.UNKNOWN;
    }

    Set<String> destinations() {
      return values(DESTINATION, text);
    }

    Set<BigDecimal> grossWeights() {
      return decimals(values(GROSS, text));
    }

    Set<BigDecimal> volumes() {
      return decimals(values(VOLUME, text));
    }

    Set<String> unNumbers() {
      return values(UN_NUMBER, text);
    }

    boolean safetyDataSheet() {
      return Pattern.compile("(?iu)\\bsafety data sheet\\b").matcher(text).find()
          && Pattern.compile("(?iu)\\bsection\\h+14\\b").matcher(text).find();
    }

    boolean declaration() {
      return Pattern.compile(
              "(?iu)\\b(?:declaration for dangerous goods|dangerous goods declaration)\\b")
          .matcher(text)
          .find();
    }
  }

  private static Set<String> values(Pattern pattern, String text) {
    var results = new HashSet<String>();
    pattern
        .matcher(text)
        .results()
        .map(m -> m.group(1).toUpperCase(Locale.ROOT))
        .forEach(results::add);
    return results;
  }

  private static Set<BigDecimal> decimals(Set<String> values) {
    var results = new HashSet<BigDecimal>();
    values.stream()
        .map(value -> new BigDecimal(value.replace(',', '.')).stripTrailingZeros())
        .forEach(results::add);
    return results;
  }

  public static ObjectNode describe(String text, long message) {
    var doc = new Document(text);
    return obj(
        "message",
        message,
        "type",
        doc.type().name(),
        "un",
        M.valueToTree(doc.unNumbers()),
        "sds",
        doc.safetyDataSheet(),
        "declaration",
        doc.declaration(),
        "destination",
        M.valueToTree(doc.destinations()),
        "grossKg",
        M.valueToTree(doc.grossWeights()),
        "volumeCbm",
        M.valueToTree(doc.volumes()));
  }

  public record Result(String state, ObjectNode fields, String classification) {}

  public static Result assess(
      JsonNode documents, JsonNode mailFacts, String priorClassification, long originalId) {
    var numbers = new TreeSet<String>();
    if (priorClassification.matches("UN[0-9]{4}")) numbers.add(priorClassification.substring(2));
    boolean dg = !priorClassification.isEmpty(), followup = false;
    long newest = 0;
    for (var doc : documents) {
      newest = Math.max(newest, doc.path("message").asLong());
      followup |= doc.path("message").asLong() > originalId;
      dg |= "DANGEROUS".equals(str(doc, "type"));
      for (var un : doc.path("un")) numbers.add(un.asText());
    }
    var fields = obj();
    if (dg) {
      boolean sds = false, declaration = false;
      String ref = numbers.size() == 1 ? "UN" + numbers.first() : "dangerous goods";
      for (var doc : documents) {
        if (numbers.size() != 1
            || doc.path("un").size() != 1
            || !numbers.first().equals(doc.path("un").get(0).asText())) continue;
        sds |= doc.path("sds").asBoolean();
        declaration |= doc.path("declaration").asBoolean();
      }
      return new Result(
          sds && declaration
              ? "dg-unavailable"
              : (followup || sds || declaration) ? "dg-incomplete" : "dg-documents",
          fields,
          ref);
    }
    var latest = new ArrayList<JsonNode>();
    for (var doc : documents) if (doc.path("message").asLong() == newest) latest.add(doc);
    if (latest.isEmpty() || latest.stream().anyMatch(doc -> !"GENERAL".equals(str(doc, "type"))))
      return new Result("packing-copy", fields, "");
    for (String key : List.of("destination", "grossKg", "volumeCbm")) {
      Set<String> values = new HashSet<>();
      for (var doc : latest)
        for (var value : doc.path(key)) values.add(canonical(key, value.asText()));
      if (mailFacts.hasNonNull(key)) values.add(canonical(key, str(mailFacts, key)));
      if (key.equals("grossKg") && mailFacts.has("grossAlternatives")
          || key.equals("volumeCbm") && mailFacts.has("volumeAlternatives"))
        return new Result("packing-correction", obj(), "");
      if (values.size() != 1) return new Result("packing-correction", obj(), "");
      fields.put(key, values.iterator().next());
    }
    return new Result("verified", fields, "");
  }

  private static String canonical(String key, String value) {
    return key.equals("destination")
        ? value
        : new BigDecimal(value).stripTrailingZeros().toPlainString();
  }
}
