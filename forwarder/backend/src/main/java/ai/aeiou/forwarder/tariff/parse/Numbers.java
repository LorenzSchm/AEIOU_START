package ai.aeiou.forwarder.tariff.parse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Locale-tolerant number and date parsing for tariff sheets ("3,87", "EUR 1,234.50", "01.11.2026", "1 November 2026"). */
public final class Numbers {

    /** A number with optional decimal or thousands separators; not glued to letters or other digits. */
    public static final String NUM = "\\d+(?:[.,]\\d+)*";
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z\\d.,])" + NUM + "(?![A-Za-z\\d])");
    private static final Pattern THOUSANDS_ONLY = Pattern.compile("\\d{1,3}(?:,\\d{3})+");

    private static final List<Pattern> DATE_PATTERNS = List.of(
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}"),
            Pattern.compile("\\d{1,2}[./]\\d{1,2}[./]\\d{4}"),
            Pattern.compile("\\d{1,2}\\.? [A-Za-z]+ \\d{4}"),
            Pattern.compile("[A-Za-z]+ \\d{1,2},? \\d{4}"));
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d.M.yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH));

    private Numbers() {}

    /**
     * Parses one number token. With both separators the last one is the decimal mark; a lone comma is a
     * decimal comma ("3,87") unless it groups thousands ("1,250").
     */
    public static BigDecimal parse(String token) {
        String t = token.trim();
        int comma = t.lastIndexOf(',');
        int dot = t.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            t = comma > dot ? t.replace(".", "").replace(',', '.') : t.replace(",", "");
        } else if (comma >= 0) {
            t = THOUSANDS_ONLY.matcher(t).matches() ? t.replace(",", "") : t.replace(',', '.');
        }
        return new BigDecimal(t);
    }

    /** All numbers in reading order. */
    public static List<BigDecimal> all(String text) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) out.add(parse(m.group()));
        return out;
    }

    public static boolean containsNumber(String text) {
        return NUMBER.matcher(text).find();
    }

    /** The first date found in the text, in any of the formats the kit uses. */
    public static Optional<LocalDate> date(String text) {
        List<LocalDate> dates = dates(text);
        return dates.isEmpty() ? Optional.empty() : Optional.of(dates.getFirst());
    }

    /** All dates in the text, in reading order. */
    public static List<LocalDate> dates(String text) {
        TreeMap<Integer, LocalDate> found = new TreeMap<>();
        for (Pattern p : DATE_PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (found.containsKey(m.start())) continue;
                parseDate(m.group()).ifPresent(d -> found.put(m.start(), d));
            }
        }
        return List.copyOf(found.values());
    }

    private static Optional<LocalDate> parseDate(String token) {
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(token, f));
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return Optional.empty();
    }
}
