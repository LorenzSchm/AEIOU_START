package ai.aeiou.forwarder.tracking;

import ai.aeiou.forwarder.booking.BookingRecord;
import ai.aeiou.forwarder.booking.BookingRepository;
import ai.aeiou.forwarder.booking.Payloads;
import ai.aeiou.forwarder.booking.ShipmentFacts;
import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.router.Extraction;
import ai.aeiou.forwarder.router.MailHandler;
import ai.aeiou.forwarder.router.MailType;
import ai.aeiou.forwarder.router.RoutedMail;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.session.SessionService.SessionView;
import ai.aeiou.forwarder.session.SessionStatus;
import ai.aeiou.forwarder.writer.EmailWriter;
import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import ai.aeiou.forwarder.writer.EmailWriter.Purpose;
import ai.aeiou.forwarder.writer.EmailWriter.WrittenMail;
import ai.aeiou.forwarder.writer.Facts;
import ai.aeiou.forwarder.writer.Outbox;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Answers "where is my shipment" from our own CRM (ARCHITECTURE.md §4.2). The booking is found by, in order: an
 * explicit reference (airline BK-…, our AEI-… / team reference, or the customer's own reference found in their RFQ
 * thread), the mail thread, the session, and finally the customer's latest booking. No match: a human takes over.
 */
@Component
public class TrackingHandler implements MailHandler {

