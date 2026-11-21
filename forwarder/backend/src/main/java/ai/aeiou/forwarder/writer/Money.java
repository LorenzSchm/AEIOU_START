package ai.aeiou.forwarder.writer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats, parses and finds money amounts the way they appear in mails ("EUR 1,387.35", "1.387,35 €"). The one
 * place that formats quoted amounts, so writer facts, body checks and templates agree.
 */
public final class Money {

    private static final String CURRENCY = "(EUR|USD|GBP|CHF|€|\\$|£)";
    private static final String NUMBER = "(\\d[\\d.,']*\\d|\\d)";
    /**
     * Any amount with a currency next to it: "EUR 1,387.35", "1.387,35 €", "$980", "980 USD", "980 euros".
     * Groups: currency 1, 4 or 6; number 2, 3 or 5.
     */
    static final Pattern AMOUNT = Pattern.compile(
            "(?i)" + CURRENCY + "\\s?" + NUMBER + "|" + NUMBER + "\\s?" + CURRENCY + "|" + NUMBER + "\\s?(euros?\\b|dollars?\\b)");

    private Money() {}

    /** Rounds to cents, half up. */
    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** "1,387.35": the canonical display form of an amount in every mail we write. */
    public static String amount(BigDecimal value) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(round(value));
    }

    /** "EUR 1,387.35": the literal that must appear verbatim in quotes and confirmations. */
    public static String format(BigDecimal value, String currency) {
        return currency.toUpperCase(Locale.ROOT) + " " + amount(value);
    }

    /**
     * Parses a number written with either English or German separators. A single separator followed by exactly
     * three digits is a thousands separator ("1,387" = 1387), otherwise it is the decimal point.
     */
    public static Optional<BigDecimal> parse(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.trim().replace("'", "").replace(" ", "").replace(" ", "");
        if (s.isEmpty()) return Optional.empty();
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        String normalized;
        if (lastDot >= 0 && lastComma >= 0) {
            char decimal = lastDot > lastComma ? '.' : ',';
            char grouping = decimal == '.' ? ',' : '.';
            normalized = s.replace(String.valueOf(grouping), "").replace(decimal, '.');
        } else if (lastDot >= 0 || lastComma >= 0) {
            char sep = lastDot >= 0 ? '.' : ',';
            int last = Math.max(lastDot, lastComma);
            boolean grouping = s.length() - last - 1 == 3 && s.indexOf(sep) == last || countOf(s, sep) > 1;
            normalized = grouping ? s.replace(String.valueOf(sep), "") : s.replace(sep, '.');
        } else {
            normalized = s;
        }
        try {
            return Optional.of(new BigDecimal(normalized));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** One amount found in a text, with its ISO currency. */
    public record Amount(BigDecimal value, String currency) {}

    /** Every money amount (number + currency) a text states, in order. */
    public static java.util.List<Amount> amounts(String text) {
        java.util.List<Amount> found = new java.util.ArrayList<>();
        if (text == null) return found;
        Matcher m = AMOUNT.matcher(text);
        while (m.find()) {
            String number = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(5);
            String cur = m.group(1) != null ? m.group(1) : m.group(4) != null ? m.group(4) : m.group(6);
            Optional<BigDecimal> value = parse(number);
            if (value.isPresent()) found.add(new Amount(value.get(), isoCurrency(cur)));
        }
        return found;
    }

    private static String isoCurrency(String raw) {
        String c = raw.trim().toLowerCase(Locale.ROOT);
        return switch (c) {
            case "€", "euro", "euros" -> "EUR";
            case "$", "dollar", "dollars" -> "USD";
            case "£" -> "GBP";
            default -> c.toUpperCase(Locale.ROOT);
        };
    }

    /** How many money amounts (number + currency) a text states. */
    public static int countAmounts(String text) {
        if (text == null) return 0;
        Matcher m = AMOUNT.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** True when {@code body} states exactly one amount and it is {@code label} (see {@link #format}) verbatim. */
    public static boolean statesExactlyOneTotal(String body, String label) {
        if (body == null) return false;
        int occurrences = body.split(Pattern.quote(label), -1).length - 1;
        return occurrences == 1 && countAmounts(body) == 1;
    }

    private static int countOf(String s, char c) {
        return (int) s.chars().filter(ch -> ch == c).count();
    }
}
