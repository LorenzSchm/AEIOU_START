package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.MailParser;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CarrierBidParsingTest {
  @Test
  void readsAFirmPickupBidThatDoesNotRequireSeparateBooking() {
    String reply = """
        Our all-in rate for collection and delivery to MUC is 212.67 EUR.
        Estimated transit time from actual pickup to airport delivery is 25 hours,
        excluding any wait before collection.
        The rate is valid until 21 Nov 2026 21:02 UTC. Your offer reference is TRK-5257,
        and there’s no need to book the pickup separately.
        """;

    var bid = MailParser.bid(reply, true).orElseThrow();
    assertEquals(new BigDecimal("212.67"), bid.euros());
    assertEquals(25 / 24.0, bid.days());
    assertTrue(MailParser.bid(reply + "Fuel is billed separately.", true).isEmpty());
  }

  @Test
  void readsPickupTransitWhenTheBidNamesTheDeliveryAirport() {
    String reply = """
        Our all-in rate for collection and delivery to STR is 355.16 EUR.
        The estimated transit time from actual pickup to delivery at STR is 17 hours,
        excluding any waiting time before collection.
        """;

    var bid = MailParser.bid(reply, true).orElseThrow();
    assertEquals(new BigDecimal("355.16"), bid.euros());
    assertEquals(17 / 24.0, bid.days());
  }
}
