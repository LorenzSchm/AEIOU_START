package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.MailParser;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** New wording variants of customer follow-ups, carrier bids and attachment requests. */
class FollowUpVariantsTest {
  @ParameterizedTest
  @ValueSource(strings = {"Please book.", "Go ahead and book.", "Yes, please book it.", "Great, go ahead.",
      "Approved – please proceed.", "We accept your offer. Please book.", "OK, let's book it.",
      "We'd like to book this shipment.", "Please book it as soon as possible.", "Bitte so buchen, danke!",
      "Thanks! Please book it now.\n\nBest regards\nAnna"})
  void plainApprovalsBook(String reply) {
    assertTrue(PortalBookings.accepted(reply), reply);
  }

  @ParameterizedTest
  @ValueSource(strings = {"we accept, but please book the cheaper alternative", "Hold off for now, please.",
      "Don't book yet.", "Looks good but can you do 10% better?", "Please confirm the booking.",
      "Thank you for your offer. We would like to proceed.", "Can you book it?", "Please book it if the price falls.",
      "Go ahead and book, unless the rate changes."})
  void conditionalOrOtherRepliesNeverBook(String reply) {
    assertFalse(PortalBookings.accepted(reply), reply);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "Go ahead with shipment B only.|2", "Please book the first shipment only.|1", "Book both, please.|1,2",
      "Please book shipment 1 only, thanks.|1", "Please book the shipment to SIN.|2", "Please book shipment A.|1"})
  void namedShipmentsOfASeparateQuoteBook(String reply, String numbers) {
    var expected = new TreeSet<Integer>();
    for (String n : numbers.split(",")) expected.add(Integer.parseInt(n));
    assertEquals(expected, new TreeSet<>(PortalBookings.acceptedShipments(reply, List.of("BKK", "SIN"))), reply);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Please book shipment B if the price holds.", "Hold shipment A, book shipment B."})
  void conditionalShipmentRepliesNeverBook(String reply) {
    assertEquals(Set.of(), PortalBookings.acceptedShipments(reply, List.of("BKK", "SIN")), reply);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Can you match 1450 EUR?", "Another forwarder quoted EUR 1,200. Can you do better?",
      "Your price is too high, any flexibility?", "Can you beat EUR 900?", "Is there any room on the price?"})
  void priceMatchRequestsAreRecognised(String reply) {
    assertTrue(PriceMatchReplies.requested(reply), reply);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Cancel please.", "Please cancel.\n\nBest\nAnna", "We no longer need the shipment.",
      "Storno bitte.", "We need to cancel.", "Please cancel the booking."})
  void cancellationsAreAcknowledged(String reply) {
    assertTrue(QuoteFollowUps.cancels(reply), reply);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Hold off for now.", "Please don't cancel, we just need a day.",
      "We need to cancel the pickup and will deliver to FRA ourselves."})
  void holdsAndChangesAreNotCancellations(String reply) {
    assertFalse(QuoteFollowUps.cancels(reply), reply);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "PDF and Excel please.|PDF,XLSX", "Please attach the offer as a PDF file.|PDF",
      "Por favor, envíen la oferta en PDF.|PDF", "Teklifinizi PDF olarak gönderin lütfen.|PDF",
      "견적서는 PDF로 보내주세요.|PDF", "見積書はPDFでお願いします。|PDF", "A quotation in PDF would help.|PDF",
      "Prosimy o ofertę w PDF.|PDF", "Please quote in the email body, no attachments.|"})
  void requestedQuoteFormatsAreRecognised(String body, String formats) {
    var expected = EnumSet.noneOf(QuoteAttachments.Format.class);
    if (formats != null)
      for (String f : formats.split(",")) expected.add(QuoteAttachments.Format.valueOf(f));
    assertEquals(expected, QuoteAttachments.requested("RFQ", body), body);
  }

  @ParameterizedTest
  @ValueSource(strings = {"All-in EUR 180 excl. fuel. Transit 6 hours.",
      "All-in EUR 180, fuel surcharge additional. Transit 6 hours.", "EUR 180 zzgl. Treibstoffzuschlag. Transit 6 hours.",
      "EUR 180 all-in, ohne Maut. Transit 6 hours.", "All-in EUR 180 net of fuel. Transit 6 hours.",
      "All-in EUR 180 (excl. tolls). Transit 6 hours.", "EUR 180 all-in, fuel extra. Transit 6 hours.",
      "Price EUR 180 w/o fuel surcharge. Transit 6 hours.", "EUR 180 all-in, sans surcharge carburant. Transit 6 hours.",
      "EUR 180 all-in + toll. Transit 6 hours.", "EUR 180 all in, 6 hours, security fee not incl."})
  void bidsThatLeaveAChargeOutAreNeverAllIn(String bid) {
    assertTrue(MailParser.bid(bid, true).isEmpty(), bid);
    assertTrue(MailParser.bid(bid.replace("6 hours", "3 days"), false).isEmpty(), bid);
  }

  @ParameterizedTest
  @ValueSource(strings = {"All in EUR 180. Transit time from pickup to delivery: 6 hours.",
      "EUR 180 all-in. Transit 6 Stunden.", "All-in EUR 180, valid for 48 hours. Transit 6 hours.",
      "All-in EUR 180 to SIN customs-ready. Transit 6 hours."})
  void firmTruckBidsStayReadable(String bid) {
    var parsed = MailParser.bid(bid, true).orElseThrow(() -> new AssertionError(bid));
    assertEquals(0, parsed.euros().compareTo(new java.math.BigDecimal("180")));
    assertEquals(0.25, parsed.days(), 1e-9);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "Hola,\\n\\nnecesitamos una cotización de flete aéreo de FRA a MEX, 350 kg, 1,6 m3.\\n\\nSaludos\\nCarlos|es",
      "Buongiorno,\\n\\nvi chiediamo una quotazione per una spedizione aerea da MUC a NBO, 180 kg.\\n\\nGrazie\\nLuca|it",
      "Goedemiddag,\\n\\ngraag een offerte voor luchtvracht van DUS naar JNB, 270 kg.\\n\\nMet vriendelijke groet\\nSanne|nl",
      "Dzień dobry,\\n\\nprosimy o wycenę frachtu lotniczego z STR do ORD, 520 kg.\\n\\nPozdrawiam\\nPiotr|pl",
      "Olá,\\n\\nprecisamos de uma cotação de frete aéreo de FRA para GRU, 230 kg, a opção mais rápida. Entregaremos a carga no aeroporto nós mesmos.\\n\\nObrigado\\nJoão|pt",
      "Hi,\\n\\nplease quote FRA to SIN, 200 kg.\\n\\nThanks\\n\\n-----Ursprüngliche Nachricht-----\\nBitte um Angebot|en",
      "Guten Tag,\\n\\nbitte um Angebot FRA-ORD 200 kg.\\n\\nKind regards / Mit freundlichen Grüßen\\nJonas|de"})
  void repliesFollowTheCustomersOwnLanguage(String body, String language) {
    assertEquals(language, CustomerLanguage.replyTag(Map.of("body", body.replace("\\n", "\n"), "subject", "RFQ")));
  }
}
