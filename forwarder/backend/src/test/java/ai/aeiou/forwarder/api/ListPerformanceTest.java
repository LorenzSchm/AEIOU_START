package ai.aeiou.forwarder.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Performance budget check (ARCHITECTURE.md §13: p95 < 50 ms per UI endpoint) on a realistic volume:
 * 5k sessions, 50k events, 10k mails. Prints warm timings; the assertion is lenient to stay stable on CI.
 */
class ListPerformanceTest extends ApiTestBase {

    private static final int WARMUP = 5;
    private static final int RUNS = 20;
    private static final long BUDGET_MS = 150;

    @Test
    void listEndpointsStayFastOnRealisticVolume() throws Exception {
        seed();
        String sessionsPage2 = getJson("/api/sessions?limit=50").get("nextCursor").asString();
        String mailsPage2 = getJson("/api/mails?limit=50").get("nextCursor").asString();
        long busySession = 2500;

        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("sessions p1", "/api/sessions?limit=50");
        urls.put("sessions p2", "/api/sessions?limit=50&cursor=" + sessionsPage2);
        urls.put("sessions needsHuman", "/api/sessions?needsHuman=true&limit=50");
        urls.put("sessions status", "/api/sessions?status=QUOTED&limit=50");
        urls.put("session events", "/api/sessions/" + busySession + "/events?limit=100");
        urls.put("events feed", "/api/events?limit=100");
        urls.put("events kind", "/api/events?kind=llm.call&limit=100");
        urls.put("mails p2", "/api/mails?limit=50&cursor=" + mailsPage2);
        urls.put("mails search", "/api/mails?q=singapore&limit=50");
        urls.put("stats", "/api/stats");

        Map<String, Double> p95 = new LinkedHashMap<>();
        for (var e : urls.entrySet()) {
            for (int i = 0; i < WARMUP; i++) getJson(e.getValue());
            long[] samples = new long[RUNS];
            for (int i = 0; i < RUNS; i++) {
                long t = System.nanoTime();
                JsonNode body = getJson(e.getValue());
                samples[i] = System.nanoTime() - t;
                assertThat(body).isNotNull();
            }
            Arrays.sort(samples);
            p95.put(e.getKey(), samples[(int) Math.ceil(RUNS * 0.95) - 1] / 1e6);
        }
        p95.forEach((k, ms) -> System.out.printf("[perf] %-22s p95 %6.1f ms%n", k, ms));
        assertThat(p95).allSatisfy((k, ms) -> assertThat(ms).as(k).isLessThan(BUDGET_MS));
    }

    private void seed() {
        jdbc.sql("""
                INSERT INTO session (public_ref, type, status, customer_addr, subject, needs_human, event_count,
                                     last_event_at, last_event_summary, created_at)
                SELECT 'AEI-' || g,
                       CASE WHEN g % 10 = 0 THEN 'TRACKING' ELSE 'RFQ' END,
                       (ARRAY['NEW','PRICING','QUOTED','BOOKED','CLOSED'])[1 + g % 5],
                       'customer' || g || '@example.test', 'Quote request ' || g, g % 50 = 0, 10,
                       now() - g * interval '1 minute', 'event ' || g, now() - g * interval '1 minute'
                FROM generate_series(1, 5000) g""").update();
        jdbc.sql("""
                INSERT INTO rfq (session_id, origin_airport, destination, weight_kg)
                SELECT id, 'FRA', 'SIN', 100 FROM session WHERE type = 'RFQ'""").update();
        jdbc.sql("""
                INSERT INTO event (session_id, kind, actor, summary, payload, created_at)
                SELECT 1 + g % 5000,
                       (ARRAY['mail.in','route.decision','llm.call','pricing.decision','mail.out'])[1 + g % 5],
                       'system', 'event ' || g,
                       jsonb_build_object('n', g, 'blob', repeat('x', 500)),
                       now() - (50000 - g) * interval '1 second'
                FROM generate_series(1, 50000) g""").update();
        jdbc.sql("""
                INSERT INTO mail (external_id, direction, from_addr, to_addr, subject, body, session_id, sent_at)
                SELECT g, CASE WHEN g % 2 = 0 THEN 'in' ELSE 'out' END, 'a' || g || '@x.test', 'desk@x.test',
                       CASE WHEN g % 7 = 0 THEN 'Air freight to Singapore' ELSE 'Air freight to Bangkok' END,
                       repeat('Please quote our shipment. ', 20), 1 + g % 5000, now() - g * interval '30 seconds'
                FROM generate_series(1, 10000) g""").update();
        jdbc.sql("ANALYZE").update();
    }
}
