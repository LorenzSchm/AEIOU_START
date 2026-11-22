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

  private static final Pattern REPLY_IN = Pattern.compile(
      "(?iu)\\b(?:reply|respond|quote|antworten sie|r[ée]pondez)(?: please)? "
          + "(?:in|auf|en) (english|anglais|englisch|french|fran[çc]ais|französisch|"
          + "german|allemand|deutsch|chinese|chinois|chinesisch)\\b");
  private static final Pattern WAITING_CHARGE = Pattern.compile(
      "The first (\\d+) minutes of waiting at pickup are included\\. Longer waits incur a charge of EUR"
          + " ([0-9.]+) per hour\\.");
  private final String tag;
  private final Pattern markers;

  CustomerLanguage(String tag, String markers) {
    this.tag = tag;
    this.markers = Pattern.compile(markers, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  static CustomerLanguage of(Map<?, ?> original) {
    String raw = Ledger.str(original, "body");
    String body = MailParser.authoredText(raw);
    String transcript = Ledger.str(original, "voice_transcript");
    if (!transcript.isBlank() && !body.contains(transcript))
      body += "\n" + MailParser.authoredText(transcript);
    var requested = REPLY_IN.matcher(body);
    if (requested.find()) {
      return switch (requested.group(1).toLowerCase(Locale.ROOT)) {
        case "french", "français", "francais", "französisch" -> FRENCH;
        case "german", "allemand", "deutsch" -> GERMAN;
        case "chinese", "chinois", "chinesisch" -> CHINESE;
        default -> ENGLISH;
      };
    }
    String subject = MailParser.currentSubject(Ledger.str(original, "subject"), raw);
    CustomerLanguage selected = ENGLISH;
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
