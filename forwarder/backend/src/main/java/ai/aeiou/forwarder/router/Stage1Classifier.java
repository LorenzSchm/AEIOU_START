package ai.aeiou.forwarder.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Router stage 1: decides the type from hard facts only (sender, subject, our own threads), with no I/O.
 * Ambiguous mails get no type, only hints for stage 2. See ARCHITECTURE.md §3.2.
 */
public final class Stage1Classifier {

    public static final String RATEDESK = "ratedesk@hackloop.mail";
    public static final String TRACKING = "tracking@hackloop.mail";

    private static final Pattern BOOKING_CONFIRMED =
            Pattern.compile("\\bbooking\\s+(BK-[A-Z0-9]{8})\\s+confirmed\\b", Pattern.CASE_INSENSITIVE);

    /** A known airline or trucker from the {@code carrier} table. */
    public record Carrier(long id, String name, String kind) {}

    /**
     * Everything stage 1 knows about a mail.
     *
     * @param carrier              sender as a known carrier, or null
     * @param bidRequestSessionId  session of our bid request this mail answers, or null
     * @param threadSessionId      session of a mail of ours in the same thread, or null
     * @param threadSessionType    type of that session (RFQ, TRACKING, ...), or null
     * @param bookingSessionId     session of the booking named in a confirmation subject, or null
     */
    public record Facts(String from, String subject, boolean hasAttachments, Carrier carrier,
                        Long bidRequestSessionId, Long threadSessionId, String threadSessionType,
                        Long bookingSessionId) {}

    /**
     * @param type       the decided type, or null when stage 2 must classify
     * @param needsStage2 whether the LLM must run (to classify, or only to extract data for a decided type)
     * @param hints      facts passed on to stage 2 and stored in the route decision
     */
    public record Result(MailType type, boolean needsStage2, List<String> hints) {}

    public Result classify(Facts f) {
        String from = f.from() == null ? "" : f.from().trim().toLowerCase();
        List<String> hints = new ArrayList<>();
        if (f.threadSessionId() != null) {
            hints.add("The mail continues one of our existing threads (session type " + f.threadSessionType() + ").");
        }

        if (from.equals(RATEDESK)) {
            hints.add("Sender is the 5U rate desk; it answers rate questions we asked.");
            return new Result(MailType.RATEDESK_REPLY, true, hints);
        }
        if (from.equals(TRACKING)) {
            hints.add("Sender is the tracking desk; it asks about the status of shipments.");
            return new Result(MailType.TRACKING_QUESTION, true, hints);
        }
        Optional<String> bookingRef = bookingReference(f.subject());
        if (bookingRef.isPresent()) {
            hints.add("Subject confirms booking " + bookingRef.get() + ".");
            return new Result(MailType.BOOKING_CONFIRMATION, false, hints);
        }
        if (f.carrier() != null) {
            hints.add("Sender is the known " + f.carrier().kind() + " '" + f.carrier().name() + "'.");
            if (f.bidRequestSessionId() != null) {
                hints.add("The mail replies to a bid request we sent to this carrier.");
                return new Result(MailType.CARRIER_BID, true, hints);
            }
            if (f.hasAttachments() && f.threadSessionId() == null) {
                hints.add("Unsolicited mail from a carrier with an attachment, like a monthly tariff.");
                return new Result(MailType.TARIFF, false, hints);
            }
            hints.add("It is not a reply to one of our bid requests.");
            return new Result(null, true, hints);
        }
        if (f.threadSessionId() == null) {
            hints.add("New thread from a sender who is not a known carrier (most likely a customer).");
        }
        return new Result(null, true, hints);
    }

    /** The {@code BK-xxxxxxxx} reference of a "Booking BK-xxxxxxxx confirmed" subject. */
    public static Optional<String> bookingReference(String subject) {
        if (subject == null) return Optional.empty();
        Matcher m = BOOKING_CONFIRMED.matcher(subject);
        return m.find() ? Optional.of(m.group(1).toUpperCase()) : Optional.empty();
    }
}
