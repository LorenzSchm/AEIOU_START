package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.aeiou.freight.Catalog;
import ai.aeiou.freight.Offer;
import ai.aeiou.freight.Shipment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class PriceMatchRepliesTest {
  private static final Instant NOW = Instant.parse("2026-11-22T09:46:10Z");
  private static final Instant START = NOW.minusSeconds(600);
  private static final long ANSWER_ID = 300;
  // Verbatim live replies to our quotes (mail 56309, 53014 and 43179).
  private static final String LIVE_56309 = "Hi there,\n\nThanks for sending over your offer! It looks good to me,"
      + " and I’d like to place the shipment with you. Another forwarder has quoted 6 percent less for the same"
      + " shipment, though. Would you be able to match their rate?\n\nThanks a lot and have a lovely day!\n"
      + "Petra Hofmann\nExport Desk, Hofmann Textilwerk KG";
  private static final String LIVE_53014 = "Thanks for the offer. It works for us, and I’d like to place the"
      + " shipment with you. Another forwarder quoted 7 percent less for the same shipment. Could you match"
      + " their rate?\n\nMira Petersen\nNordlicht Audio";
  private static final String LIVE_43179 = "Hello,\n\nThank you for your offer. It suits us well, and we would"
      + " like to place the shipment with you. However, another forwarder has offered us a rate 10 percent"
      + " lower for the same shipment.\n\nCould you match their rate?\n\nViele Gruesse / Many greetings\n"
      + "Deniz Yilmaz\nRheintal Elektronik GmbH";
  private static final String LIVE_32038 = "Hi,\n\nThanks for sending over your offer. Hope your week’s going"
      + " well!\n\nThe offer suits us, and I’d like to place the shipment with you. Another forwarder has quoted"
      + " us 7 percent less for the same shipment. Would you be able to match their price?\n\nThanks a lot and"
      + " have a lovely day!\nPetra Hofmann\nExport Desk, Hofmann Textilwerk KG";
  private static final String LIVE_31672 = "Thanks for the offer. It looks good, and we’d like to place the"
      + " shipment with you.\n\nAnother forwarder has quoted 7 percent less for the same shipment. Can you match"
      + " their rate?\n\nMira Petersen\nNordlicht Audio";
  // Verbatim live RFQ 56174: a Turkish thread whose price-match reply came in English.
  private static final String TURKISH_RFQ = "Merhaba,\n\nUmarım haftanız iyi geçiyordur. Frankfurt (FRA)"
      + " çıkışlı, Tel Aviv, IL (TLV) varışlı bir gönderimiz için hava kargo teklifi rica ediyorum.\n\nSpor"
      + " ekipmanları 2026-11-26 tarihinde hazır olacak. Gönderiyi Frankfurt (FRA) havalimanına biz teslim"
      + " edeceğiz. Toplam 7 parça, brüt ağırlığı 324 kg ve hacmi 1.96 cbm.\n\nBu gönderi için en hızlı"
      + " seçeneği istiyoruz. En erken hangi uçuşa yetişebileceğimizi ve TLV’ye tahmini varış zamanını"
      + " paylaşabilir misiniz? Teklifinizi tüm masraflar dahil EUR olarak iletebilirseniz sevinirim.\n\n"
      + "Şimdiden teşekkürler, iyi çalışmalar!\n\nThanks a lot and have a lovely day!\nPetra Hofmann\n"
      + "Export Desk, Hofmann Textilwerk KG";
  private static final String ENGLISH_RFQ = "Hi,\n\nCould you quote the fastest air freight option from FRA"
      + " to LAX, all-in in EUR?\n\nMira Petersen\nNordlicht Audio";

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final Catalog catalog = new DispatchApplication().catalog(null);
  private final FiveU upstream = mock(FiveU.class);
  private final Shipment shipment =
      new Shipment("FRA", "LAX", BigDecimal.valueOf(100), BigDecimal.ONE, "fastest", null, null, 0);
  @TempDir Path temp;
  private Ledger ledger;
  private RfqGateway gateway;
  private PriceMatchReplies priceMatch;
  private ObjectMapper mapper;

  @BeforeEach
  void setup() throws Exception {
    mapper = new ObjectMapper().findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    ledger = new Ledger(temp.resolve("dispatch.sqlite3").toString(), mapper);
    gateway = new RfqGateway(upstream, ledger, catalog, mapper, clock, "gpt-4o-mini", true);
    var worker = new WaveWorker(ledger, catalog, upstream, clock, gateway,
        mock(MailboxWebhook.class), true, START, 40, false);
    priceMatch = new PriceMatchReplies(ledger, worker, gateway, clock, true, START);
  }

  @ParameterizedTest
  @ValueSource(strings = {LIVE_56309, LIVE_53014, LIVE_43179, LIVE_32038, LIVE_31672})
  void recognisesTheLivePriceMatchReplies(String reply) {
    assertTrue(PriceMatchReplies.requested(reply));
    assertFalse(PortalBookings.accepted(reply));
    assertFalse(QuoteChecks.changesQuote(reply));
  }

  @Test
  void leavesApprovalsDeclinesAndOrdinaryRepliesAlone() {
    assertFalse(PriceMatchReplies.requested("Thank you for your offer. That works for us, please go ahead and book it."));
    assertFalse(PriceMatchReplies.requested(
        "Thank you for your offer, but your price is too high for us, so we will not go ahead with it."));
    assertFalse(PriceMatchReplies.requested("Thanks for your message. I need to check that and will come back to you."));
    assertFalse(PriceMatchReplies.requested("Sorry I left out the pickup location: 84236 Landshut, Germany."));
    assertTrue(PriceMatchReplies.requested("Could you lower your price a bit? Then we book."));
    assertTrue(PriceMatchReplies.requested("Is there any discount for a repeat customer?"));
  }

  @ParameterizedTest
  @ValueSource(strings = {LIVE_53014, LIVE_43179})
  void answersOnceInEnglishWithTheSamePriceThenBooksOnAPlainApproval(String live) {
    var quoted = quote(ENGLISH_RFQ, "Air freight quote: FRA to LAX", NOW.plusSeconds(600));
    reply(live, 101);
    when(upstream.post(eq("/v1/mailbox/messages"), anyMap())).thenReturn(Map.of("id", ANSWER_ID));
    var bookings = new PortalBookings(ledger, upstream, clock, true, gateway);

    bookings.bookAccepted();
    priceMatch.answer();
    priceMatch.answer();
    bookings.bookAccepted();

    var send = ArgumentCaptor.<Map<String, Object>>captor();
    verify(upstream).post(eq("/v1/mailbox/messages"), send.capture());
    String body = send.getValue().get("body").toString();
    assertEquals(201L, send.getValue().get("in_reply_to"));
    assertEquals("Re: Air freight quote: FRA to LAX", send.getValue().get("subject"));
    assertTrue(body.startsWith("Good morning"), body);
    assertTrue(body.contains("The price we quoted, EUR 125.00 all-in, is the carrier’s confirmed all-in rate"
        + " for this shipment, so unfortunately we cannot go below it."), body);
    assertTrue(body.contains("book it for you at the quoted price right away"), body);
    assertTrue(body.contains("The quoted rate is valid until 2026-11-22 09:56 UTC."), body);
    assertTrue(body.contains("“Please go ahead and book it.”"), body);
    assertEquals("sent", ledger.delivery("price-match:1:201"));
    assertNull(ledger.mail(1).get("booking"));
    verify(upstream, never()).currentRate(any(), any(), any());
    verify(upstream, never()).post(eq("/v1/portal/bookings"), anyMap());
    assertEquals(quoted, ledger.read(ledger.mail(1).get("proposal"), WaveWorker.Draft.class).offer());

    bookAfterApproval(bookings, quoted);
  }

  @Test
  void answersTheLiveTurkishThreadInTurkishAndBooksAfterwards() {
    var quoted = quote(TURKISH_RFQ, "FRA–TLV hava kargo teklifi", NOW.plusSeconds(600));
    reply(LIVE_56309, 101);
    when(upstream.post(eq("/v1/mailbox/messages"), anyMap())).thenReturn(Map.of("id", ANSWER_ID));
    var bookings = new PortalBookings(ledger, upstream, clock, true, gateway);

    priceMatch.answer();
    bookings.bookAccepted();
    priceMatch.answer();

    var send = ArgumentCaptor.<Map<String, Object>>captor();
    verify(upstream).post(eq("/v1/mailbox/messages"), send.capture());
    String body = send.getValue().get("body").toString();
    assertTrue(body.startsWith("Merhaba,"), body);
    assertTrue(body.contains("EUR 125.00 tutarındaki fiyat"), body);
    assertTrue(body.contains("bunun altına inemiyoruz"), body);
    assertTrue(body.contains("2026-11-22 09:56 UTC"), body);
    assertTrue(body.contains("Saygılarımızla,\nAEIOU Ekibi"), body);
    assertFalse(body.contains("cannot go below"), body);
    verify(upstream, never()).post(eq("/v1/portal/bookings"), anyMap());

    bookAfterApproval(bookings, quoted);
  }

  @Test
  void usesTheModelReadingForAPriceRequestInAnotherLanguage() {
    var quoted = quote("Bonjour,\n\nPourriez-vous nous envoyer un devis fret aérien de FRA à LAX, tarif tout"
        + " compris en EUR ?\n\nMerci d’avance,\nClaire", "Devis fret aérien FRA LAX", NOW.minusSeconds(5));
    String live = "Bonjour,\n\nVotre offre nous convient. Un autre transitaire nous propose 7 % de moins pour le"
        + " même envoi. Pouvez-vous vous aligner sur son tarif ?\n\nClaire";
    reply(live, 101);
    assertFalse(PriceMatchReplies.requested(live));
    when(upstream.directLlm()).thenReturn(true);
    when(upstream.parseRfq(anyMap())).thenReturn(mapper.valueToTree(Map.of("choices", List.of(Map.of(
        "finish_reason", "stop", "message", Map.of("content", """
            {"english": "Your offer suits us. Another forwarder offers 7% less for the same shipment.\
             Can you align with their rate?", "approval_excerpt": null, "price_request": true}
            """))))));
    when(upstream.post(eq("/v1/mailbox/messages"), anyMap())).thenReturn(Map.of("id", ANSWER_ID));
    var bookings = new PortalBookings(ledger, upstream, clock, true, gateway);

    bookings.bookAccepted();
    priceMatch.answer();
    priceMatch.answer();

    verify(upstream).parseRfq(anyMap());
    var send = ArgumentCaptor.<Map<String, Object>>captor();
    verify(upstream).post(eq("/v1/mailbox/messages"), send.capture());
    String body = send.getValue().get("body").toString();
    assertTrue(body.startsWith("Bonjour"), body);
    assertTrue(body.contains("EUR 125.00 tout compris"), body);
    assertTrue(body.contains("nous ne pouvons malheureusement pas descendre en dessous"), body);
    assertTrue(body.contains("Nous vérifierons le tarif actuel du transporteur"), body);
    assertFalse(body.contains("valable jusqu"), body);
    verify(upstream, never()).post(eq("/v1/portal/bookings"), anyMap());
    assertNotNull(quoted);
  }

  @Test
  void leavesAnOldUnansweredPriceMatchRequestForTheDesk() {
    quote(ENGLISH_RFQ, "Air freight quote: FRA to LAX", NOW.plusSeconds(600));
    ledger.ingest(Map.of("messages", List.of(Map.of(
        "id", 201L, "from", "customer@example.invalid", "subject", "Re: quote",
        "body", LIVE_53014, "sent_at", NOW.minusSeconds(3600).toString(), "in_reply_to", 101L))),
        catalog, NOW);

    priceMatch.answer();

    verify(upstream, never()).post(eq("/v1/mailbox/messages"), anyMap());
  }

  private void bookAfterApproval(PortalBookings bookings, Offer quoted) {
    reply("Thanks. Please go ahead and book it.", ANSWER_ID);
    var live = new Offer(quoted.airline(), quoted.days(), quoted.totalEur(), quoted.billedKg(), "portal",
        "R-LIVE", NOW.plusSeconds(600));
    when(upstream.currentRate(shipment, quoted, NOW)).thenReturn(live);
    var payload = Map.<String, Object>of("rate_id", "R-LIVE", "team_reference", "AEIOU-1");
    when(upstream.post("/v1/portal/bookings", payload)).thenReturn(Map.of(
        "rate_id", "R-LIVE", "team_reference", "AEIOU-1", "booking_reference", "BK-MATCH"));

    bookings.bookAccepted();
    priceMatch.answer();

    assertEquals("BK-MATCH", ledger.read(ledger.mail(1).get("booking"), Map.class).get("booking_reference"));
    verify(upstream).post("/v1/portal/bookings", payload);
    verify(upstream, times(1)).post(eq("/v1/mailbox/messages"), anyMap());
  }

  private Offer quote(String body, String subject, Instant expiry) {
    var sent = NOW.minusSeconds(40);
    var received = sent.minusSeconds(30).toString();
    var offer = new Offer("Fjordline Air Cargo", 2, new BigDecimal("125.00"),
        BigDecimal.valueOf(167), "portal", "R-QUOTE-1", expiry);
    var draft = new WaveWorker.Draft("draft", offer, List.of(offer), List.of(), "",
        null, null, null, null);
    ledger.jdbc().update("""
        INSERT INTO mail(id,root,sender,subject,body,received,kind,fields,stage,proposal)
        VALUES(1,1,'customer@example.invalid',?,?,?,'request',?,'sent',?)
        """, subject, body, received, ledger.write(shipment), ledger.write(draft));
    ledger.jdbc().update("INSERT INTO board_result VALUES(?,?)",
        received, ledger.write(Map.of("verdict", "accepted")));
    ledger.jdbc().update("INSERT INTO deliveries(action,state,response,attempted_at) VALUES(?,'sent',?,?)",
        "quote:1", ledger.write(Map.of("id", 101)), sent.toString());
    return offer;
  }

  private void reply(String body, long parent) {
    long replyId = parent + 100;
    ledger.ingest(Map.of("messages", List.of(Map.of(
        "id", replyId, "from", "customer@example.invalid", "subject", "Re: quote",
        "body", body, "sent_at", NOW.toString(), "in_reply_to", parent)),
        "next_since_id", replyId), catalog, NOW);
  }
}
