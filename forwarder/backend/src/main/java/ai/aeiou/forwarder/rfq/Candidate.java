package ai.aeiou.forwarder.rfq;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One priced option for a session, as stored in {@code price_candidate}. {@code totalEur} is the comparison value,
 * {@code total}/{@code currency} is what we would quote. {@code id} is null until persisted.
 */
public record Candidate(
        Long id,
        CandidateSource source,
        String airlineCode,
        String airlineName,
        BigDecimal total,
        String currency,
        BigDecimal totalEur,
        Integer transitDays,
        String rateId,
        OffsetDateTime validUntil,
        Map<String, Object> breakdown) {

    public Candidate withId(long newId) {
        return new Candidate(newId, source, airlineCode, airlineName, total, currency, totalEur, transitDays, rateId,
                validUntil, breakdown);
    }

    /** Short human label for events and reasons, e.g. "Helix Air Freight (sheet)". */
    public String label() {
        String who = airlineName != null ? airlineName : airlineCode != null ? airlineCode : "unknown";
        return who + " (" + source + ")";
    }
}
