package ai.aeiou.forwarder.session;

import static org.assertj.core.api.Assertions.assertThat;

import ai.aeiou.forwarder.CoreDatabaseTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The single quote per session must hold under concurrent senders. */
class ClaimQuoteConcurrencyTest extends CoreDatabaseTest {

    @Autowired SessionService sessions;

    @RepeatedTest(5)
    void exactlyOneOfTwoConcurrentClaimsWins() throws Exception {
        long sessionId = sessions.create("RFQ", "buyer@acme.example", "Quote", null);
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> claims = List.of(
                    pool.submit(() -> claimAfter(start, sessionId, "1000.00")),
                    pool.submit(() -> claimAfter(start, sessionId, "2000.00")));

            long winners = 0;
            for (Future<Boolean> claim : claims) if (claim.get()) winners++;
            assertThat(winners).isEqualTo(1);
        }
        SessionService.SessionView view = sessions.find(sessionId).orElseThrow();
        assertThat(view.quotedAt()).isNotNull();
        assertThat(view.quotedTotal()).isIn(new BigDecimal("1000.00"), new BigDecimal("2000.00"));
    }

    @Test
    void releasedClaimCanBeClaimedAgain() {
        long sessionId = sessions.create("RFQ", "buyer@acme.example", "Quote", null);

        assertThat(sessions.claimQuote(sessionId, BigDecimal.TEN, "EUR", BigDecimal.TEN)).isTrue();
        assertThat(sessions.claimQuote(sessionId, BigDecimal.ONE, "EUR", BigDecimal.ONE)).isFalse();
        sessions.releaseQuote(sessionId);
        assertThat(sessions.claimQuote(sessionId, BigDecimal.ONE, "EUR", BigDecimal.ONE)).isTrue();
    }

    private boolean claimAfter(CyclicBarrier start, long sessionId, String total) throws Exception {
        start.await();
        BigDecimal amount = new BigDecimal(total);
        return sessions.claimQuote(sessionId, amount, "EUR", amount);
    }
}
