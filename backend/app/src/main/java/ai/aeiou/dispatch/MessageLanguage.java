package ai.aeiou.dispatch;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import java.lang.Character.UnicodeScript;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Local, offline language detection for mail bodies. Returns a lowercase ISO 639-1 code, or null
 * when the text is too short or the detector is not confident. Results are cached by text hash
 * because the desk polls the whole inbox every few seconds.
 *
 * <p>Writing systems decide first: Hangul is Korean, kana is Japanese, Han without either is
 * Chinese, and Thai, Greek, Hebrew, Georgian, Armenian and the Indic scripts each map to their
 * language. Cyrillic, Arabic and Latin text goes to a statistical detector that only considers
 * languages written in that script.
 */
final class MessageLanguage {
  static final int MIN_LETTERS = 20;
  static final double MIN_CONFIDENCE = 0.5;
  private static final int CACHE_SIZE = 20_000;

  private static final Pattern REPLY_HEADER =
      Pattern.compile(
          "(?iu)^\\s*(?:-{2,}\\s*(?:original message|forwarded message|ursprüngliche nachricht"
              + "|weitergeleitete nachricht|message d'origine|message transféré|mensaje original"
              + "|messaggio originale|oorspronkelijk bericht|mensagem original|wiadomość oryginalna)"
              + "|(?:on|am|le|el|il|op|w dniu|em)\\b.{0,200}\\b(?:wrote|schrieb|a écrit|escribió|ha"
              + " scritto|schreef|napisał|escreveu)\\s*:?)\\s*-*\\s*$");
  private static final Pattern HEADER_LINE =
      Pattern.compile(
          "(?iu)^\\s*(?:from|to|cc|bcc|sent|date|subject|von|an|gesendet|datum|betreff|de|à|envoyé"
              + "|objet|para|asunto|enviado|da|oggetto|inviato|van|aan|onderwerp|verzonden)\\s*:.*$");
  private static final Pattern SIGNATURE_START =
      Pattern.compile(
          "(?iu)^\\s*(?:--\\s*|_{3,}|(?:best|kind|warm|many|with best)?\\s*(?:regards|greetings|wishes)\\b.{0,40}"
              + "|(?:best|thanks|thank you|cheers|sincerely|yours sincerely|yours faithfully),?"
              + "|(?:mit\\s+)?(?:(?:freundlichen?|besten?|herzlichen?|viele|liebe)\\s+)?"
              + "(?:gr(?:ü|ue|u)(?:ß|ss)(?:e|en)?|gru(?:ß|ss))\\b.*|lg|vg|mfg"
              + "|(?:bien )?cordialement\\b.*|bien à vous\\b.*|(?:un )?saludos?\\b.*|atentamente\\b.*"
              + "|cordiali saluti\\b.*|distinti saluti\\b.*|saluti\\b.*"
              + "|met vriendelijke groet(?:en)?\\b.*|(?:vriendelijke )?groeten\\b.*|pozdrawiam\\b.*"
              + "|atenciosamente\\b.*|cumprimentos\\b.*|abra[çc]os\\b.*"
              + "|saygılar(?:ımızla|ımla)?\\b.*|iyi çalışmalar\\b.*"
              + "|med vänliga hälsningar\\b.*|mvh\\b.*|s pozdravem\\b.*)\\s*$");
  private static final Pattern NOISE =
      Pattern.compile(
          "[Hh][Tt][Tt][Pp][Ss]?://\\S+|[Ww]{3}\\.\\S+|\\S+@\\S+|[\\p{IsLatin}\\d.,:/_+()-]*\\d[\\p{IsLatin}\\d.,:/_+()-]*|\\b\\p{Lu}{2,}\\b");

