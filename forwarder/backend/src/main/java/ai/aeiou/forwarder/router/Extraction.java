package ai.aeiou.forwarder.router;

import java.math.BigDecimal;

/** Output of router stage 2 (one LLM call: classify + extract). Fields not relevant to the type are null. */
public record Extraction(
        MailType type,
        double confidence,
        String reasoning,
        String language,       // ISO code of the language the sender wrote in, e.g. "en", "de"
        String senderName,     // how the sender signs, for a personal greeting
        Rfq rfq,
        Bid bid,
        Tracking tracking) {

    public record Rfq(
            String originZip,
            String originCity,
            String originAirport,
            String destination,       // IATA if determinable
            BigDecimal weightKg,
            BigDecimal volumeCbm,
            Integer pieces,
            String mode,              // "cheapest" | "fastest"
            Boolean doorPickup,
            String readyDate,         // ISO date or null
            String commodity,
            String notes) {}

    /** {@code rateId}: the carrier's own "R-..." reference; it books through the portal like a portal rate id. */
    public record Bid(
            BigDecimal price,
            String currency,
            Integer transitDays,
            String validUntil,
            String airlineOrCompany,
            Boolean declined,
            String notes,
            String rateId) {

        public Bid(BigDecimal price, String currency, Integer transitDays, String validUntil, String airlineOrCompany,
                   Boolean declined, String notes) {
            this(price, currency, transitDays, validUntil, airlineOrCompany, declined, notes, null);
        }
    }

    public record Tracking(String reference, String question) {}
}
