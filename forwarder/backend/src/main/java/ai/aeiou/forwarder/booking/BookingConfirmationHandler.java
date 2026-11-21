package ai.aeiou.forwarder.booking;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.router.MailHandler;
import ai.aeiou.forwarder.router.MailType;
import ai.aeiou.forwarder.router.RoutedMail;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.session.SessionService.SessionView;
import ai.aeiou.forwarder.session.SessionStatus;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * "Booking BK-xxxxxxxx confirmed" from the airline: marks our booking confirmed and, if the customer has not heard
 * about the booking yet (email bookings), tells them.
 */
@Component
public class BookingConfirmationHandler implements MailHandler {

    static final Pattern BOOKING_REF = Pattern.compile("\\bBK-[A-Z0-9]{8}\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern TEAM_REF = Pattern.compile("\\bAEI-[A-Z0-9]{3,}\\b", Pattern.CASE_INSENSITIVE);

    private final BookingRepository repo;
    private final SessionService sessions;
    private final EventLog events;
    private final CustomerNotifier notifier;

    public BookingConfirmationHandler(BookingRepository repo, SessionService sessions, EventLog events, CustomerNotifier notifier) {
        this.repo = repo;
        this.sessions = sessions;
        this.events = events;
        this.notifier = notifier;
    }

    @Override
    public Set<MailType> supports() {
        return Set.of(MailType.BOOKING_CONFIRMATION);
    }

    @Override
    public void handle(RoutedMail routed) {
        StoredMail mail = routed.mail();
        String text = nullToEmpty(mail.subject()) + "\n" + nullToEmpty(mail.body());
        String bookingRef = find(BOOKING_REF, text).map(s -> s.toUpperCase(Locale.ROOT)).orElse(null);

        Optional<BookingRecord> match = Optional.ofNullable(bookingRef).flatMap(repo::findByBookingReference)
                .or(() -> find(TEAM_REF, text).flatMap(repo::findByTeamReference))
                .or(() -> repo.findRequestedForAirlineEmail(mail.fromAddr()))
                .or(() -> Optional.ofNullable(routed.sessionId()).flatMap(repo::findActiveBySession));
        if (match.isEmpty()) {
            if (routed.sessionId() != null) {
                sessions.flagNeedsHuman(routed.sessionId(), "Booking confirmation " + (bookingRef == null ? "without reference" : bookingRef)
                        + " matches none of our bookings");
            }
            return;
        }
        BookingRecord booking = match.get();
        long sessionId = booking.sessionId();
        if (BookingRecord.CONFIRMED.equals(booking.status())) {
            events.append(sessionId, mail.id(), "booking.duplicate", Actor.system,
                    "Confirmation for " + booking.bookingReference() + " received again", Payloads.of("bookingId", booking.id()));
            return;
        }

        repo.confirm(booking.id(), bookingRef);
        sessions.setStatus(sessionId, SessionStatus.CONFIRMED);
        sessions.markBooked(sessionId);
        BookingRecord confirmed = repo.findById(booking.id()).orElseThrow();
        events.append(sessionId, mail.id(), "booking.confirmed", Actor.system,
                "Airline confirmed " + (confirmed.bookingReference() != null ? confirmed.bookingReference() : booking.teamReference()),
                Payloads.of("bookingId", booking.id(), "bookingReference", confirmed.bookingReference(), "previousStatus", booking.status()));

        if (!repo.hasEvent(sessionId, CustomerNotifier.NOTIFIED_EVENT)) {
            SessionView session = sessions.find(sessionId).orElseThrow();
            notifier.bookingConfirmed(session, confirmed, null);
        }
    }

    private static Optional<String> find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
