package ai.aeiou.edgelab.alarm;

import ai.aeiou.edgelab.alarm.Alert.Topic;
import ai.aeiou.edgelab.alarm.Snapshot.Attachment;
import ai.aeiou.edgelab.alarm.Snapshot.BoardRow;
import ai.aeiou.edgelab.alarm.Snapshot.MailRow;
import ai.aeiou.edgelab.alarm.Snapshot.WaveCaseRow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The alarm's brain: a pure function from (snapshot, memory) to (alerts, new memory).
 * No database, no clock, no pop-up. Every new thing has a key; an alert fires only when
 * one of its keys is not in memory yet, and all its keys are remembered afterwards.
 * Candidates are walked in time order, so the first mail of a new kind is the example.
 */
public final class ChangeDetector {

    /** A request without a quote, or a mail in review, alerts once it is this old. */
    public static final Duration STUCK_AFTER = Duration.ofMinutes(4);

    /** A request belongs to the round whose sent_at is at most this much after it. */
    private static final Duration ROUND_SLACK = Duration.ofSeconds(5);

    public record Decision(List<Alert> alerts, Memory memory) {}

    private ChangeDetector() {}

    public static Decision decide(Snapshot snap, Memory memory) {
        Context ctx = new Context(snap);
        List<Candidate> candidates = new ArrayList<>();
        snap.board().forEach(b -> boardCandidates(b, ctx, candidates));
        snap.mail().forEach(m -> mailCandidates(m, ctx, snap.now(), candidates));
        snap.waveCases().forEach(w -> waveCandidates(w, ctx, candidates));
        candidates.sort(Comparator.comparing(Candidate::at));

        Set<String> seen = new TreeSet<>(memory.seen());
        List<Alert> alerts = new ArrayList<>();
        for (Candidate c : candidates) {
            Set<String> fresh = new LinkedHashSet<>(c.keys());
            fresh.removeAll(seen);
            if (fresh.isEmpty()) continue;
            seen.addAll(c.keys());
            if (memory.initialised()) alerts.add(c.alert().apply(fresh));
        }
        return new Decision(List.copyOf(alerts), new Memory(true, seen));
    }

    private record Candidate(Instant at, List<String> keys, Function<Set<String>, Alert> alert) {
        Candidate(Instant at, String key, Alert alert) {
            this(at, List.of(key), fresh -> alert);
        }
    }

    // ---- board_result -------------------------------------------------------

    private static void boardCandidates(BoardRow b, Context ctx, List<Candidate> out) {
        Instant at = Snapshot.known(b);
        int round = b.roundNo();
        MailRow req = ctx.requestOf(round);
        Long mailId = req == null ? null : req.id();
        String subject = req == null ? null : req.subject();
        String verdict = b.verdict() == null ? "" : b.verdict();

        if (!verdict.isEmpty()) {
            out.add(new Candidate(at, "verdict:" + verdict, new Alert(Topic.NEW_VERDICT, at,
                    "New verdict: " + verdict + " (round " + round + ")", round, mailId, subject, "-",
                    "First board result with verdict '" + verdict + "'.")));
        }
        if (b.reason() != null && !b.reason().isBlank()) {
            out.add(new Candidate(at, "reason:" + b.reason(), new Alert(Topic.REJECTION_REASON, at,
                    "New rejection reason: " + b.reason() + " (round " + round + ")", round, mailId, subject,
                    "- (a rule, not a file type: check the bot's checks)",
                    "Verdict '" + verdict + "' with reason code '" + b.reason() + "', never seen before.")));
        }
        Double adjustment = b.points().get("adjustment");
        if (adjustment != null && adjustment != 0.0) {
            out.add(new Candidate(at, "deduction:" + round, new Alert(Topic.DEDUCTION, at,
                    "Point deduction " + fmt(adjustment) + " in round " + round, round, mailId, subject,
                    "- (penalty rule: compare with the brief)",
                    "Board points " + b.points() + ", verdict '" + verdict + "'.")));
        }
        if (verdict.equalsIgnoreCase("no answer")) {
            out.add(new Candidate(at, "noanswer:" + round, new Alert(Topic.NO_ANSWER, at,
                    "No answer in round " + round, round, mailId, subject,
                    readerFor(req), "Board verdict 'no answer': the bot did not reply in time.")));
        }
    }

