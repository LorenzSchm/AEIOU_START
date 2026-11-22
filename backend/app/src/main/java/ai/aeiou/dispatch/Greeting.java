package ai.aeiou.dispatch;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/** Builds a time-of-day greeting and uses a personal name when the signature identifies one. */
final class Greeting {
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final Pattern LEGAL_FORM =
      Pattern.compile(
          "\\b(?:GmbH|AG|KG|SE|UG|OHG|e\\.\\s?K\\.|Ltd\\.?|LLC|Inc\\.?|S\\.A\\.|B\\.V\\.|AB|Oy|S\\.r\\.l\\.)(?=\\W|$)");
  private static final Set<String> DESKS = Set.of("tracking", "ratedesk");
  private static final int SIGNATURE_LINES = 8;
  private static final Pattern SIGNOFF = Pattern.compile(
      "(?iu)^(?:best(?: regards| wishes| greetings(?: from .+)?)?|kind regards|regards|sincerely"
          + "|cheers|thanks(?: a lot)?(?: and have a .+)?|thank you|many thanks"
          + "|mit freundlichen gr(?:ü|ue|u)(?:ss|ß)en|viele gr(?:ü|ue|u)(?:ss|ß)e|bestens"
          + "|cordialement|bien cordialement|merci|bien à vous)"
          + "(?:\\h*/\\h*kind regards)?[,.! ]*$");
  private static final Pattern PERSONAL_NAME = Pattern.compile(
      "^(?<first>[\\p{Lu}][\\p{L}’'-]+)(?: (?:[\\p{Lu}][\\p{L}’'-]+|"
          + "[\\p{Lu}]\\.|de|del|der|van|von|den|da|di|la|le)){1,4}$");
  private static final Pattern ROLE = Pattern.compile(
      "(?iu)\\b(?:desk|team|service|solutions|support|sales|shipping|logistics|logistik"
          + "|export|import|department|manager|coordinator|director|operations)\\b");
  private static final Pattern OPENING =
      Pattern.compile("(?:Hi|Hello|Good morning|Good afternoon|Good evening)(?: [^\\r\\n]+)?,");

  private Greeting() {}

  static String at(Map<?, ?> mail, Instant now) {
    return at(mail, now, CustomerLanguage.ENGLISH);
  }

  static String at(Map<?, ?> mail, Instant now, CustomerLanguage language) {
    if (language == CustomerLanguage.CHINESE) return "您好，";
    int hour = now.atZone(BERLIN).getHour();
    String opening = language.text(
        hour < 5 ? "Hello" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening",
        hour < 18 ? "Bonjour" : "Bonsoir",
        hour < 5 ? "Hallo" : hour < 12 ? "Guten Morgen" : hour < 18 ? "Guten Tag" : "Guten Abend");
    return opening + firstName(Ledger.str(mail, "sender"), Ledger.str(mail, "body"))
        .map(name -> " " + name).orElse("") + ",";
  }

  static String refresh(String body, Map<?, ?> mail, Instant now) {
    int end = body.indexOf("\n\n");
    return end >= 0 && OPENING.matcher(body.substring(0, end)).matches()
        ? at(mail, now) + body.substring(end) : body;
  }

  private static Optional<String> firstName(String sender, String body) {
    var lines =
        Arrays.stream(
                body.split("(?im)^\\s*(?:>|On .+ wrote:|-----Original Message-----)", 2)[0]
                    .split("\\R"))
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .toList();
    var signature = lines.subList(Math.max(0, lines.size() - SIGNATURE_LINES), lines.size());
    var company = company(sender, signature);
    for (int i = signature.size() - 2; i >= 0; i--) {
      if (SIGNOFF.matcher(signature.get(i)).matches())
        return personalName(signature.get(i + 1), company);
    }
    for (int i = signature.size() - 1; i > 0; i--) {
      if (company.isPresent() && signature.get(i).contains(company.get())) {
        int nameLine = i - 1;
        while (nameLine >= 0 && ROLE.matcher(signature.get(nameLine)).find()) nameLine--;
        if (nameLine >= 0) return personalName(signature.get(nameLine), company);
      }
    }
    return Optional.empty();
  }

  private static Optional<String> personalName(String line, Optional<String> company) {
    if (ROLE.matcher(line).find() || LEGAL_FORM.matcher(line).find()
        || company.filter(name -> name.equalsIgnoreCase(line)).isPresent())
      return Optional.empty();
    var match = PERSONAL_NAME.matcher(line);
    return match.matches() ? Optional.of(match.group("first")) : Optional.empty();
  }

  private static Optional<String> company(String sender, List<String> signature) {
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
