package ai.aeiou.forwarder.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import ai.aeiou.forwarder.booking.port.FiveUBookingPortal;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.fiveu.FiveUClient;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.router.MailType;
import ai.aeiou.forwarder.router.RoutedMail;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.session.SessionService.SessionView;
import ai.aeiou.forwarder.session.SessionStatus;
import ai.aeiou.forwarder.settings.SettingsService;
import ai.aeiou.forwarder.writer.EmailWriter;
import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import ai.aeiou.forwarder.writer.EmailWriter.Purpose;
import ai.aeiou.forwarder.writer.EmailWriter.WrittenMail;
import ai.aeiou.forwarder.writer.Outbox;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Acceptance and airline confirmation against a real Postgres, with the 5U portal behind MockRestServiceServer. */
class BookingWorkflowTest {

    private static final String BASE = "http://portal.test";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-21T15:00:00Z");
    private static final String CUSTOMER = "mark@customer.example";
    private static final String REF = "AEI-7K2Q9";

    private JdbcClient jdbc;
    private MockRestServiceServer server;
    private SessionService sessions;
    private MailGateway mailGateway;
    private EmailWriter writer;
    private SettingsService settings;
    private AcceptanceHandler acceptance;
    private BookingConfirmationHandler confirmation;
    private long sessionId;
    private StoredMail acceptanceMail;

