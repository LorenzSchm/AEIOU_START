package ai.aeiou.dispatch;

import ai.aeiou.freight.MailParser;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

enum CustomerLanguage {
  ENGLISH("en", "\\b(?:please quote|air freight quote|could you|we would like|all-in price)\\b"),
  FRENCH("fr", "\\b(?:devis|fret a[ée]rien|bonjour|bonsoir|nous souhaitons|pourriez-vous|"
      + "pouvez-vous|marchandise|d[ée]lai de transit|tarif tout compris|merci d['’]avance)\\b"),
  GERMAN("de", "\\b(?:luftfracht(?:anfrage)?|frachtanfrage|guten (?:tag|morgen|abend)|"
      + "könnten sie|bitte senden|wir benötigen|wir möchten|abholung|mit freundlichen grüßen)\\b"),
  CHINESE("zh", "空运|詢價|询价|全包价|全包價|提货|提貨|您好|谢谢|謝謝"),
  TURKISH("tr", "\\b(?:merhaba|hava navlun|havalimanı|teslim edeceğiz|teklifinizi|en ucuz|en ekonomik)\\b");

  private static final Map<String, String> LANGUAGE_NAMES = Map.ofEntries(
      Map.entry("english", "en"), Map.entry("anglais", "en"), Map.entry("englisch", "en"),
      Map.entry("inglés", "en"), Map.entry("ingles", "en"), Map.entry("inglese", "en"),
      Map.entry("engels", "en"), Map.entry("angielsku", "en"), Map.entry("ingilizce", "en"),
      Map.entry("french", "fr"), Map.entry("français", "fr"),
      Map.entry("francais", "fr"), Map.entry("französisch", "fr"), Map.entry("francés", "fr"),
      Map.entry("german", "de"), Map.entry("allemand", "de"), Map.entry("deutsch", "de"),
      Map.entry("alemán", "de"), Map.entry("tedesco", "de"), Map.entry("chinese", "zh"),
      Map.entry("chinois", "zh"), Map.entry("chinesisch", "zh"), Map.entry("spanish", "es"),
      Map.entry("español", "es"), Map.entry("espagnol", "es"), Map.entry("spanisch", "es"),
      Map.entry("italian", "it"), Map.entry("italiano", "it"), Map.entry("italienisch", "it"),
      Map.entry("portuguese", "pt"), Map.entry("português", "pt"), Map.entry("dutch", "nl"),
      Map.entry("nederlands", "nl"), Map.entry("polish", "pl"), Map.entry("polski", "pl"),
      Map.entry("turkish", "tr"), Map.entry("türkçe", "tr"), Map.entry("korean", "ko"),
      Map.entry("japanese", "ja"), Map.entry("russian", "ru"), Map.entry("arabic", "ar"));
  private static final Pattern REPLY_IN = Pattern.compile(
      "(?iu)\\b(?:reply|respond|answer|write|quote|antworten sie|r[ée]pondez|responda|rispondete|rispondi)"
          + "(?: to us| us)?(?: please| bitte)? (?:in|auf|en) (" + String.join("|", LANGUAGE_NAMES.keySet()) + ")\\b");
  private static final Pattern WAITING_CHARGE = Pattern.compile(
      "The first (\\d+) minutes of waiting at pickup are included\\. Longer waits incur a charge of EUR"
          + " ([0-9.]+) per hour\\.");
  private final String tag;
  private final Pattern markers;

