package ai.aeiou.forwarder.rfq;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.session.SessionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Writes RFQ workflow events with {@code elapsedMs} since the RFQ arrived, so the timeline shows where the
 * 2-minute budget went. The arrival is the 5U send time of the customer mail (what the scorer measures from).
 */
@Component
public class RfqEvents {

    private final EventLog events;
    private final SessionService sessions;
    private final Clock clock;
    private final Map<Long, Instant> arrivals = new ConcurrentHashMap<>();

    @Autowired
    public RfqEvents(EventLog events, SessionService sessions, ObjectProvider<Clock> clock) {
        this(events, sessions, clock.getIfAvailable(Clock::systemUTC));
    }

    RfqEvents(EventLog events, SessionService sessions, Clock clock) {
        this.events = events;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** Payload map from key/value pairs; null values are left out. */
    public static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    public void registerArrival(long sessionId, Instant arrivedAt) {
        arrivals.put(sessionId, arrivedAt);
    }

    /** When the RFQ of this session arrived; falls back to the session's creation time after a restart. */
    public Instant arrival(long sessionId) {
        return arrivals.computeIfAbsent(sessionId, id -> sessions.find(id)
                .map(s -> s.createdAt().toInstant())
                .orElseGet(clock::instant));
    }

    public long elapsedMs(long sessionId) {
        return Duration.between(arrival(sessionId), clock.instant()).toMillis();
    }

    public void forget(long sessionId) {
        arrivals.remove(sessionId);
    }

    public void system(long sessionId, String kind, String summary, Map<String, ?> payload) {
        append(sessionId, null, kind, Actor.system, summary, payload);
    }

    public void system(long sessionId, Long mailId, String kind, String summary, Map<String, ?> payload) {
        append(sessionId, mailId, kind, Actor.system, summary, payload);
    }

    public void human(long sessionId, String kind, String summary, Map<String, ?> payload) {
        append(sessionId, null, kind, Actor.human, summary, payload);
    }

    public void append(long sessionId, Long mailId, String kind, Actor actor, String summary, Map<String, ?> payload) {
        long elapsed = elapsedMs(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("elapsedMs", elapsed);
        if (payload != null) body.putAll(payload);
        events.append(sessionId, mailId, kind, actor, summary + " (+" + formatElapsed(elapsed) + ")", body);
    }

    static String formatElapsed(long ms) {
        return ms < 10_000 ? String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0) : (ms / 1000) + "s";
    }
}
