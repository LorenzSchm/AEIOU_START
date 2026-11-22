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

  @Test
  void round185ReadsAFirmPickupPriceWhoseRoadTransitIsStillOpen() {
    // Schwabenbote Transporte, request #55685 shipment 1, verbatim.
    String reply = """
        Hi Team AEIOU,

        Our all-in rate for collection and delivery to DUS is 228.37 EUR, valid through 22 Nov 2026 09:54 UTC.

        Estimated time to pickup: 21 hours. This does not include transit from pickup to airport delivery, which we can’t confirm at this stage.

        Offer reference: TRK-17754. No booking is needed for pickup.

        Best regards,
        Schwabenbote Transporte""";

    var bid = MailParser.bid(reply, true).orElseThrow();
    assertEquals(new BigDecimal("228.37"), bid.euros());
    assertNull(bid.days());
    assertTrue(MailParser.quotesAmbiguousPickupTiming(reply));
    assertTrue(MailParser.bid(reply.replace("This does not include transit",
        "The price does not include fuel or transit"), true).isEmpty());
    assertTrue(MailParser.bid(reply.replace("Estimated time", "We can’t confirm the price yet. Estimated time"),
        true).isEmpty());
  }
}
