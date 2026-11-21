package ai.aeiou.forwarder.booking;

import ai.aeiou.forwarder.booking.BookingRepository.ChosenCandidate;
import ai.aeiou.forwarder.booking.BookingRepository.RfqInfo;
import ai.aeiou.forwarder.booking.port.BookingPortal;
import ai.aeiou.forwarder.booking.port.BookingPortal.BookingResult;
import ai.aeiou.forwarder.booking.port.BookingPortal.RateQuery;
import ai.aeiou.forwarder.booking.port.BookingPortal.RateResult;
import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.fiveu.PortalModels.Booking;
import ai.aeiou.forwarder.fiveu.PortalModels.Rate;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.mail.StoredMail;
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
import ai.aeiou.forwarder.writer.Money;
import ai.aeiou.forwarder.writer.Outbox;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The customer accepted our quote: book the quoted option (ARCHITECTURE.md §4.1).
 * <ul>
 *   <li>Portal (or carrier-bid R-...) rate ID: book it with {@code team_reference = session.public_ref}. An expired
 *       (410), unknown (404) or taken (409) rate is re-quoted for the same airline, lane and weight and booked; a
 *       price that moved by more than 1% is booked too and flagged for attention.</li>
 *   <li>Portal unavailable (503 / unreachable), or a candidate without a rate id (sheet, bid without R-...): book by
 *       email with the airline.</li>
 *   <li>Anything unexpected: booked by email with the airline and flagged. Nothing waits for a person.</li>
 * </ul>
 */
