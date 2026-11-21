package ai.aeiou.forwarder.writer;

import ai.aeiou.forwarder.mail.StoredMail;
import java.util.List;
import java.util.Map;

/**
 * Writes EVERY outgoing mail body so it reads like a real person on our desk (ARCHITECTURE.md §15).
 * Hard facts in {@code facts} must appear unchanged; implementations verify them and fall back to a template.
 */
public interface EmailWriter {

    WrittenMail write(MailIntent intent);

    enum Purpose { QUOTE, BOOKING_CONFIRMATION, TRACKING_ANSWER, BID_REQUEST, TRUCK_REQUEST, RATEDESK_REQUEST, FOLLOW_UP_ANSWER, BOOKING_REQUEST, HOLDING_REPLY }

    /**
     * @param thread       conversation so far, oldest first (may be empty for a fresh mail)
     * @param facts        ordered facts to state, e.g. {"total":"1,387.35","currency":"EUR","airline":"Helix Air Freight","transitDays":4}
     * @param mustContain  literal strings that must appear verbatim (e.g. "EUR 1,387.35", a booking reference)
     * @param language     ISO language to write in (null = English)
     * @param recipientName first name / name to greet, if known
     */
    record MailIntent(Long sessionId, Purpose purpose, List<StoredMail> thread, Map<String, Object> facts,
                      List<String> mustContain, String language, String recipientName, String extraInstructions) {}

    record WrittenMail(String subject, String body, boolean fromTemplate) {}
}
