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

class QuoteFollowUpsTest {
  private static final Instant NOW = Instant.parse("2026-11-21T15:10:00Z");
  private static final Instant START = NOW.minusSeconds(600);
  private static final long FOLLOW_UP_ID = 200;
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final Catalog catalog = new DispatchApplication().catalog(null);
  private final FiveU upstream = mock(FiveU.class);
  private final Shipment shipment =
      new Shipment("FRA", "LAX", BigDecimal.valueOf(100), BigDecimal.ONE, "cheapest", null, null, 0);
  @TempDir Path temp;
  private Ledger ledger;
  private QuoteFollowUps followUps;
  private RfqGateway gateway;
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
    followUps = new QuoteFollowUps(ledger, worker, clock, true, START);
  }

  @Test
  void skipsModelAndBookingForTheExplicitRound140Decline() {
    quote(1, 120, NOW.plusSeconds(60), "accepted");
    reply(1, "Thank you for your offer. That seems too low to be right, so we will not go ahead with it.", 101);
    when(upstream.directLlm()).thenReturn(true);
    new PortalBookings(ledger, upstream, clock, true, gateway).bookAccepted();
    verify(upstream, never()).parseRfq(anyMap());
    verify(upstream, never()).post(eq("/v1/portal/bookings"), anyMap());
  }

  @Test
  void sendsOneReminderAndBooksWhenTheCustomerRepliesToItAtTheSameRate() {
    var quoted = quote(1, 120, NOW.plusSeconds(60), "accepted");
    when(upstream.post(eq("/v1/mailbox/messages"), anyMap())).thenReturn(Map.of("id", FOLLOW_UP_ID));

    followUps.followUp();
    followUps.followUp();

    var send = ArgumentCaptor.<Map<String, Object>>captor();
    verify(upstream).post(eq("/v1/mailbox/messages"), send.capture());
    assertEquals(101L, send.getValue().get("in_reply_to"));
    assertEquals("sent", ledger.delivery("reply:1"));
    assertTrue(send.getValue().get("body").toString().contains("Would you like us to book your FRA to LAX shipment?"));

    reply(1, "Good evening Team AEIOU,\n\nThank you for your offer. We would like to proceed."
        + " Please go ahead and book the shipment now."
        + "\n\nKind regards\nAnna Sonnberg", FOLLOW_UP_ID);
    var live = new Offer(quoted.airline(), quoted.days(), quoted.totalEur(), quoted.billedKg(), "portal",
        "R-LIVE", NOW.plusSeconds(120));
    when(upstream.currentRate(eq(shipment), eq(quoted), eq(NOW))).thenReturn(live);
    when(upstream.post("/v1/portal/bookings", Map.of("rate_id", "R-LIVE", "team_reference", "AEIOU-1")))
        .thenReturn(Map.of("rate_id", "R-LIVE", "team_reference", "AEIOU-1", "booking_reference", "BK-TEST"));
    new PortalBookings(ledger, upstream, clock, true).bookAccepted();
    assertEquals("BK-TEST", ledger.read(ledger.mail(1).get("booking"), Map.class).get("booking_reference"));
  }

  @Test
  void needsAnUnconditionalBookingInstruction() {
    assertFalse(PortalBookings.accepted("Thanks for your message. I need to check that and will come back to you."));
    assertFalse(PortalBookings.accepted("Thank you for your offer. We would like to proceed."));
    assertFalse(PortalBookings.accepted(
        "We would like to proceed. Please go ahead and book the shipment now if the rate drops."));
  }

  @Test
  void booksOnceWhenTheCustomerApprovesTheQuoteInTurkish() {
    var quoted = quote(1, 120, NOW.plusSeconds(60), "accepted");
    reply(1, "Merhaba,\n\nTeklifiniz için teşekkür ederiz. Bizim için uygundur, lütfen rezervasyonu yapın."
        + "\n\nSaygılarımla,\nSara\nLindqvist Medtech", 101);
    var live = new Offer(quoted.airline(), quoted.days(), quoted.totalEur(), quoted.billedKg(), "portal",
        "R-TURKISH", NOW.plusSeconds(120));
    var payload = Map.<String, Object>of("rate_id", "R-TURKISH", "team_reference", "AEIOU-1");
    when(upstream.currentRate(shipment, quoted, NOW)).thenReturn(live);
    when(upstream.post("/v1/portal/bookings", payload)).thenReturn(Map.of(
        "rate_id", "R-TURKISH", "team_reference", "AEIOU-1", "booking_reference", "BK-TURKISH"));
    var bookings = new PortalBookings(ledger, upstream, clock, true);

    bookings.bookAccepted();
    bookings.bookAccepted();

    assertEquals("BK-TURKISH", ledger.read(ledger.mail(1).get("booking"), Map.class).get("booking_reference"));
    verify(upstream).currentRate(shipment, quoted, NOW);
    verify(upstream).post("/v1/portal/bookings", payload);
  }

  @Test
  void ignoresConditionalAndQuotedTurkishBookingInstructions() {
    assertTrue(PortalBookings.accepted("Lütfen bu gönderi için rezervasyon yapınız.\n\nSara\nLindqvist Medtech"));
    assertTrue(PortalBookings.accepted("Teklifiniz için teşekkür ediyorum. Rezervasyonu yapmanızı rica ederiz."));
    assertFalse(PortalBookings.accepted("Teklifiniz için teşekkür ederiz. Bizim için uygundur."));
    assertFalse(PortalBookings.accepted("Fiyat düşerse lütfen rezervasyonu yapın."));
    assertFalse(PortalBookings.accepted("Lütfen rezervasyonu yapın.\n\nSaygılarımla,\nSara\nPS: Lütfen bekleyin."));
    assertFalse(PortalBookings.accepted("Rezervasyon yapabilir misiniz?"));
    assertFalse(PortalBookings.accepted("Lütfen rezervasyonu yapmayın."));
    assertFalse(PortalBookings.accepted("Teşekkürler.\n\n> Lütfen rezervasyonu yapın."));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void booksAnExplicitModelInterpretedReplyAndRejectsAConditionalOne(boolean approved) {
    var quoted = quote(1, 120, NOW.plusSeconds(60), "accepted");
    String instruction = "이 조건으로 예약을 진행해 주세요.";
    reply(1, approved ? instruction : "가격이 내려가면 예약을 진행해 주세요.", 101);
    when(upstream.directLlm()).thenReturn(true);
    String english = "Please proceed with the booking under these conditions.";
    String content = approved
        ? ledger.write(Map.of("english", english, "approval_excerpt", instruction))
        : ledger.write(Map.of("english", "Please book it if the price goes down.",
            "approval_excerpt", "가격이 내려가면 예약을 진행해 주세요."));
    when(upstream.parseRfq(anyMap())).thenReturn(mapper.valueToTree(Map.of("choices", List.of(
        Map.of("finish_reason", "stop", "message", Map.of("content", content))))));
    var live = new Offer(quoted.airline(), quoted.days(), quoted.totalEur(), quoted.billedKg(), "portal",
        "R-CURRENT", NOW.plusSeconds(120));
    when(upstream.currentRate(shipment, quoted, NOW)).thenReturn(live);
    var payload = Map.<String, Object>of("rate_id", "R-CURRENT", "team_reference", "AEIOU-1");
    when(upstream.post("/v1/portal/bookings", payload)).thenReturn(Map.of(
        "rate_id", "R-CURRENT", "team_reference", "AEIOU-1", "booking_reference", "BK-LOCALIZED"));

    var bookings = new PortalBookings(ledger, upstream, clock, true, gateway);
    bookings.bookAccepted();
    bookings.bookAccepted();

    verify(upstream).parseRfq(anyMap());
    if (approved) {
      assertEquals("BK-LOCALIZED", ledger.read(ledger.mail(1).get("booking"), Map.class).get("booking_reference"));
      verify(upstream).post("/v1/portal/bookings", payload);
    } else {
      assertNull(ledger.mail(1).get("booking"));
      verify(upstream, never()).post(eq("/v1/portal/bookings"), anyMap());
    }
  }

  @Test
  void updatedQuoteRequiresADirectNewApprovalAndReplacesTheOldHold() {
    var original = quote(1, 120, NOW.plusSeconds(60), "accepted");
    var updated = new Offer(original.airline(), original.days(), new BigDecimal("115.00"),
        original.billedKg(), "portal", "R-UPDATED", NOW.plusSeconds(120));
    var draft = new WaveWorker.Draft("updated", updated, List.of(updated), List.of(), "",
        null, null, null, null);
    ledger.jdbc().update("""
        INSERT INTO booking_attempt(request_id,rate_id,submitted,retry_at,issue)
        VALUES(1,?,0,?,'Earlier rate changed')
        """, original.rateId(), Long.MAX_VALUE);
    ledger.jdbc().update("""
        INSERT INTO deliveries(action,state,response,payload) VALUES('requote:1','sent',?,?)
        """, ledger.write(Map.of("id", 150)), ledger.write(Map.of("updated_quote", draft)));
    var bookings = new PortalBookings(ledger, upstream, clock, true);

    reply(1, "Please go ahead and book it.", 101);
    bookings.bookAccepted();
    assertThrows(WaveWorker.Conflict.class, () -> bookings.book(1));
    verifyNoInteractions(upstream);

    reply(1, "Please go ahead and book it.", 150);
    when(upstream.currentRate(shipment, updated, NOW)).thenReturn(updated);
    when(upstream.post("/v1/portal/bookings", Map.of("rate_id", "R-UPDATED", "team_reference", "AEIOU-1")))
        .thenReturn(Map.of("rate_id", "R-UPDATED", "team_reference", "AEIOU-1", "booking_reference", "BK-UPDATED"));
    bookings.bookAccepted();
    assertEquals("BK-UPDATED", ledger.read(ledger.mail(1).get("booking"), Map.class).get("booking_reference"));
  }

  @Test
  void skipsRecentExpiredRejectedPreviouslyContactedAndAnsweredQuotes() {
    quote(1, 119, NOW.plusSeconds(60), "accepted");
    quote(2, 130, NOW, "accepted");
    quote(3, 140, NOW.plusSeconds(60), "rejected");
    quote(4, 140, NOW.plusSeconds(60), "accepted");
    quote(5, 140, NOW.plusSeconds(60), "accepted");
    ledger.jdbc().update("INSERT INTO deliveries(action,state) VALUES('reply:4','sent')");
    reply(5, "I need to check and will get back to you.", 105);

    followUps.followUp();

    verifyNoInteractions(upstream);
  }

  @Test
  void live53112DoesNotBookAnAcceptanceTheCustomerCancelsTwentySecondsLater() {
    var quoted = quote(1, 120, NOW.plusSeconds(600), "accepted");
    var live = new Offer(quoted.airline(), quoted.days(), quoted.totalEur(), quoted.billedKg(), "portal",
        "R-LIVE", NOW.plusSeconds(600));
    when(upstream.currentRate(any(), any(), any())).thenReturn(live);
    when(upstream.post(eq("/v1/portal/bookings"), anyMap())).thenReturn(Map.of(
        "rate_id", "R-LIVE", "team_reference", "AEIOU-1", "booking_reference", "BK-42O0OC0Z"));
    ledger.ingest(Map.of("messages", List.of(Map.of("id", 201L, "from", customer(1), "subject", "Re: RFQ FRA to LAX",
        "body", "Thank you for your offer. That works for us, please go ahead and book it.\n\n"
            + "Mit freundlichen Gruessen / Kind regards\nJonas Kessler", "sent_at", NOW.minusSeconds(10).toString(),
        "in_reply_to", 101L)), "next_since_id", 201L), catalog, NOW);
    new PortalBookings(ledger, upstream, clock, true).bookAccepted();
    verify(upstream, never()).post(eq("/v1/portal/bookings"), anyMap());

    ledger.ingest(Map.of("messages", List.of(Map.of("id", 202L, "from", customer(1), "subject", "Re: RFQ FRA to LAX",
        "body", "Our buyer has withdrawn the order, so we need to cancel the shipment. Please do not proceed with"
            + " the booking. If you have already booked the shipment, please cancel the booking.",
        "sent_at", NOW.plusSeconds(9).toString(), "in_reply_to", 101L)), "next_since_id", 202L), catalog, NOW);
    new PortalBookings(ledger, upstream, Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC), true).bookAccepted();
    verify(upstream, never()).post(eq("/v1/portal/bookings"), anyMap());
    assertNull(ledger.mail(1).get("booking"));
  }

  private Offer quote(long id, long age, Instant expiry, String verdict) {
    var sent = NOW.minusSeconds(age);
    var received = sent.minusSeconds(20 + id).toString();
    var offer = new Offer("Fjordline Air Cargo", 2, new BigDecimal("125.00"),
        BigDecimal.valueOf(167), "portal", "R-QUOTE-" + id, expiry);
    var draft = new WaveWorker.Draft("draft", offer, List.of(offer), List.of(), "",
        null, null, null, null);
    ledger.jdbc().update("""
        INSERT INTO mail(id,root,sender,subject,body,received,kind,fields,stage,proposal)
        VALUES(?,?,?,'RFQ FRA to LAX','Please send a quote.',?,'request',?,'sent',?)
        """, id, id, customer(id), received, ledger.write(shipment), ledger.write(draft));
    ledger.jdbc().update("INSERT INTO board_result VALUES(?,?)",
        received, ledger.write(Map.of("verdict", verdict)));
    ledger.jdbc().update("INSERT INTO deliveries(action,state,response,attempted_at) VALUES(?,'sent',?,?)",
        "quote:" + id, ledger.write(Map.of("id", id + 100)), sent.toString());
    return offer;
  }

  private void reply(long id, String body, long parent) {
    long replyId = parent + 100;
    ledger.ingest(Map.of("messages", List.of(Map.of(
        "id", replyId, "from", customer(id), "subject", "Re: RFQ FRA to LAX",
        "body", body, "sent_at", NOW.minusSeconds(PortalBookings.BOOKING_GRACE_SECONDS).toString(), "in_reply_to", parent)),
        "next_since_id", replyId), catalog, NOW);
  }

  private String customer(long id) {
    return "customer" + id + "@example.invalid";
  }
}
