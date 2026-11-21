package ai.aeiou.dispatch;

import java.util.*;
import java.util.regex.Pattern;

/** Opens customer mail with the company named in the sender's signature. */
final class Greeting {
  private static final Pattern LEGAL_FORM =
      Pattern.compile(
          "\\b(?:GmbH|AG|KG|SE|UG|OHG|e\\.\\s?K\\.|Ltd\\.?|LLC|Inc\\.?|S\\.A\\.|B\\.V\\.|AB|Oy|S\\.r\\.l\\.)(?=\\W|$)");
  private static final Set<String> DESKS = Set.of("tracking", "ratedesk");
  private static final int SIGNATURE_LINES = 6;

  private Greeting() {}

  static String hi(Map<?, ?> mail) {
    return company(Ledger.str(mail, "sender"), Ledger.str(mail, "body"))
        .map(name -> "Hi " + name + ",")
        .orElse("Hi,");
  }

  static Optional<String> company(String sender, String body) {
    var lines =
        Arrays.stream(
                body.split("(?im)^\\s*(?:>|On .+ wrote:|-----Original Message-----)", 2)[0]
                    .split("\\R"))
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .toList();
    var signature = lines.subList(Math.max(0, lines.size() - SIGNATURE_LINES), lines.size());
    // "Export Desk, Hofmann Textilwerk KG" or "Kessler Feinmechanik GmbH, Ulm"
    for (String line : signature.reversed())
      for (String part : line.split(",\\s*"))
        if (part.length() <= 60 && !part.matches(".*\\d.*") && LEGAL_FORM.matcher(part).find())
          return Optional.of(part);
    // "Nordlicht Audio" from nordlicht-audio@: the closing line that names the sender's company.
    String local = sender.contains("@") ? sender.substring(0, sender.indexOf('@')) : sender;
    String first = local.split("[-_.]")[0].toLowerCase(Locale.ROOT);
    if (first.length() < 3 || DESKS.contains(local.toLowerCase(Locale.ROOT)))
      return Optional.empty();
    String last = signature.isEmpty() ? "" : signature.getLast().split(",\\s*")[0];
    if (last.length() <= 60
        && last.split("\\s+").length <= 5
        && last.toLowerCase(Locale.ROOT).startsWith(first)) return Optional.of(last);
    return Optional.empty();
  }
}
