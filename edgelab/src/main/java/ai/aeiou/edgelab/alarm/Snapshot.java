package ai.aeiou.edgelab.alarm;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the alarm sees in one poll: board results, mails (no bodies) and wave cases,
 * plus the database clock at the time of the poll.
 */
public record Snapshot(Instant now, List<BoardRow> board, List<MailRow> mail, List<WaveCaseRow> waveCases) {

    public Snapshot {
        board = board == null ? List.of() : List.copyOf(board);
        mail = mail == null ? List.of() : List.copyOf(mail);
        waveCases = waveCases == null ? List.of() : List.copyOf(waveCases);
    }

    /** One row of board_result. {@code points} may contain "adjustment" for deductions. */
    public record BoardRow(int roundNo, Instant sentAt, Instant answeredAt, String verdict, String reason,
                           Map<String, Double> points) {
        public BoardRow {
            points = points == null ? Map.of() : Map.copyOf(points);
        }
    }

    /** One row of mail, without body, fields, proposal or booking contents. */
    public record MailRow(long id, Long root, String sender, String subject, Instant received, String kind,
                          String stage, List<Attachment> attachments, boolean hasProposal) {
        public MailRow {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    public record Attachment(String filename, String contentType) {
        /** Lower-case file ending without the dot, or "" when there is none. */
        public String extension() {
            if (filename == null) return "";
            int dot = filename.lastIndexOf('.');
            return dot < 0 || dot == filename.length() - 1 ? "" : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One row of wave_case. {@code id} is the request mail id; {@code opened} is epoch seconds. */
    public record WaveCaseRow(long id, String reason, Long opened) {}

    /**
     * History as it looked at {@code t}: only rows that existed then. Board rows count from
     * when their verdict was known. Used by tests to replay real history poll by poll.
     */
    public Snapshot asOf(Instant t) {
        return new Snapshot(t,
                board.stream().filter(b -> !known(b).isAfter(t)).toList(),
                mail.stream().filter(m -> m.received() != null && !m.received().isAfter(t)).toList(),
                waveCases.stream().filter(w -> w.opened() == null || !Instant.ofEpochSecond(w.opened()).isAfter(t)).toList());
    }

    /** A round's answer window; a result without answered_at (e.g. "no answer") is known when it closes. */
    static final java.time.Duration ROUND_WINDOW = java.time.Duration.ofMinutes(6);

    /** When a board result became known. */
    static Instant known(BoardRow b) {
        if (b.answeredAt() != null) return b.answeredAt();
        return b.sentAt() == null ? Instant.EPOCH : b.sentAt().plus(ROUND_WINDOW);
    }
}
