package ai.aeiou.edgelab.alarm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import ai.aeiou.edgelab.alarm.ChangeDetector.Decision;
import ai.aeiou.edgelab.alarm.Snapshot.Attachment;
import ai.aeiou.edgelab.alarm.Snapshot.MailRow;

/** An attachment without content type (and without file ending) reads "(unknown)", never "null". */
class MissingContentTypeTest {

    @Test
    void nullContentTypeShowsAsUnknown() {
        Instant at = Instant.parse("2026-11-21T10:00:00Z");
        MailRow mail = new MailRow(1L, null, "Someone@Example.com", "rfq", at, "request", "unreadable",
                List.of(new Attachment("scan", null)), false);
        Snapshot snap = new Snapshot(at.plusSeconds(10), List.of(), List.of(mail), List.of());

        Decision d = ChangeDetector.decide(snap, Memory.empty());

        assertFalse(d.alerts().isEmpty());
        for (Alert a : d.alerts()) {
            assertFalse(a.line().contains("null"), a.line());
            assertFalse(a.detail().contains("null"), a.detail());
        }
        assertTrue(d.memory().seen().contains("unreadable-type:(unknown)"), d.memory().seen().toString());
        assertTrue(d.memory().seen().contains("sender:someone@example.com"), d.memory().seen().toString());
    }
}
