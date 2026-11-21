package ai.aeiou.forwarder.mail;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Token bucket for outgoing mail. 5U allows 60 mails per minute per team key; with a burst of
 * {@code capacity} and a refill of {@code perMinute}, no 60-second window can exceed
 * {@code capacity + perMinute}. {@link #backOff} empties the bucket after a 429 so every sender waits.
 *
 * <p>Uses a {@link ReentrantLock} (not {@code synchronized}) so waiting virtual threads are not pinned.
 */
public final class SendRateLimiter {

    private final double capacity;
    private final double tokensPerNano;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();

    private double tokens;
    private long lastRefill;
    private long blockedUntil;

    public SendRateLimiter(int capacity, int perMinute) {
        this(capacity, perMinute, System::nanoTime);
    }

    SendRateLimiter(int capacity, int perMinute, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.tokensPerNano = perMinute / (double) Duration.ofMinutes(1).toNanos();
        this.clock = nanoClock;
        this.tokens = capacity;
        this.lastRefill = nanoClock.getAsLong();
        this.blockedUntil = lastRefill;
    }

    /** Blocks until a mail may be sent. */
    public void acquire() throws InterruptedException {
        while (true) {
            long waitNanos = tryAcquire();
            if (waitNanos <= 0) return;
            Thread.sleep(Duration.ofNanos(waitNanos));
        }
    }

    /** Takes a token if one is available; otherwise returns how long to wait, in nanoseconds. */
    long tryAcquire() {
        lock.lock();
        try {
            long now = clock.getAsLong();
            refill(now);
            if (now < blockedUntil) return blockedUntil - now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / tokensPerNano);
        } finally {
            lock.unlock();
        }
    }

    /** After a 429: nobody sends for {@code pause}, and the bucket starts empty afterwards. */
    public void backOff(Duration pause) {
        lock.lock();
        try {
            long now = clock.getAsLong();
            blockedUntil = Math.max(blockedUntil, now + pause.toNanos());
            tokens = 0;
            lastRefill = blockedUntil;
        } finally {
            lock.unlock();
        }
    }

    private void refill(long now) {
        if (now <= lastRefill) return;
        tokens = Math.min(capacity, tokens + (now - lastRefill) * tokensPerNano);
        lastRefill = now;
    }
}
