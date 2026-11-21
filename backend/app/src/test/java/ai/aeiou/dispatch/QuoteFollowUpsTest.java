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
import org.mockito.ArgumentCaptor;

class QuoteFollowUpsTest {
  private static final Instant NOW = Instant.parse("2026-11-21T15:10:00Z");
  private static final Instant START = NOW.minusSeconds(600);
  private static final long FOLLOW_UP_ID = 200;
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final Catalog catalog = new DispatchApplication().catalog();
  private final FiveU upstream = mock(FiveU.class);
  private final Shipment shipment =
      new Shipment("FRA", "LAX", BigDecimal.valueOf(100), BigDecimal.ONE, "cheapest", null, null, 0);
  @TempDir Path temp;
  private Ledger ledger;
  private QuoteFollowUps followUps;

  @BeforeEach
  void setup() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    ledger = new Ledger(temp.resolve("dispatch.sqlite3").toString(), mapper);
    var worker = new WaveWorker(ledger, catalog, upstream, clock, mock(RfqGateway.class),
        mock(MailboxWebhook.class), true, START, 40, false);
    followUps = new QuoteFollowUps(ledger, worker, clock, true, START);
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
        "body", body, "sent_at", NOW.toString(), "in_reply_to", parent)),
        "next_since_id", replyId), catalog, NOW);
  }

  private String customer(long id) {
    return "customer" + id + "@example.invalid";
  }
}
