package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CloudlineTest {
  private static final String QUOTE =
      "Hi Nordlicht Audio,\n\nThe lowest rate we found for your 349 kg shipment from MUC to PVG"
          + " is with Helix Air Freight at EUR 2140.5 all in.\n\nWould you like us to book it for"
          + " you?\n\nBest regards,\nTeam AEIOU";

  private static String render(String total) {
    return Cloudline.renderMailHtml(QUOTE, total, "en", "cloudline", "MUC", "PVG");
  }

  @Test
  void wrapsTheFinishedQuoteWithoutChangingIt() {
    String html = render("EUR 2140.5");
    assertTrue(html.startsWith("<!doctype html>"));
    assertTrue(html.contains("<strong>EUR 2140.5</strong> all in."));
    assertTrue(html.contains("MUC <span role=\"img\""));
    assertTrue(html.contains("background-image:url('data:image/webp;base64,"));
    assertTrue(html.contains("<span style=\"color: #304c5a; font-weight: 700;\">Team AEIOU</span>"));
    assertFalse(html.contains("$"));
  }

  @Test
  void boldsOnlyATotalThatAppearsExactlyOnce() {
    assertFalse(render("EUR 999").contains("<strong>"));
    assertFalse(render("").contains("<strong>"));
  }

  @Test
  void rejectsUnknownDesigns() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Cloudline.renderMailHtml(QUOTE, "", "en", "skyline", "MUC", "PVG"));
  }
}