  CustomerLanguage(String tag, String markers) {
    this.tag = tag;
    this.markers = Pattern.compile(markers, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  /**
   * The language to answer this customer message in: one the customer asked for, otherwise the
   * language of their own authored text (quoted history, forwarded mails and signatures removed),
   * then the current subject. Null when none of that is conclusive.
   */
  static String replyTag(Map<?, ?> original) {
    String raw = Ledger.str(original, "body");
    String body = authored(original);
    String requested = requested(body);
    if (requested != null) return requested;
    String detected = MessageLanguage.detect(body);
    if (detected != null) return detected;
    // A forwarded subject belongs to the quoted mail, not to the customer.
    String subject = Ledger.str(original, "subject");
    if (!MailParser.currentSubject(subject, raw).equals(subject)) subject = "";
    subject = subject.replaceAll("(?iu)^\\s*(?:(?:re|aw|fw|fwd|wg|tr|rv|sv|vs)\\s*:\\s*)+", "");
    detected = MessageLanguage.detectProse(subject + "\n" + body);
    if (detected != null) return detected;
    CustomerLanguage marked = marked(subject, body);
    return marked == null ? null : marked.tag;
  }

  /** A reply language the customer asked for explicitly, such as "please reply in English". */
  static String requestedTag(Map<?, ?> message) {
    return requested(authored(message));
  }

  private static String requested(String authored) {
    var requested = REPLY_IN.matcher(authored);
    return requested.find() ? LANGUAGE_NAMES.get(requested.group(1).toLowerCase(Locale.ROOT)) : null;
  }

  private static String authored(Map<?, ?> message) {
    String body = MailParser.authoredText(Ledger.str(message, "body"));
    String transcript = Ledger.str(message, "voice_transcript");
    if (!transcript.isBlank() && !body.contains(transcript))
      body += "\n" + MailParser.authoredText(transcript);
    return body;
  }

  /** The hand-written templates for a reply tag. Other languages start from English and are rendered. */
  static CustomerLanguage forTag(String tag) {
    String primary = MessageLanguage.primary(tag);
    for (var language : values()) if (language.tag.equals(primary)) return language;
    return ENGLISH;
  }

  static CustomerLanguage of(Map<?, ?> original) {
    return forTag(replyTag(original));
  }

  private static CustomerLanguage marked(String subject, String body) {
    CustomerLanguage selected = null;
    long best = 0;
    for (var language : values()) {
      long score = 2 * language.markers.matcher(subject).results().count()
          + language.markers.matcher(body).results().count();
      if (score > best) {
        selected = language;
        best = score;
      }
    }
    return selected;
  }

  String tag() {
    return tag;
  }

  String text(String english, String french, String german) {
    return switch (this) {
      case ENGLISH, CHINESE, TURKISH -> english;
      case FRENCH -> french;
      case GERMAN -> german;
    };
  }

  String text(String english, String french, String german, String chinese) {
    return this == CHINESE ? chinese : text(english, french, german);
  }

  String text(String english, String french, String german, String chinese, String turkish) {
    return this == TURKISH ? turkish : text(english, french, german, chinese);
  }

  String signoff() {
    return text("Best regards,\nTeam AEIOU", "Cordialement,\nL’équipe AEIOU",
        "Mit freundlichen Grüßen\nTeam AEIOU", "谢谢！\nAEIOU 团队", "Saygılarımızla,\nAEIOU Ekibi");
  }

  String followUpQuestion(String template, String details) {
    if (template == null || template.length() > 145) return null;
    String grammar = switch (this) {
      case ENGLISH -> "(?:To (?:complete|prepare|finalize) (?:your|the) (?:quote|quotation), )?"
          + "(?:(?:Could|Can|Would) you (?:please )?|Please )"
          + "(?:confirm|provide|share|send us|let us know) \\{details\\}[?.]";
      case FRENCH -> "(?:Pour (?:établir|préparer|finaliser) (?:le|votre) devis, )?"
          + "(?:(?:Pouvez|Pourriez)-vous (?:nous )?|Merci de )"
          + "(?:confirmer|préciser|indiquer|fournir|transmettre) \\{details\\}[ \\u00A0\\u202F]?[?.]";
      case GERMAN -> "(?:Für (?:Ihr|das) Angebot,? )?"
          + "(?:(?:Können|Könnten|Würden) Sie|Bitte) (?:uns )?(?:bitte )?\\{details\\} "
          + "(?:bestätigen|mitteilen|angeben|nennen|zusenden)[?.]";
      case CHINESE -> "(?:为了(?:给您|为您)报价[，,])?(?:能否|可否|请您|请|烦请|麻烦您)"
          + "(?:确认|提供|告知|告诉我们) ?\\{details\\}[？?。.]";
      case TURKISH -> "(?!)";
    };
    return Pattern.compile(grammar, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
        .matcher(template).matches() ? template.replace("{details}", details) : null;
  }

  String waitingDisclosure(String disclosure) {
    if (disclosure == null) return "";
    if (this == ENGLISH) return " " + disclosure;
    var terms = WAITING_CHARGE.matcher(disclosure);
    if (!terms.matches()) return " " + disclosure;
    return text("",
        " Les %s premières minutes d’attente à l’enlèvement sont incluses. Au-delà, l’attente coûte %s EUR par heure.",
        " Die ersten %s Minuten Wartezeit bei der Abholung sind enthalten. Danach kostet die Wartezeit %s EUR pro Stunde.",
        " 上门提货时，前 %s 分钟的等待已包含在报价中，超时等待按每小时 %s 欧元另计。")
        .formatted(terms.group(1), this == CHINESE ? terms.group(2) : terms.group(2).replace('.', ','));
  }

  String transit(double days) {
    long hours = Math.round(days * 24);
    long wholeDays = hours / 24, remainder = hours % 24;
    if (this == CHINESE) {
      if (remainder == 0) return wholeDays + " 天";
      return wholeDays == 0 ? remainder + " 小时" : wholeDays + " 天 " + remainder + " 小时";
    }
    if (this == TURKISH) {
      if (remainder == 0) return wholeDays + " gün";
      return wholeDays == 0 ? remainder + " saat" : wholeDays + " gün " + remainder + " saat";
    }
    String dayText = wholeDays + text(wholeDays == 1 ? " day" : " days",
        wholeDays == 1 ? " jour" : " jours", wholeDays == 1 ? " Tag" : " Tage");
    String hourText = remainder + text(remainder == 1 ? " hour" : " hours",
        remainder == 1 ? " heure" : " heures", remainder == 1 ? " Stunde" : " Stunden");
    if (remainder == 0) return dayText;
    return wholeDays == 0 ? hourText : dayText + text(" and ", " et ", " und ") + hourText;
  }
}
