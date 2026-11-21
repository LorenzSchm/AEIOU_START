package ai.aeiou.forwarder.rfq;

/** Where a price candidate comes from; the names match {@code price_candidate.source}. */
public enum CandidateSource {
    sheet, portal, bid, ratedesk, truck, combined;

    /** Air candidates fly the shipment airport to airport; a truck candidate only brings it to the airport. */
    public boolean isAir() {
        return this != truck && this != combined;
    }
}
