package ai.aeiou.forwarder.rfq;

import static ai.aeiou.forwarder.rfq.RfqEvents.payload;

import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.pricing.Shipment;
import ai.aeiou.forwarder.rfq.RfqRepository.Draft;
import ai.aeiou.forwarder.rfq.RfqRepository.RfqRecord;
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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Entry point of the RFQ workflow for routed mail: new requests, carrier / rate-desk answers and customer
 * follow-ups in RFQ sessions. Acceptances belong to the booking workflow.
 */
@Component
public class RfqHandler implements MailHandler {

    static final String SESSION_TYPE = "RFQ";
    private static final Logger log = LoggerFactory.getLogger(RfqHandler.class);

    private final SessionService sessions;
    private final RfqRepository repo;
    private final ReferenceRepository reference;
    private final QuoteOrchestrator orchestrator;
    private final BidCollector bids;
    private final EmailWriter writer;
    private final RfqEvents events;

    public RfqHandler(SessionService sessions, RfqRepository repo, ReferenceRepository reference,
                      QuoteOrchestrator orchestrator, BidCollector bids, EmailWriter writer, RfqEvents events) {
        this.sessions = sessions;
        this.repo = repo;
        this.reference = reference;
        this.orchestrator = orchestrator;
        this.bids = bids;
        this.writer = writer;
        this.events = events;
    }

    @Override
    public Set<MailType> supports() {
        return EnumSet.of(MailType.NEW_RFQ, MailType.CARRIER_BID, MailType.RATEDESK_REPLY, MailType.FOLLOW_UP);
    }

    @Override
    public void handle(RoutedMail mail) {
        if (mail.sessionId() == null) return;
        Optional<SessionView> session = sessions.find(mail.sessionId());
        if (session.isEmpty() || !SESSION_TYPE.equals(session.get().type())) return;
        switch (mail.type()) {
            case NEW_RFQ -> onNewRfq(mail);
            case CARRIER_BID, RATEDESK_REPLY -> bids.onReply(mail);
            case FOLLOW_UP -> onFollowUp(mail, session.get());
            default -> { }
        }
    }

    private void onNewRfq(RoutedMail routed) {
        long sessionId = routed.sessionId();
        StoredMail mail = routed.mail();
        if (mail.sentAt() != null) events.registerArrival(sessionId, mail.sentAt().toInstant());
        Extraction x = routed.extraction();
        if (x == null || x.rfq() == null) {
            sessions.flagNeedsHuman(sessionId, "RFQ without extracted shipment details");
            return;
        }
        Extraction.Rfq r = x.rfq();
        String destination = iata(r.destination());
        String origin = resolveOrigin(r);
        String mode = QuoteDecider.FASTEST.equalsIgnoreCase(r.mode()) ? QuoteDecider.FASTEST : QuoteDecider.CHEAPEST;
        RfqRecord rfq = new RfqRecord(sessionId, mail.id(), r.originZip(), origin, destination, r.weightKg(), r.volumeCbm(),
                r.weightKg() == null ? null : new Shipment(origin, destination, r.weightKg(), r.volumeCbm()).chargeableKg(),
                r.pieces(), mode, Boolean.TRUE.equals(r.doorPickup()), parseDate(r.readyDate()), r.commodity(),
                x.language(), x.senderName(), null);
        if (!repo.insertRfq(rfq, x)) {
            events.system(sessionId, mail.id(), "rfq.duplicate", "RFQ already registered for this session", payload());
            return;
        }
        sessions.setMode(sessionId, mode);
        events.system(sessionId, mail.id(), "rfq.received", "RFQ " + origin + " → " + destination + ", "
                        + rfq.chargeableKg() + " kg chargeable, " + mode + (rfq.doorPickup() ? ", door pickup" : ""),
                payload("origin", origin, "originZip", r.originZip(), "destination", destination, "weightKg", r.weightKg(),
                        "volumeCbm", r.volumeCbm(), "chargeableKg", rfq.chargeableKg(), "mode", mode,
                        "modeGiven", r.mode() != null, "doorPickup", rfq.doorPickup()));

        List<String> missing = missingEssentials(rfq);
        if (!missing.isEmpty()) {
            // A question is not a quote, and only the first reply counts: a human decides what to send.
            draftHoldingReply(rfq, mail, missing);
            sessions.flagNeedsHuman(sessionId, "RFQ is missing " + String.join(", ", missing));
            return;
        }
        sessions.setStatus(sessionId, SessionStatus.PRICING);
        orchestrator.start(rfq);
    }

    private void onFollowUp(RoutedMail routed, SessionView session) {
        StoredMail mail = routed.mail();
        String summary = summary(routed);
        boolean quoted = session.quotedAt() != null;
        events.system(session.id(), mail.id(), "rfq.follow_up", "Customer wrote again" + (quoted ? " after our quote" : ""),
                payload("summary", summary));
        // Never a second automatic quote in the same thread; a person reads it.
        sessions.flagNeedsHuman(session.id(), (quoted ? "Follow-up after quote: " : "Follow-up before quote: ") + summary);
    }

    private String resolveOrigin(Extraction.Rfq r) {
        String airport = iata(r.originAirport());
        if (airport != null) return airport;
        String zone = zipZone(r.originZip());
        if (zone != null) {
            Optional<String> byZone = reference.airportForZone(zone);
            if (byZone.isPresent()) return byZone.get();
        }
        return r.originCity() == null ? null : reference.airportForCity(r.originCity()).orElse(null);
    }

    private static List<String> missingEssentials(RfqRecord rfq) {
        List<String> missing = new ArrayList<>();
        if (rfq.originAirport() == null) missing.add("origin");
        if (rfq.destination() == null) missing.add("destination");
        if (rfq.weightKg() == null || rfq.weightKg().signum() <= 0) missing.add("weight");
        return missing;
    }

    private void draftHoldingReply(RfqRecord rfq, StoredMail mail, List<String> missing) {
        String body;
        try {
            body = writer.write(new MailIntent(rfq.sessionId(), Purpose.HOLDING_REPLY, List.of(mail),
                    payload("missing", missing), List.of(), rfq.language(), rfq.customerName(),
                    "Ask only for the missing details.")).body();
        } catch (RuntimeException e) {
            log.warn("Holding reply writer failed for session {}", rfq.sessionId(), e);
            body = "Hello,\n\nthanks for your request. Could you tell us the " + String.join(" and ", missing)
                    + " of the shipment? Then we send you a price right away.\n\nBest regards,\nAEIOU Air Freight desk";
        }
        OutgoingMail reply = OutgoingMail.reply(mail, body);
        repo.saveDraft(new Draft(rfq.sessionId(), "other", reply.to(), reply.subject(), body, reply.inReplyToExt(),
                payload("kind", "holding_reply", "missing", missing)));
    }

    private static String summary(RoutedMail routed) {
        StoredMail m = routed.mail();
        String body = m.body() == null ? "" : m.body().replaceAll("\\s+", " ").trim();
        if (body.length() > 160) body = body.substring(0, 157) + "...";
        return body.isEmpty() ? String.valueOf(m.subject()) : body;
    }

    /** First two digits of a German postcode: the pickup zone ("64289" → "64"). */
    public static String zipZone(String zip) {
        if (zip == null) return null;
        String digits = zip.replaceAll("\\D", "");
        return digits.length() >= 2 ? digits.substring(0, 2) : null;
    }

    private static String iata(String code) {
        if (code == null) return null;
        String c = code.trim().toUpperCase();
        return c.matches("[A-Z]{3}") ? c : null;
    }

    private static LocalDate parseDate(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
