package ai.aeiou.forwarder.booking;

import ai.aeiou.forwarder.booking.BookingRepository.RfqInfo;
import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.session.SessionService.SessionView;
import ai.aeiou.forwarder.writer.EmailWriter;
import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import ai.aeiou.forwarder.writer.EmailWriter.Purpose;
import ai.aeiou.forwarder.writer.EmailWriter.WrittenMail;
import ai.aeiou.forwarder.writer.Money;
import ai.aeiou.forwarder.writer.Outbox;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Tells the customer, in their own thread, that their shipment is booked. */
@Component
public class CustomerNotifier {

    /** Written once the customer has actually been sent the booking confirmation. */
    public static final String NOTIFIED_EVENT = "booking.customer_notified";

    private final EmailWriter writer;
    private final Outbox outbox;
    private final MailGateway mailGateway;
    private final BookingRepository repo;
    private final SessionService sessions;
    private final EventLog events;
    private final Clock clock;

    @Autowired
    public CustomerNotifier(EmailWriter writer, Outbox outbox, MailGateway mailGateway, BookingRepository repo,
                            SessionService sessions, EventLog events, ObjectProvider<Clock> clock) {
        this(writer, outbox, mailGateway, repo, sessions, events, clock.getIfAvailable(Clock::systemUTC));
    }

    CustomerNotifier(EmailWriter writer, Outbox outbox, MailGateway mailGateway, BookingRepository repo,
                     SessionService sessions, EventLog events, Clock clock) {
        this.writer = writer;
        this.outbox = outbox;
        this.mailGateway = mailGateway;
        this.repo = repo;
        this.sessions = sessions;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Sends (or drafts) the booking confirmation. {@code replyTo} is the customer mail to answer; when null, the
     * customer's latest mail in the session is used.
     */
    public void bookingConfirmed(SessionView session, BookingRecord booking, StoredMail replyTo) {
        Optional<StoredMail> original = Optional.ofNullable(replyTo).or(() -> repo
                .latestCustomerMailExt(session.id(), session.customerAddr())
                .flatMap(mailGateway::findByExternalId));
        if (original.isEmpty()) {
            sessions.flagNeedsHuman(session.id(), "Booking " + reference(booking) + " made, but no customer mail to reply to");
            return;
        }
        RfqInfo rfq = repo.rfq(session.id()).orElse(null);
        String language = rfq != null && rfq.language() != null ? rfq.language() : null;
        String name = rfq == null ? null : rfq.customerName();

        // The customer pays what we quoted (it includes trucking for door-to-port), not the airline's booking price.
        BigDecimal total = session.quotedTotal() != null ? session.quotedTotal() : booking.price();
        String currency = session.quotedTotal() != null ? session.quotedCurrency() : booking.currency();

        Map<String, Object> facts = ShipmentFacts.forBooking(booking, total, currency, language == null ? "en" : language,
                OffsetDateTime.now(clock), iata -> repo.airportCity(iata).orElse(null));
        if (rfq != null && rfq.commodity() != null) facts.put("commodity", rfq.commodity());
        List<String> mustContain = new ArrayList<>();
        if (booking.bookingReference() != null) mustContain.add(booking.bookingReference());
        if (total != null && currency != null) mustContain.add(Money.format(total, currency));

        WrittenMail written = writer.write(new MailIntent(session.id(), Purpose.BOOKING_CONFIRMATION, List.of(original.get()),
                facts, mustContain, language, name, null));
        OutgoingMail mail = new OutgoingMail(original.get().fromAddr(), written.subject(), written.body(),
                original.get().externalId(), List.of());
        if (outbox.deliver(session, mail, "booking_confirmation") == Outbox.Delivery.SENT) {
            events.append(session.id(), NOTIFIED_EVENT, Actor.system,
                    "Customer informed about booking " + reference(booking),
                    Map.of("bookingId", booking.id(), "fromTemplate", written.fromTemplate()));
        }
    }

    private static String reference(BookingRecord b) {
        return b.bookingReference() != null ? b.bookingReference() : b.teamReference();
    }
}
