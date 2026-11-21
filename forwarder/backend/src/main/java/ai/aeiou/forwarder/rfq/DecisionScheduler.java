package ai.aeiou.forwarder.rfq;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * One pending decision timer per session. The timer thread only hands the work to a fresh virtual thread, so a
 * slow decision (writer LLM call, mail send) never delays another session's timer.
 */
@Component
public class DecisionScheduler {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("rfq-decision-timer").daemon().factory());
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    /** Schedules (or reschedules) the decision of {@code sessionId} at {@code at}. */
    public void schedule(long sessionId, Instant at, Runnable decision) {
        long delayMs = Math.max(0, Duration.between(Instant.now(), at).toMillis());
        ScheduledFuture<?> future = timer.schedule(() -> {
            pending.remove(sessionId);
            Thread.ofVirtual().name("rfq-decide-" + sessionId).start(decision);
        }, delayMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = pending.put(sessionId, future);
        if (previous != null) previous.cancel(false);
    }

    public void cancel(long sessionId) {
        ScheduledFuture<?> f = pending.remove(sessionId);
        if (f != null) f.cancel(false);
    }

    public boolean isScheduled(long sessionId) {
        return pending.containsKey(sessionId);
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }
}
