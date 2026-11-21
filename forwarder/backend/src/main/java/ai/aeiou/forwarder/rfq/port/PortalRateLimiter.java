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
 * <p>The portal uses a fixed one-minute window per key and allows only {@value #DEFAULT_LIMIT} rate reads in it. We
 * do not know where the window starts, so the bucket is conservative: it assumes a window starts with our first read
 * (the server's may have started earlier and end sooner) and follows the server from there. Every response tells us
 * {@code X-RateLimit-Limit}/{@code -Remaining}, and a 429 tells us via {@code Retry-After} when the next window opens.
 */
@Component
public class PortalRateLimiter {

    static final Duration WINDOW = Duration.ofSeconds(60);
    /** Reads per window before the portal has told us its limit (5U: {@code x-ratelimit-limit: 10}). */
    static final int DEFAULT_LIMIT = 10;

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition refilled = lock.newCondition();
    /** Reads per window, as last stated by the portal. */
    private int limit = DEFAULT_LIMIT;
    /** Reads left in the current window (our estimate, never above the server's count). */
    private int remaining = DEFAULT_LIMIT;
    /** When the current window ends; null until the first read of a window. */
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
                    remaining = limit; // a new window has started
                    windowEndsAt = null;
                }
                if (remaining > 0) {
                    remaining--;
                    if (windowEndsAt == null) windowEndsAt = now.plus(WINDOW);
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
            if (result.rateLimitLimit() != null && result.rateLimitLimit() > 0) limit = result.rateLimitLimit();
            if (result.rateLimited()) {
                remaining = 0;
                windowEndsAt = now.plus(result.retryAfter() != null ? result.retryAfter() : WINDOW);
            } else if (result.rateLimitRemaining() != null) {
                // Other calls may be in flight; the server's count is the truth, never raise our own estimate.
                remaining = Math.max(0, Math.min(remaining, result.rateLimitRemaining()));
                if (windowEndsAt == null) windowEndsAt = now.plus(WINDOW);
            }
            refilled.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Reads left in the current window, as far as we know. */
    public int remaining() {
        lock.lock();
        try {
            return remaining;
        } finally {
            lock.unlock();
        }
    }
}
