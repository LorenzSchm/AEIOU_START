package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GreetingTest {
  private static String hi(String sender, String body) {
    return Greeting.hi(Map.of("sender", sender, "body", body));
  }

  @Test
  void namesTheCompanyFromTheSignature() {
    assertEquals(
        "Hi Hofmann Textilwerk KG,",
        hi(
            "tracking@hackloop.mail",
            "Hello,\n\nWhere is it now?\n\nThanks a lot and have a lovely day!\nPetra Hofmann\n"
                + "Export Desk, Hofmann Textilwerk KG"));
    assertEquals(
        "Hi Kessler Feinmechanik GmbH,",
        hi(
            "kessler-feinmechanik@hackloop.mail",
            "Please quote.\n\nMit freundlichen Gruessen / Kind regards\nJonas Kessler\n"
                + "Logistics Coordinator\nKessler Feinmechanik GmbH, Ulm"));
    assertEquals(
        "Hi Nordlicht Audio,",
        hi(
            "nordlicht-audio@hackloop.mail",
            "Lot 1: 4 drums, 730 KG gross\n\nPlease send your all-in price in EUR.\n\n"
                + "Mira Petersen\nNordlicht Audio"));
  }

  @Test
  void fallsBackToAPlainGreeting() {
    assertEquals("Hi,", hi("tracking@hackloop.mail", "Where is BK-UNKNOWN?\n\nPlease check."));
    assertEquals(
        "Hi,", hi("buyer@example.invalid", "Lot 2: 2 pieces, 141 KG gross\n\nMira Petersen"));
  }
}
