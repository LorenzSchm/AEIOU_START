package ai.aeiou.forwarder.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.aeiou.forwarder.booking.BookingRepository;
import ai.aeiou.forwarder.booking.BookingTestDb;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.router.Extraction;
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
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class TrackingHandlerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-21T15:00:00Z");
    private static final String TRACKING = "tracking@hackloop.mail";

    private JdbcClient jdbc;
    private SessionService sessions;
    private MailGateway mailGateway;
    private EmailWriter writer;
    private TrackingHandler handler;
    private long rfqSession;
    private long trackingSession;

    @BeforeEach
    void setUp() {
        jdbc = BookingTestDb.jdbc();
        BookingTestDb.reset();
        sessions = mock(SessionService.class);
        mailGateway = mock(MailGateway.class);
        writer = mock(EmailWriter.class);
        SettingsService settings = mock(SettingsService.class);
        EventLog events = mock(EventLog.class);
        when(settings.getBool(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(writer.write(any())).thenAnswer(inv -> new WrittenMail("Re: Where is my shipment", "Answer", false));

        handler = new TrackingHandler(new BookingRepository(jdbc), sessions, events, writer,
                new Outbox(mailGateway, settings, events, jdbc), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), TRACKING);

        rfqSession = BookingTestDb.session("AEI-7K2Q9", "mark@customer.example", "RFQ PO-4711 machine parts FRA-SIN");
        BookingTestDb.booking(rfqSession, "AEI-7K2Q9", "BK-7Q2M9X4L", "confirmed",
                OffsetDateTime.parse("2026-11-20T10:00:00Z"), OffsetDateTime.parse("2026-11-26T10:00:00Z"));
        trackingSession = BookingTestDb.session("AEI-TRK01", TRACKING, "Where is my shipment");
        when(sessions.find(anyLong())).thenAnswer(inv -> Optional.of(view(inv.getArgument(0))));
    }

    private static SessionView view(long id) {
        return new SessionView(id, "AEI-X", "TRACKING", SessionStatus.NEW, TRACKING, "Where is my shipment", null,
                false, true, null, null, null, null, false, NOW);
    }

    private RoutedMail tracking(String from, long sessionId, String subject, String body, String extractedRef) {
        StoredMail mail = new StoredMail(50, 5000, "in", from, "aeiou@hackloop.mail", subject, body, null, List.of(), 5000L,
                sessionId, NOW, List.of());
        Extraction extraction = new Extraction(MailType.TRACKING_QUESTION, 0.9, null, "en", "Priya", null, null,
                new Extraction.Tracking(extractedRef, "where is it"));
        return new RoutedMail(mail, MailType.TRACKING_QUESTION, 0.9, 2, sessionId, extraction);
    }

    private MailIntent writtenIntent() {
        ArgumentCaptor<MailIntent> intent = ArgumentCaptor.forClass(MailIntent.class);
        verify(writer).write(intent.capture());
        return intent.getValue();
    }

    @Test
    void answersByBookingReferenceWithComputedProgress() {
        handler.handle(tracking(TRACKING, trackingSession, "Status BK-7q2m9x4l?", "Where is BK-7Q2M9X4L right now?", null));

        MailIntent intent = writtenIntent();
        assertThat(intent.purpose()).isEqualTo(Purpose.TRACKING_ANSWER);
        assertThat(intent.mustContain()).containsExactly("BK-7Q2M9X4L");
        assertThat(intent.recipientName()).isEqualTo("Priya");
        assertThat(intent.facts())
                .containsEntry("airline", "Helix Air Freight")
                .containsEntry("origin", "Frankfurt (FRA)")
                .containsEntry("destination", "Singapore (SIN)")
                .containsEntry("status", "booked and confirmed by the airline")
                .containsEntry("eta", "Thursday, 26 November")
                .containsEntry("progress", "in transit, left Frankfurt yesterday, due in Singapore on Thursday, 26 November");

        ArgumentCaptor<OutgoingMail> sent = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(mailGateway).send(eq(trackingSession), sent.capture());
        assertThat(sent.getValue().to()).isEqualTo(TRACKING);
        assertThat(sent.getValue().inReplyToExt()).isEqualTo(5000L);
        verify(sessions).setStatus(trackingSession, SessionStatus.ANSWERED);
    }

    @Test
    void findsTheShipmentByOurOwnReference() {
        handler.handle(tracking(TRACKING, trackingSession, "Update on AEI-7K2Q9", "Any news?", "AEI-7K2Q9"));

        assertThat(writtenIntent().facts()).containsEntry("bookingReference", "BK-7Q2M9X4L");
    }

    @Test
    void findsTheShipmentByTheCustomersOwnReference() {
        handler.handle(tracking(TRACKING, trackingSession, "Our order PO-4711", "Where is our PO-4711?", "PO-4711"));

        assertThat(writtenIntent().facts()).containsEntry("bookingReference", "BK-7Q2M9X4L");
    }

    @Test
    void questionInTheRfqThreadKeepsTheBookedStatus() {
        handler.handle(tracking("mark@customer.example", rfqSession, "Re: RFQ PO-4711", "Has it left yet?", null));

        assertThat(writtenIntent().facts()).containsEntry("bookingReference", "BK-7Q2M9X4L");
        verify(sessions, never()).setStatus(anyLong(), any());
    }

    @Test
    void unknownReferenceNeedsAHumanAndLeavesADraft() {
        handler.handle(tracking(TRACKING, trackingSession, "Status BK-ZZZZZZZZ", "Where is BK-ZZZZZZZZ?", "BK-ZZZZZZZZ"));

        verify(sessions).flagNeedsHuman(eq(trackingSession), anyString());
        verify(mailGateway, never()).send(anyLong(), any());
        Map<String, Object> draft = jdbc.sql("SELECT * FROM draft WHERE session_id = ?").param(trackingSession).query().singleRow();
        assertThat(draft).containsEntry("purpose", "tracking_answer").containsEntry("to_addr", TRACKING);
        assertThat(writtenIntent().purpose()).isEqualTo(Purpose.HOLDING_REPLY);
    }
}