    private static final Logger log = LoggerFactory.getLogger(TrackingHandler.class);
    private static final Pattern BOOKING_REF = Pattern.compile("\\bBK-[A-Z0-9]{8}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEAM_REF = Pattern.compile("\\bAEI-[A-Z0-9]{3,}\\b", Pattern.CASE_INSENSITIVE);
    private static final int MIN_CUSTOMER_REF_LENGTH = 4;

    private final BookingRepository repo;
    private final SessionService sessions;
    private final EventLog events;
    private final EmailWriter writer;
    private final Outbox outbox;
    private final Clock clock;
    private final String trackingAddress;

    @Autowired
    public TrackingHandler(BookingRepository repo, SessionService sessions, EventLog events, EmailWriter writer, Outbox outbox,
                           ObjectProvider<Clock> clock,
                           @Value("${app.fiveu.tracking-address:tracking@hackloop.mail}") String trackingAddress) {
        this(repo, sessions, events, writer, outbox, clock.getIfAvailable(Clock::systemUTC), trackingAddress);
    }

    TrackingHandler(BookingRepository repo, SessionService sessions, EventLog events, EmailWriter writer, Outbox outbox,
                    Clock clock, String trackingAddress) {
        this.repo = repo;
        this.sessions = sessions;
        this.events = events;
        this.writer = writer;
        this.outbox = outbox;
        this.clock = clock;
        this.trackingAddress = trackingAddress;
    }

    @Override
    public Set<MailType> supports() {
        return Set.of(MailType.TRACKING_QUESTION);
    }

    @Override
    public void handle(RoutedMail routed) {
        Long sessionId = routed.sessionId();
        if (sessionId == null) {
            log.warn("Tracking mail {} has no session", routed.mail().id());
            return;
        }
        StoredMail mail = routed.mail();
        Extraction extraction = routed.extraction();
        String language = extraction != null && extraction.language() != null ? extraction.language() : "en";
        String name = extraction == null ? null : extraction.senderName();
        SessionView session = sessions.find(sessionId).orElseThrow();

        Optional<Match> match = locate(routed);
        if (match.isEmpty()) {
            notFound(session, mail, language, name);
            return;
        }
        BookingRecord booking = match.get().booking();
        Map<String, Object> facts = ShipmentFacts.forBooking(booking, null, null, language, OffsetDateTime.now(clock),
                iata -> repo.airportCity(iata).orElse(null));
        String reference = booking.bookingReference() != null ? booking.bookingReference() : booking.teamReference();
        WrittenMail written = writer.write(new MailIntent(sessionId, Purpose.TRACKING_ANSWER, List.of(mail), facts,
                List.of(reference), language, name, null));
        Outbox.Delivery delivery = outbox.deliver(session, reply(mail, written), "tracking_answer");

        events.append(sessionId, mail.id(), "tracking.answered", Actor.system,
                "Tracking answer for " + reference + " (matched by " + match.get().by() + ")",
                Payloads.of("bookingId", booking.id(), "matchedBy", match.get().by(), "progress", facts.get(Facts.PROGRESS),
                        "delivery", delivery.name(), "fromTemplate", written.fromTemplate()));
        // A question inside the RFQ thread must not push a booked session back to ANSWERED.
        if (booking.sessionId() != sessionId) {
            sessions.setStatus(sessionId, SessionStatus.ANSWERED);
        }
    }

    record Match(BookingRecord booking, String by) {}

    Optional<Match> locate(RoutedMail routed) {
        StoredMail mail = routed.mail();
        String text = nullToEmpty(mail.subject()) + "\n" + nullToEmpty(mail.body());
        String extracted = routed.extraction() == null || routed.extraction().tracking() == null
                ? null : routed.extraction().tracking().reference();

        Set<String> bookingRefs = new LinkedHashSet<>();
        Set<String> teamRefs = new LinkedHashSet<>();
        collect(BOOKING_REF, extracted, bookingRefs);
        collect(TEAM_REF, extracted, teamRefs);
        collect(BOOKING_REF, text, bookingRefs);
        collect(TEAM_REF, text, teamRefs);

        for (String ref : bookingRefs) {
            Optional<BookingRecord> b = repo.findByBookingReference(ref);
            if (b.isPresent()) return Optional.of(new Match(b.get(), "booking reference " + ref));
        }
        for (String ref : teamRefs) {
            Optional<BookingRecord> b = repo.findByTeamReference(ref);
            if (b.isPresent()) return Optional.of(new Match(b.get(), "our reference " + ref));
        }
        boolean explicitReference = !bookingRefs.isEmpty() || !teamRefs.isEmpty();
        if (extracted != null && !extracted.isBlank() && bookingRefs.isEmpty() && teamRefs.isEmpty()) {
            String ref = extracted.strip();
            explicitReference = true;
            Optional<BookingRecord> b = repo.findByTeamReference(ref);
            if (b.isPresent()) return Optional.of(new Match(b.get(), "team reference " + ref));
            if (ref.length() >= MIN_CUSTOMER_REF_LENGTH) {
                List<BookingRecord> hits = repo.findByCustomerText(ref);
                if (!hits.isEmpty()) return Optional.of(new Match(hits.getFirst(), "customer reference " + ref));
            }
        }
        if (mail.threadRootExt() != null) {
            Optional<BookingRecord> b = repo.findByThread(mail.threadRootExt());
            if (b.isPresent()) return Optional.of(new Match(b.get(), "thread"));
        }
        if (routed.sessionId() != null) {
            Optional<BookingRecord> b = repo.findActiveBySession(routed.sessionId());
            if (b.isPresent()) return Optional.of(new Match(b.get(), "session"));
        }
        // Only guess from the sender when they named no reference: answering about a different shipment is worse than asking.
        if (!explicitReference && mail.fromAddr() != null && !mail.fromAddr().equalsIgnoreCase(trackingAddress)) {
            Optional<BookingRecord> b = repo.findLatestByCustomer(mail.fromAddr());
            if (b.isPresent()) return Optional.of(new Match(b.get(), "customer " + mail.fromAddr()));
        }
        return Optional.empty();
    }

    private void notFound(SessionView session, StoredMail mail, String language, String name) {
        sessions.flagNeedsHuman(session.id(), "Tracking question, but no booking matches it");
        WrittenMail written = writer.write(new MailIntent(session.id(), Purpose.HOLDING_REPLY, List.of(mail),
                Map.of(Facts.NEXT_UPDATE, "shortly"), List.of(), language, name,
                "Say you are checking the shipment status and will come back shortly. Ask for the booking reference if they gave none."));
        // Answered automatically (honest holding answer); the flag lets a human follow up if they want to.
        outbox.deliver(session, reply(mail, written), "tracking_answer");
        events.append(session.id(), mail.id(), "tracking.not_found", Actor.system,
                "No booking matches the tracking question", Payloads.of("subject", mail.subject()));
    }

    private static OutgoingMail reply(StoredMail original, WrittenMail written) {
        return new OutgoingMail(original.fromAddr(), written.subject(), written.body(), original.externalId(), List.of());
    }

    private static void collect(Pattern pattern, String text, Set<String> into) {
        if (text == null) return;
        Matcher m = pattern.matcher(text);
        while (m.find()) into.add(m.group().toUpperCase(Locale.ROOT));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
