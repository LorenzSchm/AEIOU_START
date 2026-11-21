package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GreetingTest {
  private static final Instant MORNING = Instant.parse("2026-11-21T08:00:00Z");

  private static String greeting(String sender, String body) {
    return Greeting.at(Map.of("sender", sender, "body", body), MORNING);
  }

  @Test
  void namesThePersonFromTheSignature() {
    assertEquals(
        "Good morning Petra,",
        greeting(
            "tracking@hackloop.mail",
            "Hello,\n\nWhere is it now?\n\nThanks a lot and have a lovely day!\nPetra Hofmann\n"
                + "Export Desk, Hofmann Textilwerk KG"));
    assertEquals(
        "Good morning Jonas,",
        greeting(
            "kessler-feinmechanik@hackloop.mail",
            "Please quote.\n\nMit freundlichen Gruessen / Kind regards\nJonas Kessler\n"
                + "Logistics Coordinator\nKessler Feinmechanik GmbH, Ulm"));
    assertEquals(
        "Good morning Mira,",
        greeting(
            "nordlicht-audio@hackloop.mail",
            "Lot 1: 4 drums, 730 KG gross\n\nPlease send your all-in price in EUR.\n\n"
                + "Mira Petersen\nNordlicht Audio"));
  }

  @Test
  void fallsBackToAPlainGreeting() {
    assertEquals(
        "Good morning,", greeting("tracking@hackloop.mail", "Where is BK-UNKNOWN?\n\nPlease check."));
    assertEquals(
        "Good morning,", greeting("buyer@example.invalid", "Please send a quote.\n\nRegards,\nNordlicht Audio GmbH"));
    assertEquals(
        "Good morning,", greeting("nordlicht-audio@hackloop.mail", "Please quote.\n\nBest,\nNordlicht Audio"));
  }

  @Test
  void choosesTheGreetingInBerlinTimeAndRefreshesOldDrafts() {
    var mail = Map.of("sender", "buyer@example.invalid", "body", "Please quote.\n\nBest,\nAnna Sonnberg");
    assertEquals("Hello Anna,", Greeting.at(mail, Instant.parse("2026-11-21T03:59:59Z")));
    assertEquals("Good morning Anna,", Greeting.at(mail, Instant.parse("2026-11-21T04:00:00Z")));
    assertEquals("Good afternoon Anna,", Greeting.at(mail, Instant.parse("2026-11-21T11:00:00Z")));
    assertEquals("Good evening Anna,", Greeting.at(mail, Instant.parse("2026-11-21T17:00:00Z")));
    assertEquals("Good evening Anna,", Greeting.at(mail, Instant.parse("2026-07-21T16:00:00Z")));
    assertEquals("Good evening Anna,\n\nThe total is EUR 125.00.",
        Greeting.refresh("Hi Sonnberg GmbH,\n\nThe total is EUR 125.00.", mail,
            Instant.parse("2026-11-21T17:00:00Z")));
  }
}