    // ---- mail ---------------------------------------------------------------

    private static void mailCandidates(MailRow m, Context ctx, Instant now, List<Candidate> out) {
        if (m.received() == null) return;
        Instant at = m.received();
        Integer round = ctx.roundOfMail(m);
        String rnd = round == null ? "" : " (round " + round + ")";
        String kind = m.kind() == null ? "" : m.kind();
        String stage = m.stage() == null ? "" : m.stage();

        if (!kind.isEmpty()) {
            out.add(new Candidate(at, "kind:" + kind, new Alert(Topic.NEW_KIND, at,
                    "New mail kind: " + kind + rnd, round, m.id(), m.subject(), "-",
                    "First mail of kind '" + kind + "' from " + m.sender() + ", stage '" + stage + "'.")));
        }
        if (m.sender() != null && !m.sender().isBlank()) {
            out.add(new Candidate(at, "sender:" + m.sender().toLowerCase(), new Alert(Topic.NEW_SENDER, at,
                    "New sender: " + m.sender() + rnd, round, m.id(), m.subject(), "-",
                    "First mail from " + m.sender() + " (kind '" + kind + "', stage '" + stage + "').")));
        }
        for (Attachment a : m.attachments()) {
            String type = typeOf(a);
            String reader = Readers.suggest(a.extension(), a.contentType());
            out.add(new Candidate(at, "attachment:" + type, new Alert(Topic.ATTACHMENT_TYPE, at,
                    "New attachment type: " + type + rnd, round, m.id(), m.subject(), reader,
                    "File '" + a.filename() + "' (" + a.contentType() + ") in a " + kind + " mail from "
                            + m.sender() + ", stage '" + stage + "'.")));
        }
        if (stage.toLowerCase().contains("unreadable")) {
            out.add(unreadable(m, at, round, rnd, kind, stage));
        }
        if (stage.equalsIgnoreCase("expired")) {
            Integer r = round;
            String key = r == null ? "expired:" + m.id() : "noanswer:" + r;
            out.add(new Candidate(at, key, new Alert(Topic.NO_ANSWER, at,
                    "Request expired" + rnd + ": mail #" + m.id(), round, m.id(), m.subject(), readerFor(m),
                    "The " + kind + " from " + m.sender() + " ended in stage 'expired' without a reply.")));
        }
        boolean waitingRequest = kind.equals("request") && !m.hasProposal()
                && !stage.equalsIgnoreCase("sent") && !stage.equalsIgnoreCase("expired");
        boolean inReview = stage.equalsIgnoreCase("review");
        Instant due = at.plus(STUCK_AFTER);
        if ((waitingRequest || inReview) && !now.isBefore(due)) {
            long minutes = Duration.between(at, now).toMinutes();
            String why = inReview ? "in review" : "no quote";
            out.add(new Candidate(due, "stuck:" + m.id(), new Alert(Topic.STUCK, due,
                    "Mail #" + m.id() + " " + why + " after " + minutes + " min" + rnd, round, m.id(), m.subject(),
                    readerFor(m), "A " + kind + " from " + m.sender() + " is in stage '" + stage
                            + "' with no quote, " + minutes + " minutes after it arrived. Step in before 6 min.")));
        }
    }

