package ai.aeiou.forwarder.router;

import java.util.Locale;
import java.util.regex.Pattern;

/** Last-resort routing when the LLM is unavailable: a request-looking customer mail is treated as an RFQ. */
final class KeywordFallback {

    private static final Pattern WEIGHT_OR_VOLUME = Pattern.compile("(?i)\\b\\d[\\d.,]*\\s*(kg|kgs|kilos?|cbm|m3|m³)\\b");
    private static final Pattern REQUEST = Pattern.compile("(?i)\\b(quote|quotation|rate|offer|price|angebot|preis|anfrage|rfq)\\b");
    private static final Pattern IATA_PAIR = Pattern.compile("\\b[A-Z]{3}\\b.{0,40}\\b(to|nach|→|->|-)\\b.{0,10}\\b[A-Z]{3}\\b");

    private KeywordFallback() {}

    static MailType guess(String subject, String body) {
        String text = (subject == null ? "" : subject) + "\n" + (body == null ? "" : body);
        boolean shipment = WEIGHT_OR_VOLUME.matcher(text).find() || IATA_PAIR.matcher(text).find();
        boolean request = REQUEST.matcher(text.toLowerCase(Locale.ROOT)).find();
        return shipment && request ? MailType.NEW_RFQ : MailType.OTHER;
    }
}
