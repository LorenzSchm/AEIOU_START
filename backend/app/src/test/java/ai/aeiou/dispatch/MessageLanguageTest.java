package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MessageLanguageTest {
  @Test
  void englishRfq() {
    assertEquals(
        "en",
        MessageLanguage.detect(
            """
            Hi,

            Could you quote your cheapest air freight option to EZE, all-in in EUR, including
            truck pickup from our warehouse to the airport?

            The shipment is 12 pieces of precision instruments, 1805 kg gross and 6.92 cbm,
            ready for pickup on 2026-11-30.

            Sara
            Lindqvist Medtech
            """));
  }

  @Test
  void germanRfq() {
    assertEquals(
        "de",
        MessageLanguage.detect(
            """
            Guten Tag,

            bitte senden Sie uns ein Angebot für Luftfracht von München nach JFK. Die Sendung
            besteht aus 4 Paletten Maschinenteilen, insgesamt 820 kg, und ist ab dem 2026-12-02
            abholbereit. Wir benötigen den Gesamtpreis inklusive Abholung im Lager.

            Mit freundlichen Grüßen
            Tobias Auer
            """));
  }

  @Test
  void frenchRfq() {
    assertEquals(
        "fr",
        MessageLanguage.detect(
            """
            Bonjour,

            Pourriez-vous nous envoyer une offre pour un envoi aérien de Lyon vers Montréal ?
            Il s'agit de trois palettes de pièces détachées, environ 640 kg au total, prêtes
            à être enlevées la semaine prochaine dans notre entrepôt.

            Cordialement,
            Claire Dubois
            """));
  }

  @Test
  void englishWithGermanSignature() {
    assertEquals(
        "en",
        MessageLanguage.detect(
            """
            Hello,

            could you please tell me the current status of our shipment to SGN, 1403 kg,
            ready on 2026-11-28? Where is it now and when will it arrive?

            Mit freundlichen Grüßen
            Tobias Auer
            Versand / Leiter Logistik
            Auer Maschinenbau GmbH, Industriestraße 12, 93053 Regensburg
            Geschäftsführer: Johann Auer, Amtsgericht Regensburg HRB 12345
            """));
    assertEquals(
        "en",
        MessageLanguage.detect(
            """
            Please book the cheapest option for AEIOU-4125 and send the documents to our team.

            Tobias Auer
            Auer Maschinenbau GmbH
            Industriestraße 12
            93053 Regensburg
            """));
    assertEquals(
        "en",
        MessageLanguage.detect(
            """
            We need the airway bill for our shipment before Friday, can you send it over?

            Freundliche Grüsse
            Urs Meier, Speditionsabteilung, Bahnhofstrasse 5, 8001 Zürich
            """));
  }

  @Test
  void ignoresQuotedReply() {
    assertEquals(
        "en",
        MessageLanguage.detect(
            """
            Thanks, we accept the offer and would like to proceed with the booking today.

            Am 20.11.2026 um 10:12 schrieb Disposition:
            > Sehr geehrte Damen und Herren, anbei unser Angebot für Ihre Sendung nach Shanghai.
            > Bitte bestätigen Sie die Buchung bis morgen früh.
            """));
  }

  @Test
  void shortOrEmptyTextIsUnknown() {
    assertNull(MessageLanguage.detect("Thanks!"));
    assertNull(MessageLanguage.detect("OK, AEIOU-1234 FRA-JFK 120 kg"));
    assertNull(MessageLanguage.detect(""));
    assertNull(MessageLanguage.detect(null));
  }
}
