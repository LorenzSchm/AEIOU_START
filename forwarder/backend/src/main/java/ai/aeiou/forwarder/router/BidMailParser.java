package ai.aeiou.forwarder.router;

import ai.aeiou.forwarder.writer.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads carrier and rate-desk answers without an LLM. They follow a stable pattern, e.g.
 * "Our all-in air freight rate from FRA to ASU is 529.00 EUR, valid until 21 Nov 2026 15:02 UTC. Estimated transit
 * time: 6 days. Please quote R-6uQituvqTpVTuHy0 when booking." Pure, no I/O.
 *
 * <p>Returns a bid only when the reading is unambiguous: one distinct price (with transit days when the mail talks
 * about transit), or a clear decline. Anything else returns empty and the router asks the LLM.
 */
final class BidMailParser {

    private static final Pattern TRANSIT = Pattern.compile(
            "(?i)transit(?:\\s+time)?(?:\\s+(?:of|is|will be|approx\\.?|about|around))*\\s*:?\\s*(\\d{1,2})\\s*(?:calendar\\s+)?days?");
    private static final Pattern DAYS_TRANSIT = Pattern.compile("(?i)(\\d{1,2})\\s*days?\\s+(?:of\\s+)?transit");
    private static final Pattern RATE_ID = Pattern.compile("\\b(R-[A-Za-z0-9_-]{8,40})\\b");
    private static final Pattern OFFER_REF = Pattern.compile("\\b(TRK-[A-Za-z0-9_-]{1,40})\\b");
    private static final Pattern VALID_UNTIL = Pattern.compile(
            "(?i)valid\\s+(?:until|till|through)\\s+(\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4}(?:\\s+\\d{1,2}:\\d{2})?|\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2})?)");
    private static final Pattern DECLINE = Pattern.compile(
            "(?i)\\b(cannot|can ?not|can't|are unable to|unable to|not able to|won't be able to)\\s+(offer|quote|accept|serve|"
                    + "provide|handle|support|carry|fly)\\b|\\bno (capacity|space|service)\\b|\\b(must|have to) decline\\b|"
                    + "\\bwe do not (serve|fly|operate)\\b|\\bnot (served|available) on this (lane|route)\\b");

    private BidMailParser() {}

    static Optional<Extraction.Bid> parse(String subject, String body) {
        String text = withoutQuotedReply(body == null ? "" : body);
        List<Money.Amount> amounts = Money.amounts(text);
        List<BigDecimal> distinct = amounts.stream().map(a -> a.value().stripTrailingZeros()).distinct().toList();
        String rateId = find(RATE_ID, text);
        String offerRef = find(OFFER_REF, text);
        String notes = offerRef == null ? null : "offer reference " + offerRef;

        if (amounts.isEmpty()) {
            if (DECLINE.matcher(text).find()) {
                return Optional.of(new Extraction.Bid(null, null, null, null, null, true, firstSentence(text), null));
            }
            return Optional.empty();
        }
        if (distinct.size() != 1) return Optional.empty();
        Money.Amount price = amounts.getFirst();
        Integer transit = transitDays(text);
        boolean talksTransit = text.toLowerCase(Locale.ROOT).contains("transit");
        if (transit == null && talksTransit) return Optional.empty();
        return Optional.of(new Extraction.Bid(price.value(), price.currency(), transit, validUntil(text), null, false,
                notes, rateId));
    }

    /** Drops the quoted earlier mail ("> ..." lines, "On ... wrote:" and below) so only the new answer is read. */
    static String withoutQuotedReply(String body) {
        StringBuilder kept = new StringBuilder();
        for (String line : body.split("\\R")) {
            String t = line.trim();
            if (t.startsWith(">")) continue;
            if (t.matches("(?i)^(on .+ wrote:|-{2,}\\s*original message\\s*-{2,}|from: .+@.+)$")) break;
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    static Integer transitDays(String text) {
        String days = find(TRANSIT, text);
        if (days == null) days = find(DAYS_TRANSIT, text);
        return days == null ? null : Integer.valueOf(days);
    }

    /** ISO date-time (UTC) or date, or null. */
    static String validUntil(String text) {
        String raw = find(VALID_UNTIL, text);
        if (raw == null) return null;
        String s = raw.trim().replaceAll("\\s+", " ");
        for (String pattern : List.of("d MMM yyyy HH:mm", "d MMMM yyyy HH:mm")) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).atOffset(ZoneOffset.UTC).toString();
            } catch (DateTimeParseException ignored) {
                // next pattern
            }
        }
        for (String pattern : List.of("d MMM yyyy", "d MMMM yyyy", "yyyy-MM-dd")) {
            try {
                return LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).toString();
            } catch (DateTimeParseException ignored) {
                // next pattern
            }
        }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).atOffset(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String find(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String firstSentence(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        int end = flat.indexOf(". ");
        String s = end > 0 ? flat.substring(0, end + 1) : flat;
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
