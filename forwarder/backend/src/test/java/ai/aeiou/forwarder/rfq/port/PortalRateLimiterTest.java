package ai.aeiou.forwarder.rfq.port;

import static org.assertj.core.api.Assertions.assertThat;

import ai.aeiou.forwarder.rfq.port.RatePortal.RateResult;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PortalRateLimiterTest {

    private final PortalRateLimiter limiter = new PortalRateLimiter();

    @Test
    void optimisticUntilTheServerSaysOtherwise() throws InterruptedException {
        assertThat(limiter.acquire(Instant.now())).isTrue();
        assertThat(limiter.remaining()).isNull();
    }

    @Test
    void followsRemainingHeaderAndRefusesBeyondBudget() throws InterruptedException {
        limiter.observe(new RateResult(200, null, 30, 1, null, null));

        assertThat(limiter.acquire(Instant.now())).isTrue();
        assertThat(limiter.remaining()).isZero();
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.acquire(Instant.now().plusSeconds(5))).isFalse(); // the window ends in ~60 s
    }

    @Test
    void waitsForRetryAfterWhenWithinBudget() throws InterruptedException {
        limiter.observe(new RateResult(429, null, 30, 0, Duration.ofSeconds(1), null));

        long start = System.nanoTime();
        assertThat(limiter.acquire(Instant.now().plusSeconds(5))).isTrue();
        Duration waited = Duration.ofNanos(System.nanoTime() - start);
        assertThat(waited).isBetween(Duration.ofMillis(900), Duration.ofSeconds(3));
    }
}
