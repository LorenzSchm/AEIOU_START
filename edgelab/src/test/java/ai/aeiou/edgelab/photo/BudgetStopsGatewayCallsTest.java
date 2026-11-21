package ai.aeiou.edgelab.photo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.Router;
import ai.aeiou.edgelab.gateway.Gateway;
import ai.aeiou.edgelab.office.OfficeReader;

/**
 * After the time budget runs out, the reading thread must stop calling the gateway
 * (each call costs points), not keep going through the remaining pages in the background.
 */
class BudgetStopsGatewayCallsTest {

    /** Slow; shrugs off interrupts like a real HTTP call that already went out. */
    static final class SlowGateway implements Gateway {
        final AtomicInteger calls = new AtomicInteger();

        @Override public Answer transcribe(byte[] audio, String format) { throw new AssertionError("no audio here"); }

        @Override public Answer copyText(byte[] image, String mimeType) {
            calls.incrementAndGet();
            long until = System.nanoTime() + Duration.ofMillis(250).toNanos();
            while (System.nanoTime() < until) {
                try {
                    Thread.sleep(Math.max(1, (until - System.nanoTime()) / 1_000_000));
                } catch (InterruptedException e) {
                    // swallowed on purpose: the worst case for the reader
                }
            }
            return new Answer("page text 340 kg MUC ORD", 0.9, "slow-fake", null);
        }
    }

    @Test
    void noGatewayCallsAfterTheDeadline() throws Exception {
        int pages = 8;
        byte[] pdf = PhotoFixtures.scannedPdf(pages);
        SlowGateway gw = new SlowGateway();
        Router router = Router.empty().withOffice(new ScanAwareOfficeReader(new OfficeReader(), new PhotoReader(gw)));
        EdgeReader reader = new EdgeReader(router, EdgeReader.DEFAULT_MAX_BYTES, Duration.ofMillis(600));

        Reading r = reader.read("many-scans.pdf", pdf);
        int atReturn = gw.calls.get();
        Thread.sleep(2500); // long enough for the remaining pages, were they still being sent

        assertEquals(Reading.Status.PARTIAL, r.status(), r.notes().toString());
        int later = gw.calls.get();
        assertTrue(later <= atReturn + 1, "calls went on after the deadline: " + atReturn + " -> " + later);
        assertTrue(later < pages, "all " + pages + " pages were still sent");
    }
}
