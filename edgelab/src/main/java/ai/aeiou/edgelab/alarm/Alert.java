package ai.aeiou.edgelab.alarm;

import java.time.Instant;

/**
 * One thing worth telling Thure about.
 *
 * @param line   the one plain line for the pop-up
 * @param round  round number, or null when unknown
 * @param mailId example mail, or null
 * @param reader which edgelab reader fits (or what to look at), never null
 */
public record Alert(Topic topic, Instant at, String line, Integer round, Long mailId, String subject,
                    String reader, String detail) {

    public enum Topic {
        REJECTION_REASON("New rejection reason"),
        NEW_VERDICT("New verdict"),
        HOLD_REASON("New hold reason"),
        ATTACHMENT_TYPE("New attachment type"),
        UNREADABLE("Unreadable mail"),
        STUCK("Mail waiting too long"),
        NEW_KIND("New mail kind"),
        NEW_SENDER("New sender"),
        DEDUCTION("Point deduction"),
        NO_ANSWER("No answer / expired");

        public final String label;

        Topic(String label) {
            this.label = label;
        }
    }
}