@Component
public class AcceptanceHandler implements MailHandler {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceHandler.class);
    private static final BigDecimal MAX_PRICE_DRIFT = new BigDecimal("0.01");
    /** Sources whose rate id the portal books; carrier bids and rate-desk answers carry the same R-... ids. */
    private static final Set<String> PORTAL_SOURCES = Set.of("portal", "combined", "bid", "ratedesk");

    private final BookingPortal portal;
    private final BookingRepository repo;
    private final SessionService sessions;
    private final EventLog events;
    private final EmailWriter writer;
    private final Outbox outbox;
    private final MailGateway mailGateway;
    private final CustomerNotifier notifier;
    private final Clock clock;

    @Autowired
    public AcceptanceHandler(BookingPortal portal, BookingRepository repo, SessionService sessions, EventLog events,
                             EmailWriter writer, Outbox outbox, MailGateway mailGateway, CustomerNotifier notifier,
                             ObjectProvider<Clock> clock) {
        this(portal, repo, sessions, events, writer, outbox, mailGateway, notifier, clock.getIfAvailable(Clock::systemUTC));
    }

    AcceptanceHandler(BookingPortal portal, BookingRepository repo, SessionService sessions, EventLog events,
                      EmailWriter writer, Outbox outbox, MailGateway mailGateway, CustomerNotifier notifier, Clock clock) {
        this.portal = portal;
        this.repo = repo;
        this.sessions = sessions;
        this.events = events;
        this.writer = writer;
        this.outbox = outbox;
        this.mailGateway = mailGateway;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Override
    public Set<MailType> supports() {
        return Set.of(MailType.ACCEPTANCE);
    }

    /** Everything one booking attempt needs. */
    private record Context(SessionView session, StoredMail acceptance, ChosenCandidate candidate, RfqInfo rfq) {}

    @Override
    public void handle(RoutedMail routed) {
        Long sessionId = routed.sessionId();
        if (sessionId == null) {
            log.warn("Acceptance mail {} has no session", routed.mail().id());
            return;
        }
        SessionView session = sessions.find(sessionId).orElseThrow();
        Optional<BookingRecord> existing = repo.findActiveBySession(sessionId);
        if (existing.isPresent()) {
            events.append(sessionId, routed.mail().id(), "booking.duplicate", Actor.system,
                    "Acceptance received again; booking " + existing.get().status() + " already exists",
                    Payloads.of("bookingId", existing.get().id()));
            return;
        }
        Optional<ChosenCandidate> candidate = repo.chosenCandidate(sessionId);
        Optional<RfqInfo> rfq = repo.rfq(sessionId);
        if (candidate.isEmpty() || rfq.isEmpty() || rfq.get().originAirport() == null || rfq.get().destination() == null) {
            sessions.flagNeedsHuman(sessionId, "Customer accepted, but there is no quoted option or lane to book");
            return;
        }
        sessions.setStatus(sessionId, SessionStatus.ACCEPTED);
        events.append(sessionId, routed.mail().id(), "booking.accepted", Actor.system,
                "Customer accepted " + candidate.get().airline() + " (" + candidate.get().source() + ")",
                Payloads.of("candidateId", candidate.get().id()));

        Context ctx = new Context(session, routed.mail(), candidate.get(), rfq.get());
        if (ctx.candidate().rateId() != null && PORTAL_SOURCES.contains(ctx.candidate().source())) {
            bookOnPortal(ctx);
        } else {
            bookByEmail(ctx, ctx.candidate().source() + " rate, the airline books by email");
        }
    }

    private void bookOnPortal(Context ctx) {
        String rateId = ctx.candidate().rateId();
        Integer transitDays = ctx.candidate().transitDays();
        boolean refreshed = false;
        if (ctx.candidate().validUntil() != null && ctx.candidate().validUntil().isBefore(OffsetDateTime.now(clock))) {
            Optional<Rate> fresh = refreshRate(ctx, "rate expired at " + ctx.candidate().validUntil());
            if (fresh.isEmpty()) return;
            rateId = fresh.get().rateId();
            transitDays = coalesce(fresh.get().transitDays(), transitDays);
            refreshed = true;
        }

        BookingResult result = book(ctx, rateId);
        // 410 expired, 404 unknown, 409 taken: a fresh rate for the same airline and lane is bookable.
        if ((result.status() == 410 || result.status() == 404 || result.status() == 409) && !refreshed) {
            Optional<Rate> fresh = refreshRate(ctx, "portal answered " + result.status());
            if (fresh.isEmpty()) return;
            rateId = fresh.get().rateId();
            transitDays = coalesce(fresh.get().transitDays(), transitDays);
            result = book(ctx, rateId);
        }

        if (result.booked()) {
            onBooked(ctx, result.booking(), transitDays);
        } else if (result.status() == 503 || result.status() >= 500 || result.status() == BookingPortal.UNREACHABLE) {
            bookByEmail(ctx, "portal booking unavailable (" + result.status() + ")");
        } else {
            // No dead end: the airline can always be booked by email.
            sessions.flagNeedsHuman(ctx.session().id(), "Portal booking failed with " + result.status() + ": " + result.message()
                    + "; booking by email instead");
            bookByEmail(ctx, "portal booking failed (" + result.status() + ")");
        }
    }

    private BookingResult book(Context ctx, String rateId) {
        BookingResult result = portal.book(rateId, ctx.session().publicRef());
        Map<String, Object> payload = Payloads.of("rateId", rateId, "teamReference", ctx.session().publicRef(),
                "status", result.status(), "message", result.message(),
                "bookingReference", result.booking() == null ? null : result.booking().bookingReference());
        events.append(ctx.session().id(), result.booked() ? "portal.booking" : "portal.error", Actor.system,
                "Portal booking " + rateId + " -> " + result.status(), payload);
        return result;
    }

    /**
     * Re-quotes the same airline, lane and weight. A price that moved by more than 1% is still booked (the customer
     * accepted this shipment, and a booking beats none) and flagged for attention.
     */
    private Optional<Rate> refreshRate(Context ctx, String why) {
        ChosenCandidate c = ctx.candidate();
        RfqInfo rfq = ctx.rfq();
        RateResult result = portal.rate(new RateQuery(rfq.originAirport(), rfq.destination(),
                c.airlineCode() != null ? c.airlineCode() : c.airlineName(), rfq.weightKg(), rfq.volumeCbm()));
        events.append(ctx.session().id(), result.ok() ? "portal.rate" : "portal.error", Actor.system,
                "Re-quoted " + c.airline() + " (" + why + ") -> " + (result.ok() ? Money.format(result.rate().total(), result.rate().currency()) : result.status()),
                result.ok() ? Payloads.of("rateId", result.rate().rateId(), "total", result.rate().total(), "why", why)
                        : Payloads.of("status", result.status(), "why", why));
        if (!result.ok()) {
            if (result.status() != 503 && result.status() != BookingPortal.UNREACHABLE) {
                sessions.flagNeedsHuman(ctx.session().id(), "Re-quote failed (" + result.status() + "); booking by email instead");
            }
            bookByEmail(ctx, "the portal cannot re-quote (" + result.status() + ")");
            return Optional.empty();
        }
        Rate rate = result.rate();
        if (!withinDrift(c, rate)) {
            sessions.flagNeedsHuman(ctx.session().id(), "Booked a fresh " + c.airline() + " rate "
                    + Money.format(rate.total(), rate.currency()) + " that differs from the quoted "
                    + Money.format(c.total(), c.currency()) + " by more than 1%");
        }
        return Optional.of(rate);
    }

    /**
     * Door-to-port candidates ({@code combined}) also contain the truck, so there the new air rate only has to stay
     * within the quoted all-in; a plain portal rate must be within 1% of the quoted price.
     */
    private static boolean withinDrift(ChosenCandidate c, Rate rate) {
        if (rate.total() == null || rate.currency() == null || !rate.currency().equalsIgnoreCase(c.currency())) return false;
        BigDecimal limit = c.total().multiply(BigDecimal.ONE.add(MAX_PRICE_DRIFT));
        if ("combined".equals(c.source())) return rate.total().compareTo(limit) <= 0;
        BigDecimal drift = rate.total().subtract(c.total()).abs().divide(c.total(), 6, RoundingMode.HALF_UP);
        return drift.compareTo(MAX_PRICE_DRIFT) <= 0;
    }

    private void onBooked(Context ctx, Booking out, Integer transitDays) {
        long sessionId = ctx.session().id();
        OffsetDateTime departure = ShipmentFacts.departure(ctx.rfq().readyDate(), OffsetDateTime.now(clock));
        BookingRecord booking = new BookingRecord(0, sessionId, out.rateId(), ctx.session().publicRef(), out.bookingReference(),
                coalesce(out.airlineCode(), ctx.candidate().airlineCode()), coalesce(out.airline(), ctx.candidate().airlineName()),
                ctx.rfq().originAirport(), ctx.rfq().destination(), out.price(), out.currency(), transitDays,
                BookingRecord.BOOKED, null, null, departure, ShipmentFacts.eta(departure, transitDays));
        long id = repo.insert(booking);
        sessions.setStatus(sessionId, SessionStatus.BOOKED);
        sessions.markBooked(sessionId);
        events.append(sessionId, "booking.created", Actor.system,
                "Booked " + booking.airline() + " as " + out.bookingReference(),
                Payloads.of("bookingId", id, "bookingReference", out.bookingReference(), "rateId", out.rateId(),
                        "price", out.price(), "currency", out.currency()));
        notifier.bookingConfirmed(ctx.session(), repo.findById(id).orElseThrow(), ctx.acceptance());
    }

    /** Books by email with the airline (carrier table), in the bid thread when there is one. */
    private void bookByEmail(Context ctx, String reason) {
        long sessionId = ctx.session().id();
        ChosenCandidate c = ctx.candidate();
        Optional<String> email = repo.airlineEmail(c.airlineName(), c.airlineCode());
        if (email.isEmpty()) {
            sessions.flagNeedsHuman(sessionId, "Book " + c.airline() + " by email, but its address is unknown (" + reason + ")");
            return;
        }
        RfqInfo rfq = ctx.rfq();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put(Facts.AIRLINE, c.airline());
        facts.put(Facts.ORIGIN, rfq.originAirport());
        facts.put(Facts.DESTINATION, rfq.destination());
        put(facts, Facts.PIECES, rfq.pieces());
        put(facts, Facts.WEIGHT_KG, rfq.weightKg() == null ? null : rfq.weightKg().stripTrailingZeros().toPlainString());
        put(facts, Facts.VOLUME_CBM, rfq.volumeCbm() == null ? null : rfq.volumeCbm().stripTrailingZeros().toPlainString());
        put(facts, Facts.COMMODITY, rfq.commodity());
        put(facts, Facts.READY_DATE, rfq.readyDate());
        if (!"combined".equals(c.source())) {
            facts.put("agreedRate", Money.format(c.total(), c.currency()));
        }
        put(facts, "rateId", c.rateId());
        facts.put(Facts.OUR_REFERENCE, ctx.session().publicRef());

        Optional<StoredMail> bidThread = repo.bidThreadMailExt(sessionId, email.get()).flatMap(mailGateway::findByExternalId);
        WrittenMail written = writer.write(new MailIntent(sessionId, Purpose.BOOKING_REQUEST,
                bidThread.map(List::of).orElse(List.of()), facts, List.of(ctx.session().publicRef()), "en", null, null));
        OutgoingMail mail = new OutgoingMail(email.get(), written.subject(), written.body(),
                bidThread.map(StoredMail::externalId).orElse(null), List.of());
        Outbox.Delivery delivery = outbox.deliver(ctx.session(), mail, "booking_request");

        OffsetDateTime departure = ShipmentFacts.departure(rfq.readyDate(), OffsetDateTime.now(clock));
        long id = repo.insert(new BookingRecord(0, sessionId, c.rateId(), ctx.session().publicRef(), null, c.airlineCode(),
                c.airlineName(), rfq.originAirport(), rfq.destination(), c.total(), c.currency(), c.transitDays(),
                BookingRecord.REQUESTED, null, null, departure, ShipmentFacts.eta(departure, c.transitDays())));
        events.append(sessionId, "booking.requested", Actor.system,
                "Booking requested by email with " + c.airline() + " (" + reason + ")",
                Payloads.of("bookingId", id, "to", email.get(), "reason", reason, "delivery", delivery.name()));

        if (delivery == Outbox.Delivery.SENT) {
            sendHoldingReply(ctx, c.airline());
        }
    }

    /** Keeps the customer in the loop while the airline confirms the email booking. */
    private void sendHoldingReply(Context ctx, String airline) {
        RfqInfo rfq = ctx.rfq();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put(Facts.AIRLINE, airline);
        facts.put(Facts.STATUS, "booking requested with the airline");
        facts.put(Facts.NEXT_UPDATE, "as soon as the airline confirms");
        put(facts, Facts.COMMODITY, rfq.commodity());
        WrittenMail written = writer.write(new MailIntent(ctx.session().id(), Purpose.HOLDING_REPLY, List.of(ctx.acceptance()),
                facts, List.of(), rfq.language(), rfq.customerName(),
                "Thank them for the go-ahead, say the booking is placed with the airline and you will confirm the booking reference shortly."));
        outbox.deliver(ctx.session(), OutgoingMail.reply(ctx.acceptance(), written.body()), "holding_reply");
    }

    private static void put(Map<String, Object> facts, String key, Object value) {
        if (value != null) facts.put(key, value);
    }

    private static <T> T coalesce(T a, T b) {
        return a != null ? a : b;
    }
}