  private static final Map<String, Optional<String>> CACHE =
      Collections.synchronizedMap(
          new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Optional<String>> eldest) {
              return size() > CACHE_SIZE;
            }
          });

  private MessageLanguage() {}

  // Low accuracy mode uses trigram models only, which keeps memory small for this many languages
  // and is reliable for mail-length text. Short or mixed text simply comes back uncertain.
  private static final class Latin {
    static final LanguageDetector DETECTOR =
        LanguageDetectorBuilder.fromLanguages(
                Language.ENGLISH, Language.GERMAN, Language.FRENCH, Language.ITALIAN,
                Language.SPANISH, Language.PORTUGUESE, Language.DUTCH, Language.POLISH,
                Language.TURKISH, Language.SWEDISH, Language.DANISH, Language.BOKMAL,
                Language.FINNISH, Language.CZECH, Language.SLOVAK, Language.SLOVENE,
                Language.CROATIAN, Language.ROMANIAN, Language.HUNGARIAN, Language.ESTONIAN,
                Language.LATVIAN, Language.LITHUANIAN, Language.INDONESIAN, Language.MALAY,
                Language.VIETNAMESE, Language.TAGALOG, Language.ALBANIAN, Language.CATALAN,
                Language.AFRIKAANS, Language.ICELANDIC, Language.IRISH, Language.WELSH,
                Language.AZERBAIJANI, Language.SWAHILI)
            .withLowAccuracyMode()
            .build();
  }

  private static final class Cyrillic {
    static final LanguageDetector DETECTOR =
        LanguageDetectorBuilder.fromLanguages(
                Language.RUSSIAN, Language.UKRAINIAN, Language.BULGARIAN, Language.SERBIAN,
                Language.MACEDONIAN, Language.BELARUSIAN, Language.KAZAKH, Language.MONGOLIAN)
            .withLowAccuracyMode()
            .build();
  }

  private static final class Arabic {
    static final LanguageDetector DETECTOR =
        LanguageDetectorBuilder.fromLanguages(Language.ARABIC, Language.PERSIAN, Language.URDU)
            .withLowAccuracyMode()
            .build();
  }

  private static final class Devanagari {
    static final LanguageDetector DETECTOR =
        LanguageDetectorBuilder.fromLanguages(Language.HINDI, Language.MARATHI)
            .withLowAccuracyMode()
            .build();
  }

  /** Loads the language models in the background so the first poll does not pay for it. */
  static void warmUp() {
    Thread.ofVirtual()
        .name("language-warmup")
        .start(() -> detect("This is a warm-up sentence for the language detector."));
  }

  /** The language of the author's own prose, without quoted history and signatures. */
  static String detect(String text) {
    if (text == null || text.isBlank()) return null;
    return CACHE.computeIfAbsent(hash(text), ignored -> Optional.ofNullable(compute(clean(text))))
        .orElse(null);
  }

  /** The language of text we wrote ourselves: nothing is stripped except codes and numbers. */
  static String detectProse(String text) {
    if (text == null || text.isBlank()) return null;
    return compute(NOISE.matcher(text).replaceAll(" "));
  }

  /** Letters that carry language: everything except Latin capitals codes, numbers and links. */
  static long letters(String text) {
    return NOISE.matcher(text).replaceAll(" ").codePoints().filter(Character::isLetter).count();
  }

  /**
   * True unless the text is confidently in another language than {@code tag}. Text written in a
   * non-Latin script must use the script of {@code tag}.
   */
  static boolean compatible(String text, String tag) {
    String target = primary(tag);
    if (target == null) return true;
    String prose = NOISE.matcher(text).replaceAll(" ");
    var counts = scripts(prose);
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    if (total == 0) return true;
    UnicodeScript expected = scriptOf(target);
    if (expected != UnicodeScript.LATIN) {
      // Our own text in Korean or Chinese still carries Latin names; most letters must be native.
      long own = nativeLetters(counts, target);
      return own > 0 && weighted(own, expected) >= total - own;
    }
    if (dominantScript(counts) != UnicodeScript.LATIN) return false;
    if (total < MIN_LETTERS) return true;
    String detected = compute(prose);
    return detected == null || detected.equals(target);
  }

  /** The primary ISO 639 subtag in lower case, or null when the tag is unusable. */
  static String primary(String tag) {
    if (tag == null) return null;
    String primary = tag.strip().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
    if (primary.equals("no") || primary.equals("nn")) return "nb";
    return primary.matches("[a-z]{2,3}") ? primary : null;
  }

  private static String compute(String clean) {
    var counts = scripts(clean);
    var script = dominantScript(counts);
    if (script == null) return null;
    switch (script) {
      case HANGUL:
        return "ko";
      case HIRAGANA:
        return "ja";
      case HAN:
        return "zh";
      case THAI:
        return "th";
      case GREEK:
        return "el";
      case HEBREW:
        return "he";
      case GEORGIAN:
        return "ka";
      case ARMENIAN:
        return "hy";
      case BENGALI:
        return "bn";
      case TAMIL:
        return "ta";
      case TELUGU:
        return "te";
      case GUJARATI:
        return "gu";
      case GURMUKHI:
        return "pa";
      case CYRILLIC:
        return statistical(Cyrillic.DETECTOR, clean, 8, "ru");
      case ARABIC:
        return statistical(Arabic.DETECTOR, clean, 8, "ar");
      case DEVANAGARI:
        return statistical(Devanagari.DETECTOR, clean, 8, "hi");
      case LATIN:
        return statistical(Latin.DETECTOR, clean, MIN_LETTERS, null);
      default:
        return null;
    }
  }

  private static String statistical(LanguageDetector detector, String text, int minimum,
      String fallback) {
    if (text.codePoints().filter(Character::isLetter).count() < minimum) return fallback;
    var confidences = detector.computeLanguageConfidenceValues(text);
    if (confidences.isEmpty()) return fallback;
    var best = confidences.entrySet().iterator().next();
    if (best.getValue() < MIN_CONFIDENCE) return fallback;
    return best.getKey().getIsoCode639_1().toString().toLowerCase(Locale.ROOT);
  }

  private static Map<UnicodeScript, Long> scripts(String text) {
    var counts = new EnumMap<UnicodeScript, Long>(UnicodeScript.class);
    text.codePoints()
        .filter(Character::isLetter)
        .forEach(
            point -> {
              var script = UnicodeScript.of(point);
              if (script == UnicodeScript.KATAKANA) script = UnicodeScript.HIRAGANA;
              counts.merge(script, 1L, Long::sum);
            });
    return counts;
  }

  /**
   * The script that carries the text. One Han or Hangul character holds about as much as a short
   * Latin word, so those count double against Latin names, codes and units in the same mail.
   */
  private static UnicodeScript dominantScript(Map<UnicodeScript, Long> counts) {
    long latin = counts.getOrDefault(UnicodeScript.LATIN, 0L);
    long hangul = counts.getOrDefault(UnicodeScript.HANGUL, 0L);
    long kana = counts.getOrDefault(UnicodeScript.HIRAGANA, 0L);
    long han = counts.getOrDefault(UnicodeScript.HAN, 0L);
    long cjk = hangul + kana + han;
    UnicodeScript other = null;
    long otherCount = 0;
    for (var entry : counts.entrySet()) {
      var script = entry.getKey();
      if (script == UnicodeScript.LATIN || script == UnicodeScript.HANGUL
          || script == UnicodeScript.HIRAGANA || script == UnicodeScript.HAN
          || script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED) continue;
      if (entry.getValue() > otherCount) {
        other = script;
        otherCount = entry.getValue();
      }
    }
    if (cjk >= 2 && 2 * cjk >= latin && 2 * cjk >= otherCount) {
      // Korean mail may quote a Hanja word and Japanese always mixes kana with kanji.
      if (hangul > 0 && hangul >= kana && 2 * hangul >= han) return UnicodeScript.HANGUL;
      if (kana > 0 && 10 * kana >= kana + han) return UnicodeScript.HIRAGANA;
      if (han >= hangul) return UnicodeScript.HAN;
      return UnicodeScript.HANGUL;
    }
    if (other != null && otherCount >= 4 && otherCount >= latin) return other;
    if (latin > 0) return UnicodeScript.LATIN;
    return other;
  }

  private static UnicodeScript scriptOf(String tag) {
    return switch (tag) {
      case "ko" -> UnicodeScript.HANGUL;
      case "ja" -> UnicodeScript.HIRAGANA;
      case "zh" -> UnicodeScript.HAN;
      case "th" -> UnicodeScript.THAI;
      case "el" -> UnicodeScript.GREEK;
      case "he" -> UnicodeScript.HEBREW;
      case "ka" -> UnicodeScript.GEORGIAN;
      case "hy" -> UnicodeScript.ARMENIAN;
      case "bn" -> UnicodeScript.BENGALI;
      case "ta" -> UnicodeScript.TAMIL;
      case "te" -> UnicodeScript.TELUGU;
      case "gu" -> UnicodeScript.GUJARATI;
      case "pa" -> UnicodeScript.GURMUKHI;
      case "ru", "uk", "bg", "sr", "mk", "be", "kk", "mn" -> UnicodeScript.CYRILLIC;
      case "ar", "fa", "ur" -> UnicodeScript.ARABIC;
      case "hi", "mr", "ne" -> UnicodeScript.DEVANAGARI;
      default -> UnicodeScript.LATIN;
    };
  }

  private static long nativeLetters(Map<UnicodeScript, Long> counts, String tag) {
    return switch (tag) {
      // Japanese mixes kanji with kana; Korean text may quote a Hanja word.
      case "ja" -> counts.getOrDefault(UnicodeScript.HIRAGANA, 0L)
          + counts.getOrDefault(UnicodeScript.HAN, 0L);
      default -> counts.getOrDefault(scriptOf(tag), 0L);
    };
  }

  private static long weighted(long letters, UnicodeScript script) {
    return switch (script) {
      case HANGUL, HIRAGANA, HAN -> 2 * letters;
      default -> letters;
    };
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
    if (line.isEmpty()) return true;
    // A short CJK sentence has no spaces, so word counts say nothing about it.
    if (line.codePoints().anyMatch(point -> {
      var script = UnicodeScript.of(point);
      return script == UnicodeScript.HAN || script == UnicodeScript.HANGUL
          || script == UnicodeScript.HIRAGANA || script == UnicodeScript.KATAKANA
          || script == UnicodeScript.THAI;
    })) return false;
    return line.split(" ").length < 5 && !line.matches(".*[.?!:]$");
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
