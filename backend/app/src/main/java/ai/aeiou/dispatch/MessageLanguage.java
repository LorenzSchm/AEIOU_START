package ai.aeiou.dispatch;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Local, offline language detection for mail bodies. Returns a lowercase ISO 639-1 code, or null
 * when the text is too short or the detector is not confident. Results are cached by text hash
 * because the desk polls the whole inbox every few seconds.
 */
final class MessageLanguage {
  static final int MIN_LETTERS = 20;
  static final double MIN_CONFIDENCE = 0.6;
  private static final int CACHE_SIZE = 20_000;

  private static final Pattern REPLY_HEADER =
      Pattern.compile(
          "(?iu)^\\s*(?:-{2,}\\s*(?:original message|ursprüngliche nachricht|message d'origine)"
              + "|(?:on|am|le|el|il|op|w dniu)\\b.{0,200}\\b(?:wrote|schrieb|a écrit|escribió|ha"
              + " scritto|schreef|napisał)\\s*:?)\\s*-*\\s*$");
  private static final Pattern HEADER_LINE =
      Pattern.compile(
          "(?iu)^\\s*(?:from|to|cc|bcc|sent|date|subject|von|an|gesendet|datum|betreff|de|à|envoyé"
              + "|objet)\\s*:.*$");
  private static final Pattern SIGNATURE_START =
      Pattern.compile(
          "(?iu)^\\s*(?:--\\s*|_{3,}|(?:best|kind|warm|many|with best)?\\s*(?:regards|greetings|wishes)\\b.{0,40}"
              + "|(?:best|thanks|thank you|cheers|sincerely|yours sincerely|yours faithfully),?"
              + "|(?:mit\\s+)?(?:(?:freundlichen?|besten?|herzlichen?|viele|liebe)\\s+)?"
              + "(?:grü(?:ß|ss)(?:e|en)?|gru(?:ß|ss))\\b.*|lg|vg|mfg"
              + "|cordialement\\b.*|bien à vous\\b.*|saludos\\b.*|cordiali saluti\\b.*"
              + "|met vriendelijke groet(?:en)?\\b.*|pozdrawiam\\b.*)\\s*$");
  private static final Pattern NOISE =
      Pattern.compile(
          "[Hh][Tt][Tt][Pp][Ss]?://\\S+|[Ww]{3}\\.\\S+|\\S+@\\S+|\\S*\\d\\S*|\\b\\p{Lu}{2,}\\b");

  private static final Map<String, Optional<String>> CACHE =
      Collections.synchronizedMap(
          new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Optional<String>> eldest) {
              return size() > CACHE_SIZE;
            }
          });

  private MessageLanguage() {}

  private static final class Holder {
    static final LanguageDetector DETECTOR =
        LanguageDetectorBuilder.fromLanguages(
                Language.ENGLISH,
                Language.GERMAN,
                Language.FRENCH,
                Language.ITALIAN,
                Language.SPANISH,
                Language.DUTCH,
                Language.POLISH)
            .withLowAccuracyMode()
            .build();
  }

  /** Loads the language models in the background so the first poll does not pay for it. */
  static void warmUp() {
    Thread.ofVirtual().name("language-warmup").start(() -> detect("This is a warm-up sentence."));
  }

  static String detect(String text) {
    if (text == null || text.isBlank()) return null;
    return CACHE.computeIfAbsent(hash(text), ignored -> Optional.ofNullable(compute(text)))
        .orElse(null);
  }

  private static String compute(String text) {
    String clean = clean(text);
    if (clean.codePoints().filter(Character::isLetter).count() < MIN_LETTERS) return null;
    var confidences = Holder.DETECTOR.computeLanguageConfidenceValues(clean);
    if (confidences.isEmpty()) return null;
    var best = confidences.entrySet().iterator().next();
    if (best.getValue() < MIN_CONFIDENCE) return null;
    return best.getKey().getIsoCode639_1().toString().toLowerCase(Locale.ROOT);
  }

  /** Keeps only the author's own prose: no quoted replies, headers, signatures or codes. */
  static String clean(String text) {
    var kept = new ArrayList<String>();
    for (String line : text.split("\\R")) {
      String trimmed = line.strip();
      if (REPLY_HEADER.matcher(trimmed).matches() || SIGNATURE_START.matcher(trimmed).matches())
        break;
      if (trimmed.startsWith(">") || HEADER_LINE.matcher(trimmed).matches()) continue;
      kept.add(NOISE.matcher(trimmed).replaceAll(" ").replaceAll("\\s+", " ").strip());
    }
    // An unmarked signature is a trailing block of short lines: name, company, street, city.
    while (!kept.isEmpty() && looksLikeSignature(kept.getLast())) kept.removeLast();
    return String.join("\n", kept).strip();
  }

  private static boolean looksLikeSignature(String line) {
    return line.isEmpty() || (line.split(" ").length < 5 && !line.matches(".*[.?!:]$"));
  }

  private static String hash(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
