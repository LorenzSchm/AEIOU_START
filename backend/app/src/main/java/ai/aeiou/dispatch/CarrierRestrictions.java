package ai.aeiou.dispatch;

import ai.aeiou.freight.MailParser;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

final class CarrierRestrictions {
  private static final Pattern EXCLUSION = Pattern.compile(
      "(?iu)(?:^|(?<=[.!?])\\h+|\\R)\\h*(?:please\\h+)?"
          + "(?:(?:(?:we\\h+)?do not|don['’]t|never)\\h+(?:offer|use|quote|select|book)"
          + "|exclude|avoid)\\h+([^.!?\\r\\n]+)");
  private static final Pattern FUTURE = Pattern.compile(
      "(?iu)\\b(?:future (?:inquiries|enquiries|shipments|rfqs|requests|quotes)"
          + "|going forward|from now on|ever again)\\b");
  private static final Pattern SCOPE = Pattern.compile(
      "(?iu)[,;]?\\h+(?:(?:for|on|in)\\h+(?:this|the|any|our|all|future)\\b"
          + "|going forward\\b|from now on\\b|ever again\\b)");
  private static final Pattern CODE_SUFFIX = Pattern.compile("(?iu)\\h*\\(([A-Z0-9]{2,3})\\)\\h*$");
  private static final Pattern CODE = Pattern.compile("[a-z0-9]{2,3}");
  // Known kit carriers can still be identified when the portal directory is unavailable.
  private static final Map<String, String> KNOWN_AIRLINES = Map.ofEntries(
      Map.entry("au", "Aurelia Air Cargo"),
      Map.entry("bx", "Borealis Freight Airways"),
      Map.entry("fq", "Fjordline Air Cargo"),
      Map.entry("gn", "Gannet Airways"),
      Map.entry("ib", "Ibisair Cargo"),
      Map.entry("jp", "Juniper Cargo Lines"),
      Map.entry("lm", "Lumen Air Cargo"),
      Map.entry("md", "Meridian Freightways"),
      Map.entry("nv", "Nova Atlas Cargo"),
      Map.entry("pl", "Polaris Air Logistics"),
      Map.entry("rv", "Riverbend Airlines Cargo"),
      Map.entry("sl", "Solace Air Cargo"),
      Map.entry("tm", "Tamarind Airways"),
      Map.entry("uv", "Ultraviolet Air Freight"),
      Map.entry("xc", "Xenon Cargo Airlines"),
      Map.entry("zs", "Zephyr Steppe Cargo"));

  private final Ledger ledger;
  private final FiveU upstream;
  private final Clock clock;

  CarrierRestrictions(Ledger ledger, FiveU upstream, Clock clock) {
    this.ledger = ledger;
    this.upstream = upstream;
    this.clock = clock;
  }

  Set<String> forRequest(long request, Map<String, Object> original) {
    var aliases = new HashSet<String>();
    for (var row : ledger.jdbc().queryForList("""
        SELECT id,root,body FROM mail WHERE sender=? AND (kind='request' OR kind='follow-up')
        AND (id<=? OR root=? OR root IN (
          SELECT json_extract(response,'$.id') FROM deliveries WHERE action LIKE ? OR action LIKE ?
        )) ORDER BY id
        """, original.get("sender"), request, request, "%:" + request, "%:" + request + ":%")) {
      long id = Ledger.id(row.get("id"));
      String authored = MailParser.authoredText(Ledger.str(row, "body"));
      var exclusions = EXCLUSION.matcher(authored);
      while (exclusions.find()) {
        String clause = exclusions.group(1);
        if (id < request && !FUTURE.matcher(clause).find()) continue;
        String names = SCOPE.split(clause, 2)[0];
        for (String name : names.split("(?iu)\\h*(?:,|;|\\band\\b|\\bor\\b)\\h*"))
          aliases.addAll(aliases(name));
      }
    }
    return aliases;
  }

  boolean excludes(Set<String> restrictions, String carrier) {
    return !restrictions.isEmpty() && aliases(carrier).stream().anyMatch(restrictions::contains);
  }

  private Set<String> aliases(String carrier) {
    var names = new HashSet<String>();
    var code = CODE_SUFFIX.matcher(carrier);
    if (code.find()) {
      names.add(code.group(1).toLowerCase(Locale.ROOT));
      carrier = carrier.substring(0, code.start());
    }
    String name = normalize(carrier);
    if (!name.isEmpty()) names.add(name);
    for (String alias : List.copyOf(names)) {
      if (!CODE.matcher(alias).matches()) continue;
      String fullName = KNOWN_AIRLINES.get(alias);
      if (fullName == null) fullName = upstream.airlineName(alias, clock.instant());
      if (fullName != null && !fullName.isBlank()) names.add(normalize(fullName));
    }
    return names;
  }

  private static String normalize(String carrier) {
    return carrier.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
  }
}
