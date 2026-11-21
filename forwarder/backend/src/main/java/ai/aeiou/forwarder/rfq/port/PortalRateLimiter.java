package ai.aeiou.forwarder.rfq.port;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One token bucket for all portal rate reads of the team key, shared across sessions.
 *
 * <p>The portal uses a fixed one-minute window per key. We do not know where the window starts, so the bucket
 * follows the server: every response tells us {@code X-RateLimit-Remaining}, and a 429 tells us via
 * {@code Retry-After} when the next window opens. Until the first response the bucket is optimistic.
 */
@Component
public class PortalRateLimiter {

    static final Duration WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition refilled = lock.newCondition();
    /** Reads left in the current window; null = unknown (assume available). */
    private Integer remaining;
    /** When the current window ends, if known. */
    private Instant windowEndsAt;

    @Autowired
    public PortalRateLimiter() {
        this(Clock.systemUTC());
    }

    PortalRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Takes one token, waiting for the next window if needed, but never past {@code budgetEnd}.
     *
     * @return false if no token is available before {@code budgetEnd}; the caller must then skip the call
     */
    public boolean acquire(Instant budgetEnd) throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                Instant now = clock.instant();
                if (windowEndsAt != null && !now.isBefore(windowEndsAt)) {
                    remaining = null; // a new window has started
                    windowEndsAt = null;
                }
                if (remaining == null || remaining > 0) {
                    if (remaining != null && --remaining == 0 && windowEndsAt == null) windowEndsAt = now.plus(WINDOW);
                    return true;
                }
                if (windowEndsAt.isAfter(budgetEnd)) return false;
                long waitMs = Math.max(1, Duration.between(now, windowEndsAt).toMillis());
                refilled.await(waitMs, TimeUnit.MILLISECONDS);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Takes a token only if one is available right now (used for optional extra calls). */
    public boolean tryAcquire() {
        try {
            return acquire(clock.instant());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Feeds the rate-limit headers of a response back into the bucket. */
    public void observe(RatePortal.RateResult result) {
        lock.lock();
        try {
            Instant now = clock.instant();
            if (result.rateLimited()) {
                remaining = 0;
                windowEndsAt = now.plus(result.retryAfter() != null ? result.retryAfter() : WINDOW);
            } else if (result.rateLimitRemaining() != null) {
                // Other calls may be in flight; the server's count is the truth, never raise our own estimate.
                remaining = remaining == null ? result.rateLimitRemaining() : Math.min(remaining, result.rateLimitRemaining());
                if (remaining <= 0 && windowEndsAt == null) windowEndsAt = now.plus(WINDOW);
            }
            refilled.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Reads left in the current window, or null when unknown. */
    public Integer remaining() {
        lock.lock();
        try {
            return remaining;
        } finally {
            lock.unlock();
        }
    }
}
