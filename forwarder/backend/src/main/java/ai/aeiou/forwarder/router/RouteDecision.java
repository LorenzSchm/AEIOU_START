package ai.aeiou.forwarder.router;

import java.util.ArrayList;
import java.util.List;

/**
 * Combines the stage-1 result and the stage-2 extraction into the final route (pure, no I/O).
 *
 * <p>Hard facts from stage 1 win over the LLM; a disagreement is reported as a problem. Every problem
 * flags the session for a human, but the mail is still dispatched when the type is known with
 * reasonable confidence, so the 6-minute quote window is never lost while waiting for a person.
 *
 * @param stage    1 if stage 1 decided the type, 2 if the LLM did
 * @param problems reasons a human should look at the session (empty if none)
 * @param dispatch whether handlers should receive the mail
 */
record RouteDecision(MailType type, double confidence, int stage, List<String> problems, boolean dispatch) {

    /**
     * @param threshold   setting {@code routerConfidenceThreshold}: below it a human is asked
     * @param minDispatch below this confidence an LLM-typed mail is not dispatched at all
     */
    static RouteDecision decide(Stage1Classifier.Result s1, Extraction extraction, String llmError,
                                String threadSessionType, double threshold, double minDispatch) {
        List<String> problems = new ArrayList<>();
        if (llmError != null) problems.add("LLM routing failed: " + llmError);

        if (s1.type() != null) {
            if (extraction != null && extraction.type() != s1.type()) {
                problems.add("Stage 2 classified " + extraction.type() + " but stage 1 found " + s1.type());
            }
            return new RouteDecision(s1.type(), 1.0, 1, List.copyOf(problems), true);
        }
        if (extraction == null || extraction.type() == null) {
            return new RouteDecision(MailType.OTHER, 0.0, 2, List.copyOf(problems), false);
        }

        MailType type = extraction.type();
        double confidence = extraction.confidence();
        if (confidence < threshold) {
            problems.add("Low router confidence %.2f for %s".formatted(confidence, type));
        }
        boolean inThread = threadSessionType != null;
        if (!inThread && (type == MailType.ACCEPTANCE || type == MailType.FOLLOW_UP)) {
            problems.add(type + " but the mail is not in a thread we know");
        }
        if (inThread && type == MailType.NEW_RFQ && "RFQ".equals(threadSessionType)) {
            problems.add("NEW_RFQ inside an existing RFQ thread");
        }
        return new RouteDecision(type, confidence, 2, List.copyOf(problems), confidence >= minDispatch);
    }

    boolean needsHuman() {
        return !problems.isEmpty();
    }
}
