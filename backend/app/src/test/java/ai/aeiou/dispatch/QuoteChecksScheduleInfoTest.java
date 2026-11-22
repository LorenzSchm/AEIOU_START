package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class QuoteChecksScheduleInfoTest {
  @Test
  void arrangingTruckingIsCoveredByCollectionWhileExtraServicesStillNeedReview() {
    assertDoesNotThrow(() -> QuoteChecks.preflight(Map.of("subject", "Air freight quote to SGN", "body",
        "Please include collection and arrange trucking from our site to the departure airport.")));
    assertThrows(WaveWorker.Conflict.class, () -> QuoteChecks.preflight(Map.of("subject", "Air freight quote to SGN", "body",
        "Please include collection and arrange crating from our site to the departure airport.")));
  }

  @Test
  void round97AskingForEarliestDepartureAndArrivalTimesIsNotACharge() {
    assertDoesNotThrow(() -> QuoteChecks.preflight(Map.of("subject", "Air freight quote: FRA to ORD", "body", """
        Could you please quote the fastest air freight option from FRA (Frankfurt) to ORD (Chicago, US)?
        Please include the all-in price in EUR and the earliest available departure and arrival times.
        """)));
    assertThrows(WaveWorker.Conflict.class, () -> QuoteChecks.preflight(Map.of("subject", "RFQ", "body",
        "Please include the customs clearance fees.")));
  }
}