    @BeforeEach
    void setUp() {
        jdbc = BookingTestDb.jdbc();
        BookingTestDb.reset();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();

        sessions = mock(SessionService.class);
        mailGateway = mock(MailGateway.class);
        writer = mock(EmailWriter.class);
        settings = mock(SettingsService.class);
        EventLog events = mock(EventLog.class);
        when(settings.getBool(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBool(eq("autopilot"), anyBoolean())).thenReturn(true);  // the live switch: answer automatically
        when(writer.write(any())).thenAnswer(inv -> {
            MailIntent intent = inv.getArgument(0);
            return new WrittenMail("Re: RFQ FRA-SIN", "Mail for " + intent.purpose() + " " + intent.mustContain(), false);
        });

        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        BookingRepository repo = new BookingRepository(jdbc);
        Outbox outbox = new Outbox(mailGateway, settings, events, jdbc);
        CustomerNotifier notifier = new CustomerNotifier(writer, outbox, mailGateway, repo, sessions, events, clock);
        acceptance = new AcceptanceHandler(new FiveUBookingPortal(new FiveUClient(builder.build(), JsonMapper.builder().build())), repo, sessions, events, writer, outbox,
                mailGateway, notifier, clock);
        confirmation = new BookingConfirmationHandler(repo, sessions, events, notifier);

        sessionId = BookingTestDb.session(REF, CUSTOMER, "RFQ FRA-SIN");
        BookingTestDb.rfq(sessionId, LocalDate.parse("2026-11-23"), "en", "Mark");
        long mailId = BookingTestDb.mail(9001, sessionId, "in", CUSTOMER, "Re: RFQ FRA-SIN", "Sounds good, please book.");
        acceptanceMail = new StoredMail(mailId, 9001, "in", CUSTOMER, "aeiou@hackloop.mail", "Re: RFQ FRA-SIN",
                "Sounds good, please book.", null, List.of(), 9001L, sessionId, NOW, List.of());
        when(mailGateway.findByExternalId(9001)).thenReturn(Optional.of(acceptanceMail));
        sessionIs(false, true);
    }

    private void sessionIs(boolean needsHuman, boolean autopilot) {
        when(sessions.find(sessionId)).thenReturn(Optional.of(new SessionView(sessionId, REF, "RFQ", SessionStatus.QUOTED, CUSTOMER,
                "RFQ FRA-SIN", "cheapest", needsHuman, autopilot, null, NOW.minusMinutes(5), new BigDecimal("1387.35"), "EUR", false,
                NOW.minusMinutes(10))));
    }

    private void accept() {
        acceptance.handle(new RoutedMail(acceptanceMail, MailType.ACCEPTANCE, 0.95, 2, sessionId, null));
    }

    private void portalCandidate(OffsetDateTime validUntil) {
        BookingTestDb.candidate(sessionId, "portal", "HX", "Helix Air Freight", "1387.35", "R-abcdefgh12", validUntil);
    }

    private void expectBooking(String rateId, HttpStatus status, String bookingRef) {
        var response = withStatus(status).contentType(MediaType.APPLICATION_JSON);
        server.expect(requestTo(BASE + "/v1/portal/bookings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.rate_id").value(rateId))
                .andExpect(jsonPath("$.team_reference").value(REF))
                .andRespond(bookingRef == null ? response.body("{\"detail\":\"x\"}") : response.body("""
                        {"booking_reference":"%s","team_reference":"%s","rate_id":"%s","airline":"Helix Air Freight",
                         "airline_code":"HX","price":"1387.35","currency":"EUR","status":"booked"}""".formatted(bookingRef, REF, rateId)));
    }

    private void expectRate(String rateId, String total) {
        server.expect(requestTo(BASE + "/v1/portal/rates?origin=FRA&destination=SIN&airline=HX&weight_kg=250&volume_cbm=1.2"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                        {"rate_id":"%s","airline":"Helix Air Freight","airline_code":"HX","origin":"FRA","destination":"SIN",
                         "total":"%s","currency":"EUR","transit_days":5,"valid_until":"2026-11-21T15:15:00Z"}""".formatted(rateId, total)));
    }

    private Map<String, Object> bookingRow() {
        return jdbc.sql("SELECT * FROM booking WHERE session_id = ?").param(sessionId).query().singleRow();
    }

    private long bookingCount() {
        return jdbc.sql("SELECT count(*) FROM booking").query(Long.class).single();
    }

    private List<OutgoingMail> sentMails(int expected) {
        ArgumentCaptor<OutgoingMail> mails = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(mailGateway, times(expected)).send(eq(sessionId), mails.capture());
        return mails.getAllValues();
    }

    @Test
    void portalBookingIsStoredAndConfirmedToTheCustomer() {
        portalCandidate(NOW.plusMinutes(10));
        expectBooking("R-abcdefgh12", HttpStatus.CREATED, "BK-7Q2M9X4L");

        accept();

        server.verify();
        Map<String, Object> row = bookingRow();
        assertThat(row).containsEntry("status", "booked").containsEntry("booking_reference", "BK-7Q2M9X4L")
                .containsEntry("team_reference", REF).containsEntry("origin", "FRA").containsEntry("transit_days", 4);
        // Ready date 23 Nov, start of day in Berlin (CET = UTC+1); ETA = departure + 4 transit days.
        assertThat(((java.sql.Timestamp) row.get("departure_at")).toInstant()).isEqualTo(Instant.parse("2026-11-22T23:00:00Z"));
        assertThat(((java.sql.Timestamp) row.get("eta_at")).toInstant()).isEqualTo(Instant.parse("2026-11-26T23:00:00Z"));
        verify(sessions).setStatus(sessionId, SessionStatus.BOOKED);
        verify(sessions).markBooked(sessionId);

        OutgoingMail mail = sentMails(1).getFirst();
        assertThat(mail.to()).isEqualTo(CUSTOMER);
        assertThat(mail.inReplyToExt()).isEqualTo(9001L);
        assertThat(mail.body()).contains("BOOKING_CONFIRMATION").contains("BK-7Q2M9X4L").contains("EUR 1,387.35");
    }

    @Test
    void expiredRateIsRequotedAndBookedWhenWithinOnePercent() {
        portalCandidate(NOW.plusMinutes(10));
        expectBooking("R-abcdefgh12", HttpStatus.GONE, null);
        expectRate("R-freshrate01", "1390.00");
        expectBooking("R-freshrate01", HttpStatus.CREATED, "BK-AB12CD34");

        accept();

        server.verify();
        assertThat(bookingRow()).containsEntry("rate_id", "R-freshrate01").containsEntry("status", "booked")
                .containsEntry("transit_days", 5);
        sentMails(1);
    }

    @Test
    void knownExpiredRateGoesStraightToRequoteAndAPriceJumpIsBookedAndFlagged() {
        portalCandidate(NOW.minusMinutes(1));
        expectRate("R-freshrate01", "1450.00");
        expectBooking("R-freshrate01", HttpStatus.CREATED, "BK-JUMP0001");

        accept();

        server.verify();
        assertThat(bookingRow()).containsEntry("status", "booked").containsEntry("rate_id", "R-freshrate01");
        verify(sessions).flagNeedsHuman(eq(sessionId), contains("more than 1%"));
        assertThat(sentMails(1).getFirst().to()).isEqualTo(CUSTOMER);
    }

    @Test
    void conflictBooksAFreshRateAutomatically() {
        portalCandidate(NOW.plusMinutes(10));
        expectBooking("R-abcdefgh12", HttpStatus.CONFLICT, null);
        expectRate("R-freshrate02", "1387.35");
        expectBooking("R-freshrate02", HttpStatus.CREATED, "BK-CONF0001");

        accept();

        server.verify();
        assertThat(bookingRow()).containsEntry("status", "booked").containsEntry("booking_reference", "BK-CONF0001");
    }

    @Test
    void portalDownBooksByEmailWithTheAirlineAndTellsTheCustomer() {
        portalCandidate(NOW.plusMinutes(10));
        expectBooking("R-abcdefgh12", HttpStatus.SERVICE_UNAVAILABLE, null);

        accept();

        assertThat(bookingRow()).containsEntry("status", "requested").containsEntry("booking_reference", null);
        List<OutgoingMail> mails = sentMails(2);
        assertThat(mails.get(0).to()).isEqualTo("helix-air-freight@hackloop.mail");
        assertThat(mails.get(0).body()).contains("BOOKING_REQUEST").contains(REF);
        assertThat(mails.get(1).to()).isEqualTo(CUSTOMER);
        assertThat(mails.get(1).body()).contains("HOLDING_REPLY");
        verify(sessions, never()).markBooked(anyLong());
    }

    @Test
    void sheetRateIsBookedByEmailWithoutThePortal() {
        BookingTestDb.candidate(sessionId, "sheet", null, "Kestrel Airways", "1200.00", null, null);
        server.expect(ExpectedCount.never(), requestTo(BASE + "/v1/portal/bookings"));

        accept();

        server.verify();
        ArgumentCaptor<MailIntent> intents = ArgumentCaptor.forClass(MailIntent.class);
        verify(writer, times(2)).write(intents.capture());
        MailIntent request = intents.getAllValues().getFirst();
        assertThat(request.purpose()).isEqualTo(Purpose.BOOKING_REQUEST);
        assertThat(request.facts()).containsEntry("agreedRate", "EUR 1,200.00").containsEntry("weightKg", "250")
                .containsEntry("ourReference", REF);
        assertThat(sentMails(2).getFirst().to()).isEqualTo("kestrel-airways@hackloop.mail");
    }

    @Test
    void autopilotOffWritesADraftInsteadOfMailingTheCustomer() {
        sessionIs(false, false);
        portalCandidate(NOW.plusMinutes(10));
        expectBooking("R-abcdefgh12", HttpStatus.CREATED, "BK-7Q2M9X4L");

        accept();

        assertThat(bookingRow()).containsEntry("status", "booked");
        verify(mailGateway, never()).send(anyLong(), any());
        Map<String, Object> draft = jdbc.sql("SELECT * FROM draft WHERE session_id = ?").param(sessionId).query().singleRow();
        assertThat(draft).containsEntry("purpose", "booking_confirmation").containsEntry("to_addr", CUSTOMER)
                .containsEntry("in_reply_to_ext", 9001L);
    }

    @Test
    void secondAcceptanceDoesNotBookTwice() {
        portalCandidate(NOW.plusMinutes(10));
        BookingTestDb.booking(sessionId, REF, "BK-7Q2M9X4L", "booked", NOW, NOW.plusDays(4));
        server.expect(ExpectedCount.never(), requestTo(BASE + "/v1/portal/bookings"));

        accept();

        server.verify();
        assertThat(bookingCount()).isOne();
    }

    @Test
    void airlineConfirmationOfAnEmailBookingConfirmsAndInformsTheCustomer() {
        BookingTestDb.booking(sessionId, REF, null, "requested", NOW, NOW.plusDays(4));
        StoredMail airline = new StoredMail(77, 9100, "in", "helix-air-freight@hackloop.mail", "aeiou@hackloop.mail",
                "Booking BK-ab12cd34 confirmed", "Your booking for " + REF + " is confirmed.", null, List.of(), 9100L, null, NOW, List.of());

        confirmation.handle(new RoutedMail(airline, MailType.BOOKING_CONFIRMATION, 1.0, 1, null, null));

        Map<String, Object> row = bookingRow();
        assertThat(row).containsEntry("status", "confirmed").containsEntry("booking_reference", "BK-AB12CD34");
        assertThat(row.get("confirmed_at")).isNotNull();
        verify(sessions).setStatus(sessionId, SessionStatus.CONFIRMED);
        OutgoingMail mail = sentMails(1).getFirst();
        assertThat(mail.to()).isEqualTo(CUSTOMER);
        assertThat(mail.body()).contains("BK-AB12CD34");
    }

    @Test
    void confirmationAfterACustomerWasAlreadyInformedStaysQuiet() {
        long bookingId = BookingTestDb.booking(sessionId, REF, "BK-7Q2M9X4L", "booked", NOW, NOW.plusDays(4));
        jdbc.sql("INSERT INTO event (session_id, kind, actor, summary) VALUES (?, ?, 'system', 'sent')")
                .param(sessionId).param(CustomerNotifier.NOTIFIED_EVENT).update();
        StoredMail airline = new StoredMail(78, 9101, "in", "noreply@hackloop.mail", "aeiou@hackloop.mail",
                "Booking BK-7Q2M9X4L confirmed", "Booked and ready to ship.", null, List.of(), 9101L, null, NOW, List.of());

        confirmation.handle(new RoutedMail(airline, MailType.BOOKING_CONFIRMATION, 1.0, 1, null, null));

        assertThat(jdbc.sql("SELECT status FROM booking WHERE id = ?").param(bookingId).query(String.class).single()).isEqualTo("confirmed");
        verify(mailGateway, never()).send(anyLong(), any());
    }
}