    private static Candidate unreadable(MailRow m, Instant at, Integer round, String rnd, String kind, String stage) {
        List<String> types = m.attachments().isEmpty()
                ? List.of("body text")
                : m.attachments().stream().map(a -> a.extension().isEmpty() ? a.contentType() : a.extension()).toList();
        String sender = m.sender() == null ? "?" : m.sender().toLowerCase();
        List<String> keys = new ArrayList<>();
        types.forEach(t -> keys.add("unreadable-type:" + t));
        keys.add("unreadable-sender:" + sender);
        String reader = m.attachments().isEmpty()
                ? "- (no attachment: the bid text itself was not understood)"
                : Readers.suggest(m.attachments().get(0).extension(), m.attachments().get(0).contentType());
        String what = String.join("+", types);
        return new Candidate(at, keys, fresh -> {
            boolean newType = fresh.stream().anyMatch(k -> k.startsWith("unreadable-type:"));
            String because = newType ? "new type" : "new sender";
            return new Alert(Topic.UNREADABLE, at,
                    "Unreadable " + what + " from " + m.sender() + rnd + " - " + because,
                    round, m.id(), m.subject(), reader,
                    "Stage '" + stage + "' on a " + kind + " mail. Files: " + files(m) + ". New here: " + fresh + ".");
        });
    }

    // ---- wave_case ----------------------------------------------------------

    private static void waveCandidates(WaveCaseRow w, Context ctx, List<Candidate> out) {
        if (w.reason() == null || w.reason().isBlank()) return;
        MailRow req = ctx.mailById.get(w.id());
        Instant at = w.opened() != null ? Instant.ofEpochSecond(w.opened())
                : req != null ? req.received() : Instant.EPOCH;
        Integer round = req == null ? null : ctx.roundOfMail(req);
        String rnd = round == null ? "" : " (round " + round + ")";
        out.add(new Candidate(at, "hold:" + w.reason(), new Alert(Topic.HOLD_REASON, at,
                "New hold reason" + rnd + ": " + w.reason(), round, w.id(), req == null ? null : req.subject(),
                "-", "The bot put request #" + w.id() + " on hold: " + w.reason())));
    }

    // ---- helpers ------------------------------------------------------------

    static String typeOf(Attachment a) {
        String ext = a.extension().isEmpty() ? "(no ending)" : "." + a.extension();
        return ext + " " + (a.contentType() == null ? "(no content type)" : a.contentType());
    }

    private static String files(MailRow m) {
        if (m.attachments().isEmpty()) return "none";
        return String.join(", ", m.attachments().stream().map(a -> a.filename() + " (" + a.contentType() + ")").toList());
    }

    private static String readerFor(MailRow m) {
        if (m == null || m.attachments().isEmpty()) return "- (no attachment: check the bot)";
        Attachment a = m.attachments().get(0);
        return Readers.suggest(a.extension(), a.contentType()) + " for " + a.filename();
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.format("%.0f", d) : String.valueOf(d);
    }

    /** Lookups shared by all candidates of one snapshot. */
    private static final class Context {
        final Map<Long, MailRow> mailById = new HashMap<>();
        final List<BoardRow> boardBySent;

        Context(Snapshot s) {
            s.mail().forEach(m -> mailById.put(m.id(), m));
            boardBySent = s.board().stream().filter(b -> b.sentAt() != null)
                    .sorted(Comparator.comparing(BoardRow::sentAt)).toList();
        }

        /** The round a mail belongs to: by its thread's root mail, the latest round sent before it. */
        Integer roundOfMail(MailRow m) {
            MailRow root = m.root() == null ? m : mailById.getOrDefault(m.root(), m);
            Instant t = root.received() == null ? m.received() : root.received();
            if (t == null) return null;
            Integer round = null;
            for (BoardRow b : boardBySent) {
                if (b.sentAt().isAfter(t.plus(ROUND_SLACK))) break;
                round = b.roundNo();
            }
            return round;
        }

        /** The first customer request of a round, or null. */
        MailRow requestOf(int round) {
            if (requestByRound == null) {
                requestByRound = new HashMap<>();
                for (MailRow m : mailById.values()) {
                    if (!"request".equals(m.kind()) || m.received() == null) continue;
                    Integer r = roundOfMail(m);
                    if (r == null) continue;
                    requestByRound.merge(r, m, (a, b) -> a.received().isBefore(b.received()) ? a : b);
                }
            }
            return requestByRound.get(round);
        }

        private Map<Integer, MailRow> requestByRound;
    }
}
