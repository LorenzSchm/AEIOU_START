package ai.aeiou.forwarder.pricing;

import java.util.List;
import java.util.Set;

/** Pure, in-memory pricing over the active tariffs. Must answer in microseconds; no I/O. */
public interface PricingService {

    /** One quote per airline that has the lane in an active tariff, using the cheapest valid weight break. */
    List<PriceQuote> priceFromSheets(Shipment shipment);

    /** Airline names that have this lane in an active sheet. */
    Set<String> sheetAirlines(String origin, String destination);

    /** Reload the in-memory store from the DB (after a tariff was activated). */
    void reload();
}
