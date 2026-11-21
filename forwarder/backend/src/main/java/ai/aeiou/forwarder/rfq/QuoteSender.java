package ai.aeiou.forwarder.rfq;

import static ai.aeiou.forwarder.rfq.RfqEvents.payload;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.rfq.RfqRepository.Draft;
import ai.aeiou.forwarder.rfq.RfqRepository.RfqRecord;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.session.SessionService.SessionView;
import ai.aeiou.forwarder.session.SessionStatus;
import ai.aeiou.forwarder.writer.EmailWriter;
import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import ai.aeiou.forwarder.writer.EmailWriter.Purpose;
import ai.aeiou.forwarder.writer.EmailWriter.WrittenMail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns the chosen candidate into the single quote of a session. Every send path, automatic or human-approved,
 * goes through {@link SessionService#claimQuote}, so a thread never gets a second quote.
 */
@Service
public class QuoteSender {

    public static final String DRAFT_PURPOSE_QUOTE = "quote";
    private static final Logger log = LoggerFactory.getLogger(QuoteSender.class);

    private final EmailWriter writer;
    private final MailGateway gateway;
    private final SessionService sessions;
    private final RfqRepository repo;
    private final RfqEvents events;
    private final RfqSettings settings;

    public QuoteSender(EmailWriter writer, MailGateway gateway, SessionService sessions, RfqRepository repo,
                       RfqEvents events, RfqSettings settings) {
        this.writer = writer;
        this.gateway = gateway;
        this.sessions = sessions;
        this.repo = repo;
        this.events = events;
        this.settings = settings;
    }

    public enum Outcome { SENT, DRAFTED, ALREADY_QUOTED, NO_DRAFT, FAILED }

    /** Writes the quote and sends it, or leaves it as a draft when a human has to look first. */
    public Outcome deliver(RfqRecord rfq, Candidate chosen) {
        long sessionId = rfq.sessionId();
        StoredMail customerMail = gateway.findById(rfq.mailId())
                .orElseThrow(() -> new IllegalStateException("RFQ mail " + rfq.mailId() + " not found"));
        Map<String, Object> facts = facts(rfq, chosen);
        String totalLabel = Money.label(chosen.total(), chosen.currency());
        String body = writeBody(sessionId, rfq, customerMail, facts, totalLabel);
        OutgoingMail mail = OutgoingMail.reply(customerMail, body);

        SessionView session = sessions.find(sessionId).orElseThrow();
        // Both switches must be on: the global "answer live mails automatically" and the session's own autopilot.
        if (session.needsHuman() || !session.autopilot() || !settings.autopilot()) {
            repo.saveDraft(new Draft(sessionId, DRAFT_PURPOSE_QUOTE, mail.to(), mail.subject(), body, mail.inReplyToExt(),
                    draftMeta(chosen)));
            String why = session.needsHuman() ? "session needs a human"
                    : !settings.autopilot() ? "global autopilot is off" : "autopilot is off for this session";
            events.system(sessionId, "quote.drafted", "Quote " + totalLabel + " drafted, waiting for approval: " + why,
                    payload("candidateId", chosen.id(), "total", chosen.total(), "currency", chosen.currency()));
            if (!session.needsHuman()) sessions.flagNeedsHuman(sessionId, "Quote " + totalLabel + " ready for approval (" + why + ")");
            return Outcome.DRAFTED;
        }
        return claimAndSend(sessionId, mail, chosen.total(), chosen.currency(), chosen.totalEur(), chosen.id(), Actor.system);
    }

    /**
     * Sends the pending draft (REST actions {@code approve} and {@code edit_and_send}). A quote draft is guarded by
     * {@code claimQuote} exactly like an automatic quote; other drafts are sent as they are.
     *
     * @param overrideBody the human's edited body, or null to send the draft unchanged
     */
    public Outcome sendDraft(long sessionId, String overrideBody) {
        Draft draft = repo.findDraft(sessionId).orElse(null);
        if (draft == null) return Outcome.NO_DRAFT;
        String body = overrideBody == null || overrideBody.isBlank() ? draft.body() : overrideBody;
        OutgoingMail mail = new OutgoingMail(draft.toAddr(), draft.subject(), body, draft.inReplyToExt(), List.of());

        Outcome outcome;
        if (DRAFT_PURPOSE_QUOTE.equals(draft.purpose())) {
            Map<String, Object> meta = draft.meta() == null ? Map.of() : draft.meta();
            BigDecimal total = decimal(meta.get("total"));
            String currency = (String) meta.get("currency");
            if (overrideBody != null && !body.contains(Money.label(total, currency))) {
                events.human(sessionId, "quote.edited", "Edited quote does not contain " + Money.label(total, currency)
                        + "; the stored total may differ from the mail", payload());
            }
            Long candidateId = meta.get("candidateId") instanceof Number n ? n.longValue() : null;
            outcome = claimAndSend(sessionId, mail, total, currency, decimal(meta.get("totalEur")), candidateId, Actor.human);
        } else {
            gateway.send(sessionId, mail);
            events.human(sessionId, "draft.sent", "Draft (" + draft.purpose() + ") sent to " + draft.toAddr(), payload());
            outcome = Outcome.SENT;
        }
        if (outcome == Outcome.SENT || outcome == Outcome.ALREADY_QUOTED) {
            repo.deleteDraft(sessionId);
            sessions.clearNeedsHuman(sessionId);
        }
        return outcome;
    }

    private Outcome claimAndSend(long sessionId, OutgoingMail mail, BigDecimal total, String currency, BigDecimal totalEur,
                                 Long candidateId, Actor actor) {
        String label = Money.label(total, currency);
        if (!sessions.claimQuote(sessionId, total, currency, totalEur)) {
            events.append(sessionId, null, "quote.skipped", actor, "Quote " + label + " not sent: this thread already has a quote", payload());
            return Outcome.ALREADY_QUOTED;
        }
        StoredMail sent;
        try {
            sent = gateway.send(sessionId, mail);
        } catch (RuntimeException e) {
            sessions.releaseQuote(sessionId);
            log.warn("Sending quote for session {} failed", sessionId, e);
            events.append(sessionId, null, "quote.failed", actor, "Sending quote " + label + " failed: " + e.getMessage(),
                    payload("error", e.getClass().getSimpleName()));
            sessions.flagNeedsHuman(sessionId, "Sending the quote failed: " + e.getMessage());
            return Outcome.FAILED;
        }
        sessions.setStatus(sessionId, SessionStatus.QUOTED);
        events.append(sessionId, sent.id(), "quote.sent", actor, "Quote " + label + " sent to " + mail.to(),
                payload("total", total, "currency", currency, "totalEur", totalEur, "candidateId", candidateId,
                        "mailId", sent.id()));
        events.forget(sessionId);
        return Outcome.SENT;
    }

    /** Writer output, unless it fails to state the total exactly once; then the plain template. */
    private String writeBody(long sessionId, RfqRecord rfq, StoredMail customerMail, Map<String, Object> facts, String totalLabel) {
        String body = null;
        try {
            WrittenMail written = writer.write(new MailIntent(sessionId, Purpose.QUOTE, List.of(customerMail), facts,
                    List.of(totalLabel), rfq.language(), rfq.customerName(), null));
            body = written == null ? null : written.body();
        } catch (RuntimeException e) {
            log.warn("Quote writer failed for session {}", sessionId, e);
        }
        if (Money.statesExactlyOneTotal(body, totalLabel)) return body;
        events.system(sessionId, "quote.template", "Written quote did not state " + totalLabel
                + " exactly once; using the plain template", payload("amountsFound", Money.countAmounts(body)));
        return QuoteTemplate.render(facts, totalLabel, rfq.customerName());
    }

    static Map<String, Object> facts(RfqRecord rfq, Candidate c) {
        // "total" (plain amount) and "currency" are the keys the writer's single-price validator reads.
        // TODO(integration): use writer.Money/Facts
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("total", Money.format(c.total()));
        f.put("currency", c.currency());
        f.put("airline", c.airlineName() != null ? c.airlineName() : c.airlineCode());
        f.put("transitDays", c.transitDays());
        f.put("route", rfq.originAirport() + " → " + rfq.destination());
        f.put("chargeableKg", rfq.chargeableKg() == null ? null : rfq.chargeableKg().stripTrailingZeros().toPlainString());
        f.put("validity", validity(c));
        f.put("doorPickup", rfq.doorPickup());
        return f;
    }

    /**
     * Portal rate ids live 15 minutes, which is not an offer validity for a customer (booking fetches a fresh rate
     * when needed). Bids may state their own validity; otherwise we hold the price for a week.
     */
    private static String validity(Candidate c) {
        boolean ownValidity = c.validUntil() != null && c.source() != CandidateSource.portal && c.rateId() == null;
        LocalDate until = ownValidity ? c.validUntil().toLocalDate() : LocalDate.now(ZoneOffset.UTC).plusDays(7);
        return until.toString();
    }

    private static Map<String, Object> draftMeta(Candidate c) {
        // Amounts as strings: a JSON double would not round-trip exactly.
        return payload("candidateId", c.id(), "total", c.total().toPlainString(), "currency", c.currency(),
                "totalEur", c.totalEur().toPlainString(), "rateId", c.rateId(), "airline", c.airlineName());
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }
}
