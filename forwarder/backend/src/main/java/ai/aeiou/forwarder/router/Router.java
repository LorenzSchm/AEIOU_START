package ai.aeiou.forwarder.router;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.mail.MailRepository;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.settings.SettingsService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Two-stage mail router (ARCHITECTURE.md §3.2).
 *
 * <ol>
 *   <li><b>Session:</b> a reply to our bid request joins that request's session, a booking confirmation
 *       joins the booking's session, a mail in a known thread joins the thread's session; anything else
 *       opens a new session (before the LLM call, so its cost is attributed to it).</li>
 *   <li><b>Stage 1:</b> hard facts decide unambiguous types ({@link Stage1Classifier}).</li>
 *   <li><b>Stage 2:</b> one structured LLM call classifies and extracts ({@link Stage2Classifier});
 *       customer mails and carrier bids always go here.</li>
 *   <li><b>Decision:</b> {@link RouteDecision} merges both, flags doubts for a human, and the mail is
 *       dispatched to the matching {@link MailHandler}s.</li>
 * </ol>
 */
@Service
public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    /** Customers expect a quote within 6 minutes of their mail (given). */
    static final Duration QUOTE_WINDOW = Duration.ofMinutes(6);
    static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.75;

    private final RouterRepository repo;
    private final MailRepository mails;
    private final Stage2Classifier stage2;
    private final HandlerDispatcher dispatcher;
    private final SessionService sessions;
    private final SettingsService settings;
    private final EventLog events;
    private final Stage1Classifier stage1 = new Stage1Classifier();

    Router(RouterRepository repo, MailRepository mails, Stage2Classifier stage2, HandlerDispatcher dispatcher,
           SessionService sessions, SettingsService settings, EventLog events) {
        this.repo = repo;
        this.mails = mails;
        this.stage2 = stage2;
        this.dispatcher = dispatcher;
        this.sessions = sessions;
        this.settings = settings;
        this.events = events;
    }

    /** Routes one newly stored incoming mail end to end. Never throws for a single bad mail. */
    public RoutedMail route(StoredMail mail) {
        Stage1Classifier.Facts facts = repo.facts(mail);
        Stage1Classifier.Result s1 = stage1.classify(facts);

        Long existing = firstNonNull(facts.bidRequestSessionId(), facts.bookingSessionId(), facts.threadSessionId());
        boolean newSession = existing == null;
        String initialType = s1.type() == null ? "OTHER" : sessionType(s1.type());
        long sessionId = newSession ? sessions.create(initialType, mail.fromAddr(), mail.subject(), null) : existing;
        mails.assignSession(mail.id(), sessionId);
        StoredMail placed = mails.findById(mail.id()).orElse(mail);
        events.append(sessionId, mail.id(), "mail.in", Actor.system,
                "Mail from " + mail.fromAddr() + ": " + mail.subject(),
                Map.of("externalId", mail.externalId(), "from", mail.fromAddr(), "subject", mail.subject(),
                        "attachments", mail.attachments().size()));

        Extraction extraction = parsedBid(s1, placed);
        String llmError = null;
        if (extraction == null && s1.needsStage2()) {
            // One retry: a lost mail costs a whole request, a second call costs a fraction of a cent.
            for (int attempt = 1; attempt <= 2 && extraction == null; attempt++) {
                try {
                    extraction = stage2.classify(placed, sessionId, s1.hints());
                    llmError = null;
                } catch (RuntimeException e) {
                    log.warn("Stage 2 failed for mail {} (attempt {})", mail.id(), attempt, e);
                    llmError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
        }
        if (extraction == null && s1.type() == null) {
            // LLM unavailable and no hard fact: route by keywords so a request still gets priced.
            MailType guess = KeywordFallback.guess(placed.subject(), placed.body());
            extraction = new Extraction(guess, 0.3, "keyword fallback, LLM unavailable", null, null, null, null, null);
        }

        RouteDecision decision = RouteDecision.decide(s1, extraction, llmError,
                newSession ? null : facts.threadSessionType(),
                settings.getDouble("routerConfidenceThreshold", DEFAULT_CONFIDENCE_THRESHOLD));

        if (newSession && !sessionType(decision.type()).equals(initialType)) {
            OffsetDateTime deadline = decision.type() == MailType.NEW_RFQ ? mail.sentAt().plus(QUOTE_WINDOW) : null;
            sessions.setType(sessionId, sessionType(decision.type()), deadline);
        }
        mails.setRoute(mail.id(), decision.type().name(), decision.confidence());
        events.append(sessionId, mail.id(), "route.decision", extraction != null ? Actor.llm : Actor.system,
                "Routed as %s (stage %d, %.2f)".formatted(decision.type(), decision.stage(), decision.confidence()),
                decisionPayload(decision, s1, extraction));
        if (decision.needsHuman()) sessions.flagNeedsHuman(sessionId, String.join("; ", decision.problems()));

        RoutedMail routed = new RoutedMail(placed, decision.type(), decision.confidence(), decision.stage(),
                sessionId, extraction);
        if (decision.dispatch()) dispatcher.dispatch(routed);
        return routed;
    }

    /** Carrier and rate-desk answers are read by pattern; the LLM is only asked when that is ambiguous. */
    private Extraction parsedBid(Stage1Classifier.Result s1, StoredMail mail) {
        if (s1.type() != MailType.CARRIER_BID && s1.type() != MailType.RATEDESK_REPLY) return null;
        return BidMailParser.parse(mail.subject(), mail.body())
                .map(bid -> new Extraction(s1.type(), 1.0, "read by pattern, no LLM call", null, null, null, bid, null))
                .orElse(null);
    }

    /**
     * A human corrects the route of a mail from the UI. The latest stage-2 extraction is reused, so a
     * mail re-routed to NEW_RFQ still carries its extracted shipment data.
     */
    public RoutedMail reroute(long mailId, MailType type) {
        StoredMail mail = mails.findById(mailId).orElseThrow(() -> new IllegalArgumentException("Unknown mail " + mailId));
        String previous = mails.routeType(mailId).orElse(null);
        long sessionId;
        if (mail.sessionId() == null) {
            sessionId = sessions.create(sessionType(type), mail.fromAddr(), mail.subject(), deadlineFor(type, mail));
            mails.assignSession(mailId, sessionId);
            mail = mails.findById(mailId).orElseThrow();
        } else {
            sessionId = mail.sessionId();
            String current = sessions.find(sessionId).map(SessionService.SessionView::type).orElse("OTHER");
            if ("OTHER".equals(current) && !"OTHER".equals(sessionType(type))) {
                sessions.setType(sessionId, sessionType(type), deadlineFor(type, mail));
            }
        }
        mails.setRoute(mailId, type.name(), 1.0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", previous);
        payload.put("to", type.name());
        events.append(sessionId, mailId, "human.reroute", Actor.human, "Re-routed to " + type + " by a human", payload);

        RoutedMail routed = new RoutedMail(mail, type, 1.0, 0, sessionId, repo.lastExtraction(mailId).orElse(null));
        dispatcher.dispatch(routed);
        return routed;
    }

    /** Session type for the first mail of a thread. */
    static String sessionType(MailType type) {
        return switch (type) {
            case NEW_RFQ -> "RFQ";
            case TRACKING_QUESTION -> "TRACKING";
            case TARIFF -> "TARIFF";
            default -> "OTHER";
        };
    }

    private static OffsetDateTime deadlineFor(MailType type, StoredMail mail) {
        return type == MailType.NEW_RFQ ? mail.sentAt().plus(QUOTE_WINDOW) : null;
    }

    private static Map<String, Object> decisionPayload(RouteDecision d, Stage1Classifier.Result s1, Extraction ex) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("stage", d.stage());
        p.put("type", d.type().name());
        p.put("confidence", d.confidence());
        p.put("stage1Type", s1.type() == null ? null : s1.type().name());
        p.put("stage2Type", ex == null || ex.type() == null ? null : ex.type().name());
        p.put("hints", s1.hints());
        p.put("reasoning", ex == null ? null : ex.reasoning());
        p.put("problems", d.problems());
        p.put("dispatched", d.dispatch());
        p.put("extraction", ex);
        return p;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }
}
