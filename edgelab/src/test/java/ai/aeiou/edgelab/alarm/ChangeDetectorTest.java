package ai.aeiou.edgelab.alarm;

import ai.aeiou.edgelab.alarm.Alert.Topic;
import ai.aeiou.edgelab.alarm.ChangeDetector.Decision;
import ai.aeiou.edgelab.alarm.Snapshot.BoardRow;
import ai.aeiou.edgelab.alarm.Snapshot.MailRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decide function on real history: an anonymised snapshot of the shared DB from
 * 2026-11-21 (no mail bodies), replayed as the alarm would have seen it poll by poll.
 */
class ChangeDetectorTest {

    static Snapshot history;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = ChangeDetectorTest.class.getResourceAsStream("/alarm/history-2026-11-21.json")) {
            history = Json.MAPPER.readValue(in, Snapshot.class);
        }
    }

    // ---- baseline ---------------------------------------------------------------

    @Test
    void firstRunWithoutMemoryLearnsTheBaselineAndSendsNoAlerts() {
        Decision d = ChangeDetector.decide(history.asOf(start(10).minusSeconds(1)), Memory.none());

        assertTrue(d.alerts().isEmpty());
        assertTrue(d.memory().initialised());
        assertTrue(d.memory().seen().containsAll(Set.of("reason:too_high", "kind:carrier", "kind:request",
                "verdict:accepted", "unreadable-type:body text")));
        assertFalse(d.memory().seen().contains("reason:dg_docs_missing"));
    }

    // ---- the five real rounds -------------------------------------------------------

    @Test
    void round10RaisesTheNewDgRuleAndTheFirstXlsx() {
        List<Alert> alerts = between(start(10).minusSeconds(1), end(10));

        Alert reason = only(alerts, Topic.REJECTION_REASON);
        assertEquals("New rejection reason: dg_docs_missing (round 10)", reason.line());
        assertEquals(10, reason.round());
        assertEquals(2254L, reason.mailId());

        Alert xlsx = only(alerts, Topic.ATTACHMENT_TYPE);
        assertTrue(xlsx.line().startsWith("New attachment type: .xlsx"), xlsx.line());
        assertEquals("office reader (Tika)", xlsx.reader());
        assertEquals(2254L, xlsx.mailId());
    }

    @Test
    void round42RaisesTheBannedAirlineRule() {
        Alert a = only(between(start(42).minusSeconds(1), end(42)), Topic.REJECTION_REASON);
        assertEquals("New rejection reason: excluded_airline (round 42)", a.line());
        assertEquals(10818L, a.mailId());
    }

    @Test
    void round48RaisesTheDeductionTheSpamSenderAndItsExpiry() {
        List<Alert> alerts = between(start(48).minusSeconds(1), start(49).minusSeconds(1));

        assertEquals("Point deduction -32.5 in round 48", only(alerts, Topic.DEDUCTION).line());
        assertEquals("New sender: ceo-office-urgent@hackloop.mail (round 48)", only(alerts, Topic.NEW_SENDER).line());
        Alert expired = only(alerts, Topic.NO_ANSWER);
        assertEquals(13055L, expired.mailId());
        assertEquals(48, expired.round());
    }

    @Test
    void round63RaisesNoAnswerOnceAndTheUnreadableDocxBid() {
        List<Alert> alerts = between(start(63).minusSeconds(1), start(64).minusSeconds(1));

        Alert noAnswer = only(alerts, Topic.NO_ANSWER); // expired mail and board verdict share one key
        assertEquals(63, noAnswer.round());
        assertEquals(17231L, noAnswer.mailId());

        Alert docx = alerts.stream().filter(a -> a.topic() == Topic.UNREADABLE && a.mailId() == 17553L).findFirst()
                .orElseThrow();
        assertEquals("Unreadable docx from maintal-transport-logistik@hackloop.mail (round 63) - new type", docx.line());
        assertEquals("office reader (Tika)", docx.reader());
    }

    @Test
    void theFirstUnreadableMp3PointsToTheVoiceReader() {
        Alert mp3 = between(start(65).minusSeconds(1), start(67)).stream()
                .filter(a -> a.topic() == Topic.UNREADABLE).filter(a -> a.mailId() == 18221L).findFirst().orElseThrow();
        assertEquals("Unreadable mp3 from elbkante-logistik@hackloop.mail (round 65) - new type", mp3.line());
        assertEquals("voice reader", mp3.reader());
    }

    @Test
    void round69RaisesNoAnswerAndAnUnreadableMp3FromANewSender() {
        List<Alert> alerts = between(start(69).minusSeconds(1), start(70).minusSeconds(1));

        Alert noAnswer = only(alerts, Topic.NO_ANSWER);
        assertEquals(19201L, noAnswer.mailId());
        assertTrue(noAnswer.reader().startsWith("voice reader"), noAnswer.reader());

        Alert mp3 = only(alerts, Topic.UNREADABLE);
        assertEquals(19354L, mp3.mailId());
        assertTrue(mp3.line().endsWith("- new sender"), mp3.line());
        assertEquals("voice reader", mp3.reader());
    }

    // ---- once per new thing -----------------------------------------------------

    @Test
    void sameSnapshotTwiceAlertsOnlyTheFirstTime() {
        Memory baseline = ChangeDetector.decide(history.asOf(start(42).minusSeconds(1)), Memory.none()).memory();
        Snapshot later = history.asOf(end(42));

        Decision first = ChangeDetector.decide(later, baseline);
        Decision second = ChangeDetector.decide(later, first.memory());

        assertFalse(first.alerts().isEmpty());
        assertTrue(second.alerts().isEmpty());
        assertEquals(first.memory(), second.memory());
    }

    @Test
    void replayingTheDayEveryMinuteRaisesEachThingExactlyOnce() {
        Memory memory = ChangeDetector.decide(history.asOf(start(10).minusSeconds(1)), Memory.none()).memory();
        Set<String> lines = new HashSet<>();
        int reasons = 0;
        for (Instant t = start(10); t.isBefore(end(69)); t = t.plus(Duration.ofSeconds(60))) {
            Decision d = ChangeDetector.decide(history.asOf(t), memory);
            for (Alert a : d.alerts()) {
                String key = a.topic() + "|" + a.line().replaceAll("after \\d+ min", "");
                assertTrue(lines.add(key), "alerted twice: " + a.line());
                if (a.topic() == Topic.REJECTION_REASON) reasons++;
            }
            memory = d.memory();
        }
        // dg_docs_missing (10), too_low (27), excluded_airline (42); wrong_language comes in round 70
        assertEquals(3, reasons);
        assertTrue(lines.stream().anyMatch(l -> l.contains("Point deduction -32.5 in round 48")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("New attachment type: .mp3")));
    }

    @Test
    void restartWithTheSavedMemoryDoesNotRepeatAlerts(@TempDir Path dir) throws Exception {
        MemoryStore store = new MemoryStore(dir.resolve("alarm-memory.json"));
        assertFalse(store.load().initialised()); // no file yet = first run

        Snapshot snap = history.asOf(end(48));
        store.save(ChangeDetector.decide(snap, Memory.none()).memory(), snap.now());

        Memory loaded = store.load();
        assertTrue(loaded.initialised());
        assertTrue(ChangeDetector.decide(snap, loaded).alerts().isEmpty());
        assertFalse(ChangeDetector.decide(history.asOf(end(63)), loaded).alerts().isEmpty());
    }

    // ---- waiting too long -------------------------------------------------------

    @Test
    void aMailInReviewAlertsAfterFourMinutesOnce() {
        MailRow review = history.mail().stream().filter(m -> m.id() == 2528L).findFirst().orElseThrow();
        assertEquals("review", review.stage());
        Memory memory = ChangeDetector.decide(history.asOf(review.received().minusSeconds(1)), Memory.none()).memory();

        Decision at3 = ChangeDetector.decide(history.asOf(review.received().plus(Duration.ofMinutes(3))), memory);
        assertTrue(at3.alerts().stream().noneMatch(a -> a.topic() == Topic.STUCK));

        Decision at4 = ChangeDetector.decide(history.asOf(review.received().plus(Duration.ofMinutes(4))), at3.memory());
        Alert stuck = only(at4.alerts(), Topic.STUCK);
        assertEquals("Mail #2528 in review after 4 min (round 11)", stuck.line());

        Decision at5 = ChangeDetector.decide(history.asOf(review.received().plus(Duration.ofMinutes(5))), at4.memory());
        assertTrue(at5.alerts().stream().noneMatch(a -> a.topic() == Topic.STUCK));
    }

    @Test
    void aQuotedRequestNeverCountsAsStuck() {
        MailRow sent = history.mail().stream().filter(m -> m.id() == 10818L).findFirst().orElseThrow();
        Memory memory = ChangeDetector.decide(history.asOf(sent.received().minusSeconds(1)), Memory.none()).memory();
        List<Alert> alerts = ChangeDetector.decide(history.asOf(sent.received().plus(Duration.ofMinutes(5))), memory).alerts();
        assertTrue(alerts.stream().noneMatch(a -> a.topic() == Topic.STUCK && a.mailId() == 10818L));
    }

    // ---- output -----------------------------------------------------------------

    @Test
    void popUpIsOneLineAndTheLogEntryHasTopicRoundMailAndReader() {
        Alert mp3 = only(between(start(69).minusSeconds(1), start(70).minusSeconds(1)), Topic.UNREADABLE);

        assertFalse(mp3.line().contains("\n"));
        String entry = AlertLog.format(mp3, Instant.parse("2026-11-21T21:56:00Z"));
        assertTrue(entry.contains("UNREADABLE MAIL"), entry);
        assertTrue(entry.contains("round:   69"), entry);
        assertTrue(entry.contains("mail:    #19354 \"Re: Rate request AEIOU-19201"), entry);
        assertTrue(entry.contains("reader:  voice reader"), entry);
    }

    // ---- helpers ----------------------------------------------------------------

    /** Alerts of a poll at {@code to}, with memory learned from history up to {@code from}. */
    private static List<Alert> between(Instant from, Instant to) {
        Memory baseline = ChangeDetector.decide(history.asOf(from), Memory.none()).memory();
        return ChangeDetector.decide(history.asOf(to), baseline).alerts();
    }

    private static Alert only(List<Alert> alerts, Topic topic) {
        List<Alert> of = alerts.stream().filter(a -> a.topic() == topic).toList();
        assertEquals(1, of.size(), topic + " in " + alerts.stream().map(Alert::line).toList());
        return of.get(0);
    }

    private static BoardRow round(int n) {
        return history.board().stream().filter(b -> b.roundNo() == n).findFirst().orElseThrow();
    }

    private static Instant start(int n) {
        return round(n).sentAt();
    }

    private static Instant end(int n) {
        return Snapshot.known(round(n)).plusSeconds(1);
    }
}
