package ai.aeiou.forwarder.router;

import static org.assertj.core.api.Assertions.assertThat;

import ai.aeiou.forwarder.router.Stage1Classifier.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteDecisionTest {

    private static final Result UNDECIDED = new Result(null, true, List.of());
    private static final Result BID = new Result(MailType.CARRIER_BID, true, List.of());

    @Test
    void confidentLlmTypeIsDispatchedWithoutHuman() {
        RouteDecision d = RouteDecision.decide(UNDECIDED, extraction(MailType.NEW_RFQ, 0.93), null, null, 0.75);

        assertThat(d.type()).isEqualTo(MailType.NEW_RFQ);
        assertThat(d.stage()).isEqualTo(2);
        assertThat(d.dispatch()).isTrue();
        assertThat(d.needsHuman()).isFalse();
    }

    @Test
    void lowConfidenceFlagsAHumanButStillDispatches() {
        RouteDecision d = RouteDecision.decide(UNDECIDED, extraction(MailType.NEW_RFQ, 0.6), null, null, 0.75);

        assertThat(d.needsHuman()).isTrue();
        assertThat(d.dispatch()).isTrue();
    }

    @Test
    void veryLowConfidenceIsStillDispatchedButFlagged() {
        RouteDecision d = RouteDecision.decide(UNDECIDED, extraction(MailType.NEW_RFQ, 0.3), null, null, 0.75);

        assertThat(d.dispatch()).isTrue();
        assertThat(d.needsHuman()).isTrue();
    }

    @Test
    void stageOneFactsWinOverAContradictingLlm() {
        RouteDecision d = RouteDecision.decide(BID, extraction(MailType.OTHER, 0.9), null, "RFQ", 0.75);

        assertThat(d.type()).isEqualTo(MailType.CARRIER_BID);
        assertThat(d.stage()).isEqualTo(1);
        assertThat(d.dispatch()).isTrue();
        assertThat(d.problems()).singleElement().asString().contains("stage 1 found CARRIER_BID");
    }

    @Test
    void failedLlmCallStillDispatchesAStageOneType() {
        RouteDecision d = RouteDecision.decide(BID, null, "HTTP 502", "RFQ", 0.75);

        assertThat(d.dispatch()).isTrue();
        assertThat(d.problems()).singleElement().asString().contains("HTTP 502");
    }

    @Test
    void failedLlmCallWithoutStageOneTypeGoesToAHuman() {
        RouteDecision d = RouteDecision.decide(UNDECIDED, null, "timeout", null, 0.75);

        assertThat(d.type()).isEqualTo(MailType.OTHER);
        assertThat(d.dispatch()).isFalse();
        assertThat(d.needsHuman()).isTrue();
    }

    @Test
    void acceptanceOutsideAKnownThreadIsSuspicious() {
        RouteDecision d = RouteDecision.decide(UNDECIDED, extraction(MailType.ACCEPTANCE, 0.9), null, null, 0.75);

        assertThat(d.needsHuman()).isTrue();
        assertThat(d.dispatch()).isTrue();
    }

    private static Extraction extraction(MailType type, double confidence) {
        return new Extraction(type, confidence, "test", "en", null, null, null, null);
    }
}
