package ai.aeiou.forwarder.writer;

import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import ai.aeiou.forwarder.writer.EmailWriter.Purpose;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a generated mail against the hard facts before it may leave the house. Every error message is phrased so it
 * can be fed back to the model as a correction.
 */
final class MailValidator {

    private static final Pattern PLACEHOLDER = Pattern.compile("[\\[\\]{}]|<[A-Za-z _]+>|\\bTBD\\b|\\bXXX\\b");

    /** Phrases that make a mail read like a chatbot. Lower-case, matched case-insensitively. */
    static final List<String> AI_TELLS = List.of(
            "hope this email finds you", "hope this message finds you", "hope you're doing well", "hope you are doing well",
            "don't hesitate", "do not hesitate", "certainly!", "i'd be happy to", "i would be happy to",
            "i'd be delighted", "delve", "rest assured", "as an ai", "i hope this helps", "thrilled",
            "zögern sie nicht", "ich hoffe, diese e-mail", "ich hoffe, es geht ihnen gut", "gerne stehe ich ihnen",
            "ich freue mich sehr, ihnen");

    private MailValidator() {}

    static List<String> validate(MailIntent intent, String subject, String body, boolean freshMail) {
        List<String> errors = new ArrayList<>();
        if (body == null || body.isBlank()) {
            errors.add("The body is empty.");
            return errors;
        }
        if (freshMail && (subject == null || subject.isBlank())) {
            errors.add("A subject line is required for this new mail.");
        }
        for (String literal : nullSafe(intent.mustContain())) {
            if (!body.contains(literal)) {
                errors.add("The text \"" + literal + "\" must appear exactly like this, character for character.");
            }
        }
        Matcher placeholder = PLACEHOLDER.matcher(body);
        if (placeholder.find()) {
            errors.add("Remove the placeholder or bracket \"" + placeholder.group() + "\"; write the real text or leave it out.");
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String tell : AI_TELLS) {
            if (lower.contains(tell)) errors.add("Drop the stock phrase \"" + tell + "\".");
        }
        if (lower.stripLeading().startsWith("subject:") || lower.stripLeading().startsWith("betreff:")) {
            errors.add("Do not repeat the subject line in the body.");
        }
        if (intent.purpose() == Purpose.QUOTE || intent.purpose() == Purpose.BOOKING_CONFIRMATION) {
            errors.addAll(checkSinglePrice(intent.facts(), body));
        }
        return errors;
    }

    /** Every money amount in the mail must be the one total, in its currency: no options, ranges or breakdowns. */
    private static List<String> checkSinglePrice(Map<String, Object> facts, String body) {
        if (facts == null || facts.get(Facts.TOTAL) == null) return List.of();
        Optional<BigDecimal> total = Money.parse(String.valueOf(facts.get(Facts.TOTAL)));
        if (total.isEmpty()) return List.of();
        String currency = facts.get(Facts.CURRENCY) == null ? null : canonicalCurrency(String.valueOf(facts.get(Facts.CURRENCY)));
        List<String> errors = new ArrayList<>();
        Matcher m = Money.AMOUNT.matcher(body);
        while (m.find()) {
            String cur = firstNonNull(m.group(1), m.group(4), m.group(6));
            String num = firstNonNull(m.group(2), m.group(3), m.group(5));
            Optional<BigDecimal> amount = Money.parse(num);
            if (amount.isPresent() && amount.get().compareTo(total.get()) != 0) {
                errors.add("Only state the one all-in total; remove the other amount \"" + m.group().trim() + "\".");
            } else if (currency != null && cur != null && !currency.equals(canonicalCurrency(cur))) {
                errors.add("The total is in " + currency + ", not " + cur + ".");
            }
        }
        return errors;
    }

    static String canonicalCurrency(String raw) {
        String c = raw.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "€", "EURO", "EUROS" -> "EUR";
            case "$", "DOLLAR", "DOLLARS" -> "USD";
            case "£" -> "GBP";
            default -> c;
        };
    }

    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null) return v;
        return null;
    }

    private static List<String> nullSafe(List<String> list) {
        return list == null ? List.of() : list;
    }
}
