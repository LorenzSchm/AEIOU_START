package ai.aeiou.forwarder.rfq;

import static ai.aeiou.forwarder.rfq.RfqEvents.payload;

import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.pricing.FxRates;
import ai.aeiou.forwarder.pricing.PriceQuote;
import ai.aeiou.forwarder.pricing.PricingService;
import ai.aeiou.forwarder.pricing.Shipment;
import ai.aeiou.forwarder.rfq.QuoteDecider.Decision;
import ai.aeiou.forwarder.rfq.QuoteDecider.Evaluated;
import ai.aeiou.forwarder.rfq.ReferenceRepository.Carrier;
import ai.aeiou.forwarder.rfq.RfqRepository.BidRequestRow;
import ai.aeiou.forwarder.rfq.RfqRepository.RfqRecord;
import ai.aeiou.forwarder.rfq.port.PortalRateLimiter;
import ai.aeiou.forwarder.rfq.port.RatePortal;
import ai.aeiou.forwarder.rfq.port.RatePortal.Lane;
import ai.aeiou.forwarder.rfq.port.RatePortal.Rate;
import ai.aeiou.forwarder.rfq.port.RatePortal.RateQuery;
import ai.aeiou.forwarder.rfq.port.RatePortal.RateResult;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.session.SessionStatus;
import ai.aeiou.forwarder.writer.EmailWriter;
import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import ai.aeiou.forwarder.writer.EmailWriter.Purpose;
import ai.aeiou.forwarder.writer.EmailWriter.WrittenMail;
import ai.aeiou.forwarder.writer.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Runs one RFQ from extraction to quote (ARCHITECTURE.md §4.1).
 *
 * <pre>
 * start():   sheets (in memory) ─┐
 *            portal lanes ───────┼─► in parallel on virtual threads:
 *                                │     portal rate per airline · bid mails · truck mails · rate desk
 *            decision timer at min(arrival + bidWait, hard stop)
 * bids:      onBidAnswered() ──► decide early once every request is answered
 * decide():  claimed once per session (rfq.decided_at) ─► QuoteDecider ─► QuoteSender
 * </pre>
 */
@Service
public class QuoteOrchestrator {

    /** Safety margin before the customer's deadline: a late quote scores nothing. */
    static final Duration HARD_STOP_BEFORE_DEADLINE = Duration.ofSeconds(60);
    /** 5U deadline when the session has none: an RFQ expires 6 minutes after it was sent. */
    static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(6);
    /** Portal calls must finish a little before the decision, so their candidates are in. */
    static final Duration PORTAL_MARGIN = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(QuoteOrchestrator.class);

    private final PricingService pricing;
    private final RatePortal portal;
    private final PortalRateLimiter limiter;
    private final ReferenceRepository reference;
    private final RfqRepository repo;
    private final EmailWriter writer;
    private final MailGateway gateway;
    private final SessionService sessions;
    private final RfqEvents events;
    private final RfqSettings settings;
    private final FxRates fx;
    private final DecisionScheduler scheduler;
    private final QuoteSender sender;
    private final QuoteDecider decider = new QuoteDecider();

    /** Sessions whose fan-out is still running; an early decision must wait for it. */
    private final Set<Long> fanningOut = ConcurrentHashMap.newKeySet();

    public QuoteOrchestrator(PricingService pricing, RatePortal portal, PortalRateLimiter limiter,
                             ReferenceRepository reference, RfqRepository repo, EmailWriter writer, MailGateway gateway,
                             SessionService sessions, RfqEvents events, RfqSettings settings, FxRates fx,
                             DecisionScheduler scheduler, QuoteSender sender) {
        this.pricing = pricing;
        this.portal = portal;
        this.limiter = limiter;
        this.reference = reference;
        this.repo = repo;
        this.writer = writer;
        this.gateway = gateway;
        this.sessions = sessions;
        this.events = events;
        this.settings = settings;
        this.fx = fx;
        this.scheduler = scheduler;
        this.sender = sender;
    }

    // fan-out ----------------------------------------------------------------------------------------------------

    /** Collects prices from every source in parallel and arms the decision timer. Returns when all requests are out. */
    public void start(RfqRecord rfq) {
        long sessionId = rfq.sessionId();
        Instant decideAt = decisionTime(sessionId);
        scheduler.schedule(sessionId, decideAt, () -> decide(sessionId, "timer"));
        fanningOut.add(sessionId);
        try {
            Shipment shipment = new Shipment(rfq.originAirport(), rfq.destination(), rfq.weightKg(), rfq.volumeCbm());
            List<PriceQuote> sheetQuotes = sheetPrices(sessionId, shipment);
            Optional<Lane> lane = portalLane(sessionId, rfq);
            Set<String> pricedAirlines = pricedAirlines(sheetQuotes, lane);
            AtomicBoolean ratedeskAsked = new AtomicBoolean();
            Instant portalBudgetEnd = decideAt.minus(PORTAL_MARGIN);

            try (ExecutorService parallel = Executors.newVirtualThreadPerTaskExecutor()) {
                lane.ifPresent(l -> branch(parallel, sessionId, "portal", () -> {
                    int priced = portalRates(rfq, l, portalBudgetEnd);
                    if (priced == 0 && sheetQuotes.isEmpty()) askRatedesk(rfq, "portal returned no rate", ratedeskAsked);
                }));
                branch(parallel, sessionId, "airline bids", () -> requestAirlineBids(rfq, pricedAirlines));
                if (rfq.doorPickup()) branch(parallel, sessionId, "truck requests", () -> requestTrucks(rfq));
                if (lane.isEmpty() && sheetQuotes.isEmpty()) {
                    branch(parallel, sessionId, "rate desk",
                            () -> askRatedesk(rfq, "lane not on the portal and in no sheet", ratedeskAsked));
                }
            }

            int open = repo.openBidRequests(sessionId).size();
            if (open > 0) sessions.setStatus(sessionId, SessionStatus.WAITING_FOR_BIDS);
            events.system(sessionId, "rfq.fanout_done", "All price requests out, " + open + " mail answers pending",
                    payload("openRequests", open, "decideAt", decideAt.toString()));
        } catch (RuntimeException e) {
            log.error("Fan-out failed for session {}", sessionId, e);
            events.system(sessionId, "rfq.error", "Fan-out failed: " + e.getMessage(), payload("error", e.getClass().getSimpleName()));
        } finally {
            fanningOut.remove(sessionId);
        }
        onBidAnswered(sessionId);
    }

    /** Runs one fan-out branch; a failing branch is logged as an event and never takes the others down. */
    private void branch(ExecutorService parallel, long sessionId, String name, Runnable work) {
        parallel.submit(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                log.warn("Fan-out branch {} failed for session {}", name, sessionId, e);
                events.system(sessionId, "rfq.error", "Branch " + name + " failed: " + e.getMessage(),
                        payload("branch", name, "error", e.getClass().getSimpleName()));
            }
        });
    }

    /** Re-arms decision timers lost in a restart (they live in memory only). */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingDecisions() {
        for (long sessionId : repo.undecidedSessions(Instant.now().minus(DEFAULT_DEADLINE))) {
            events.system(sessionId, "rfq.recovered", "Decision timer re-armed after restart", payload());
            onBidAnswered(sessionId);
        }
    }

    /** Called after every bid / rate-desk answer: decides as soon as nobody is left to wait for. */
    public void onBidAnswered(long sessionId) {
        if (fanningOut.contains(sessionId)) return; // start() checks again when the fan-out is done
        if (repo.openBidRequests(sessionId).isEmpty()) {
            decide(sessionId, "all requested parties answered");
        } else if (!scheduler.isScheduled(sessionId)) {
            // e.g. after a restart: the in-memory timer is gone, the deadline is not
            scheduler.schedule(sessionId, decisionTime(sessionId), () -> decide(sessionId, "timer"));
        }
    }

    /** Decision point: arrival + bidWait, but never later than the hard stop before the deadline. */
    Instant decisionTime(long sessionId) {
        Instant arrival = events.arrival(sessionId);
        Instant deadline = sessions.find(sessionId)
                .map(SessionService.SessionView::deadlineAt)
                .map(d -> d.toInstant())
                .orElse(arrival.plus(DEFAULT_DEADLINE));
        Instant hardStop = min(deadline.minus(HARD_STOP_BEFORE_DEADLINE), arrival.plus(settings.quoteDeadline()));
        return min(arrival.plus(settings.bidWait()), hardStop);
    }

    private List<PriceQuote> sheetPrices(long sessionId, Shipment shipment) {
        try {
            List<PriceQuote> quotes = pricing.priceFromSheets(shipment);
            for (PriceQuote q : quotes) {
                repo.insertCandidate(sessionId, new Candidate(null, CandidateSource.sheet, q.airlineCode(), q.airlineName(), q.total(),
                        q.currency(), q.totalEur(), q.transitDays(), q.rateId(), null, q.breakdown()));
            }
            events.system(sessionId, "pricing.sheets", quotes.size() + " sheet prices", payload("quotes", quotes.stream()
                    .map(q -> q.airlineName() + " " + q.currency() + " " + q.total() + " / EUR " + q.totalEur()).toList()));
            return quotes;
        } catch (RuntimeException e) {
            log.warn("Sheet pricing failed for session {}", sessionId, e);
            events.system(sessionId, "pricing.error", "Sheet pricing failed: " + e.getMessage(), payload());
            return List.of();
        }
    }

    private Optional<Lane> portalLane(long sessionId, RfqRecord rfq) {
        if (!settings.portalEnabled()) return Optional.empty();
        try {
            Optional<Lane> lane = portal.lanes().stream()
                    .filter(l -> l.origin().equalsIgnoreCase(rfq.originAirport()) && l.destination().equalsIgnoreCase(rfq.destination()))
                    .findFirst();
            events.system(sessionId, "portal.lane", lane.map(l -> "Portal lane with " + l.airlines().size() + " airlines")
                            .orElse("Lane " + rfq.originAirport() + "-" + rfq.destination() + " not on the portal"),
                    payload("airlines", lane.map(l -> l.airlines().stream().map(RatePortal.Airline::code).toList()).orElse(null)));
            return lane;
        } catch (RuntimeException e) {
            events.system(sessionId, "portal.error", "Portal lanes unavailable: " + e.getMessage(), payload());
            return Optional.empty();
        }
    }

    /** One rate per airline on the lane, all in parallel. Returns how many airlines got a price. */
    private int portalRates(RfqRecord rfq, Lane lane, Instant budgetEnd) {
        if (rfq.weightKg() == null) return 0;
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService parallel = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RatePortal.Airline airline : lane.airlines()) {
                results.add(parallel.submit(() -> portalAirline(rfq, airline, budgetEnd)));
            }
        }
        int priced = 0;
        for (Future<Boolean> f : results) {
            try {
                if (f.get()) priced++;
            } catch (ExecutionException e) {
                log.warn("Portal rate failed for session {}", rfq.sessionId(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return priced;
    }

    private boolean portalAirline(RfqRecord rfq, RatePortal.Airline airline, Instant budgetEnd) throws InterruptedException {
        RateQuery query = new RateQuery(rfq.originAirport(), rfq.destination(), airline.code(), rfq.weightKg(), rfq.volumeCbm());
        Optional<Rate> rate = fetchRate(rfq.sessionId(), query, budgetEnd, false);
        rate.ifPresent(r -> recordPortalRate(rfq.sessionId(), r, null));
        if (rate.isPresent() && settings.portalProbeNextBreak()) probeNextBreak(rfq, airline, budgetEnd);
        return rate.isPresent();
    }

    /**
     * Billing at the next weight break can be cheaper than the exact weight (a higher break has a lower rate).
     * The portal prices exactly the weight we ask for, so we ask once more for the next break, if a token is free.
     */
    private void probeNextBreak(RfqRecord rfq, RatePortal.Airline airline, Instant budgetEnd) throws InterruptedException {
        Optional<Integer> nextBreak = portal.brackets(airline.code()).flatMap(b -> b.breaksKg().stream()
                .filter(k -> BigDecimal.valueOf(k).compareTo(rfq.chargeableKg()) > 0)
                .min(Integer::compare));
        if (nextBreak.isEmpty()) return;
        RateQuery probe = new RateQuery(rfq.originAirport(), rfq.destination(), airline.code(),
                BigDecimal.valueOf(nextBreak.get()), rfq.volumeCbm());
        fetchRate(rfq.sessionId(), probe, budgetEnd, true)
                .ifPresent(r -> recordPortalRate(rfq.sessionId(), r, nextBreak.get()));
    }

    /**
     * One rate read through the shared token bucket. On 429 it waits for {@code Retry-After} only while still
     * inside the budget; on 503 it records the portal's fallback hint.
     *
     * @param optional optional calls never wait for a token
     */
    private Optional<Rate> fetchRate(long sessionId, RateQuery q, Instant budgetEnd, boolean optional) throws InterruptedException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            boolean token = optional ? limiter.tryAcquire() : limiter.acquire(budgetEnd);
            if (!token) {
                events.system(sessionId, "portal.skipped", "Rate " + q.airline() + " skipped: rate limit, no token within budget",
                        payload("airline", q.airline(), "weightKg", q.weightKg(), "optional", optional));
                return Optional.empty();
            }
            RateResult res = portal.rate(q);
            limiter.observe(res);
            Map<String, Object> p = payload("airline", q.airline(), "weightKg", q.weightKg(), "status", res.status(),
                    "rateLimitRemaining", res.rateLimitRemaining(), "rateLimitLimit", res.rateLimitLimit(),
                    "retryAfterSeconds", res.retryAfter() == null ? null : res.retryAfter().toSeconds(),
                    "message", res.message(), "attempt", attempt);
            if (res.ok()) {
                Rate r = res.rate();
                p.putAll(payload("rateId", r.rateId(), "total", r.total(), "currency", r.currency(), "transitDays", r.transitDays(),
                        "validUntil", r.validUntil() == null ? null : r.validUntil().toString()));
                events.system(sessionId, "portal.rate", "Portal " + r.airline() + ": " + r.currency() + " " + r.total()
                        + ", " + r.transitDays() + " days", p);
                return Optional.of(r);
            }
            if (res.rateLimited()) {
                Duration wait = res.retryAfter() == null ? Duration.ofSeconds(60) : res.retryAfter();
                boolean retry = !optional && Instant.now().plus(wait).isBefore(budgetEnd);
                events.system(sessionId, "portal.error", "Portal 429 for " + q.airline() + (retry ? ", retrying after " + wait.toSeconds() + "s" : ", out of budget"), p);
                if (!retry) return Optional.empty();
                continue; // the limiter now blocks until the window reopens
            }
            String kind = res.unavailable() ? "portal.unavailable" : "portal.error";
            events.system(sessionId, kind, "Portal " + res.status() + " for " + q.airline()
                    + (res.message() == null ? "" : ": " + res.message()), p);
            return Optional.empty();
        }
        return Optional.empty();
    }

    private void recordPortalRate(long sessionId, Rate r, Integer probedBreakKg) {
        fx.learn(r.currency(), r.fxEurPerUnit());
        BigDecimal totalEur = "EUR".equalsIgnoreCase(r.currency()) ? r.total()
                : r.fxEurPerUnit() != null ? r.total().multiply(r.fxEurPerUnit())
                : fx.toEur(r.total(), r.currency()).orElse(null);
        if (totalEur == null) {
            events.system(sessionId, "pricing.fx_unknown", "No EUR rate for " + r.currency() + ", portal rate ignored", payload());
            return;
        }
        Map<String, Object> breakdown = payload("chargeableKg", r.chargeableKg(), "datasetVersion", r.datasetVersion(),
                "fxEurPerUnit", r.fxEurPerUnit(), "probedBreakKg", probedBreakKg);
        repo.insertCandidate(sessionId, new Candidate(null, CandidateSource.portal, r.airlineCode(), r.airline(), r.total(),
                r.currency(), totalEur, r.transitDays(), r.rateId(), r.validUntil(), breakdown));
    }

    /** Airlines we can already price for this lane (sheet or portal), by normalised name and code. */
    private static Set<String> pricedAirlines(List<PriceQuote> sheetQuotes, Optional<Lane> lane) {
        Set<String> priced = new HashSet<>();
        for (PriceQuote q : sheetQuotes) {
            priced.add(key(q.airlineName()));
            priced.add(key(q.airlineCode()));
        }
        lane.ifPresent(l -> l.airlines().forEach(a -> {
            priced.add(key(a.name()));
            priced.add(key(a.code()));
        }));
        priced.remove("");
        return priced;
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private void requestAirlineBids(RfqRecord rfq, Set<String> pricedAirlines) {
        long sessionId = rfq.sessionId();
        if (!settings.bidsEnabled()) {
            events.system(sessionId, "bid.disabled", "Bid requests are switched off", payload());
            return;
        }
        RfqSettings.BidPolicy policy = settings.bidPolicy();
        List<Carrier> airlines = reference.airlinesServing(rfq.destination()).stream()
                .filter(c -> policy == RfqSettings.BidPolicy.all
                        || !(pricedAirlines.contains(key(c.name())) || pricedAirlines.contains(key(c.code()))))
                .toList();
        if (airlines.isEmpty()) {
            events.system(sessionId, "bid.none", "No airline to ask for a bid (policy " + policy + ")", payload());
            return;
        }
        // One body for all airlines: same facts, and one writer call instead of one per airline keeps LLM cost down.
        WrittenMail mail = writer.write(new MailIntent(sessionId, Purpose.BID_REQUEST, List.of(), shipmentFacts(rfq),
                List.of(), "en", null, null));
        String subject = subjectOr(mail, "Rate request " + lane(rfq) + ", " + kg(rfq) + " kg");
        sendRequests(sessionId, airlines, "airline", subject, mail.body());
    }

    private void requestTrucks(RfqRecord rfq) {
        long sessionId = rfq.sessionId();
        String zone = RfqHandler.zipZone(rfq.originZip());
        if (zone == null) {
            events.system(sessionId, "truck.none", "Door pickup requested but no postcode to find a trucker", payload());
            return;
        }
        List<Carrier> truckers = reference.truckersServing(zone);
        if (truckers.isEmpty()) {
            events.system(sessionId, "truck.none", "No trucker serves postcode zone " + zone, payload("zone", zone));
            return;
        }
        Map<String, Object> facts = new LinkedHashMap<>(shipmentFacts(rfq));
        facts.put("pickupZip", rfq.originZip());
        facts.put("deliverToAirport", rfq.originAirport());
        WrittenMail mail = writer.write(new MailIntent(sessionId, Purpose.TRUCK_REQUEST, List.of(), facts, List.of(),
                "en", null, null));
        String subject = subjectOr(mail, "Pickup " + rfq.originZip() + " to " + rfq.originAirport() + ", " + kg(rfq) + " kg");
        sendRequests(sessionId, truckers, "trucker", subject, mail.body());
    }

    private void askRatedesk(RfqRecord rfq, String why, AtomicBoolean asked) {
        if (!asked.compareAndSet(false, true)) return;
        long sessionId = rfq.sessionId();
        String to = settings.ratedeskAddress();
        WrittenMail mail = writer.write(new MailIntent(sessionId, Purpose.RATEDESK_REQUEST, List.of(), shipmentFacts(rfq),
                List.of(), "en", null, null));
        StoredMail sent = gateway.send(sessionId, OutgoingMail.fresh(to, subjectOr(mail, "Rate request " + lane(rfq)), mail.body()));
        repo.insertBidRequest(sessionId, null, "ratedesk", to, sent.id());
        events.system(sessionId, sent.id(), "bid.requested", "Asked the rate desk: " + why, payload("to", to, "kind", "ratedesk"));
    }

    private void sendRequests(long sessionId, List<Carrier> carriers, String kind, String subject, String body) {
        try (ExecutorService parallel = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Carrier c : carriers) {
                parallel.submit(() -> {
                    try {
                        StoredMail sent = gateway.send(sessionId, OutgoingMail.fresh(c.email(), subject, body));
                        repo.insertBidRequest(sessionId, c.id(), kind, c.email(), sent.id());
                        events.system(sessionId, sent.id(), "bid.requested", "Asked " + c.name() + " for a " + kind + " price",
                                payload("carrierId", c.id(), "to", c.email(), "kind", kind));
                    } catch (RuntimeException e) {
                        log.warn("Bid request to {} failed", c.email(), e);
                        events.system(sessionId, "bid.error", "Could not ask " + c.name() + ": " + e.getMessage(),
                                payload("carrierId", c.id()));
                    }
                });
            }
        }
    }

    private static Map<String, Object> shipmentFacts(RfqRecord rfq) {
        return payload("origin", rfq.originAirport(), "destination", rfq.destination(), "weight", kg(rfq.weightKg()),
                "volume", rfq.volumeCbm() == null ? null : rfq.volumeCbm().stripTrailingZeros().toPlainString(),
                "pieces", rfq.pieces(), "readyDate", rfq.readyDate() == null ? null : rfq.readyDate().toString(),
                "commodity", rfq.commodity());
    }

    private static String subjectOr(WrittenMail mail, String fallback) {
        return mail.subject() == null || mail.subject().isBlank() ? fallback : mail.subject();
    }

    private static String lane(RfqRecord rfq) {
        return rfq.originAirport() + "-" + rfq.destination();
    }

    private static String kg(RfqRecord rfq) {
        return kg(rfq.chargeableKg() != null ? rfq.chargeableKg() : rfq.weightKg());
    }

    private static String kg(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    // decision ---------------------------------------------------------------------------------------------------

    /**
     * Decides and quotes, at most once per session no matter how many timers and answers race here.
     *
     * @return true if this call made the decision
     */
    public boolean decide(long sessionId, String trigger) {
        if (!repo.claimDecision(sessionId)) return false;
        scheduler.cancel(sessionId);
        try {
            RfqRecord rfq = repo.findRfq(sessionId).orElseThrow();
            for (BidRequestRow open : repo.openBidRequests(sessionId)) {
                events.system(sessionId, "bid.timeout", "No answer from " + (open.carrierName() != null ? open.carrierName() : open.toAddr())
                        + " in time", payload("bidRequestId", open.id(), "kind", open.kind()));
            }
            Decision decision = decider.decide(rfq.mode(), rfq.doorPickup(), repo.candidates(sessionId));
            Candidate chosen = persist(sessionId, decision);
            events.system(sessionId, "pricing.decision", chosen == null ? "No quotable price" : "Chose " + chosen.label()
                            + " " + Money.format(chosen.total(), chosen.currency()),
                    payload("trigger", trigger, "reasoning", decision.reasoning(), "chosenCandidateId", chosen == null ? null : chosen.id(),
                            "rateId", chosen == null ? null : chosen.rateId(), "candidates", decision.evaluated().stream()
                                    .map(e -> payload("label", e.candidate().label(), "totalEur", e.candidate().totalEur(),
                                            "transitDays", e.candidate().transitDays(), "chosen", e.chosen(),
                                            "rejectReason", e.rejectReason()))
                                    .toList()));
            if (chosen == null) {
                sessions.flagNeedsHuman(sessionId, "No price to quote: " + decision.reasoning());
                return true;
            }
            sender.deliver(rfq, chosen);
        } catch (RuntimeException e) {
            log.error("Decision failed for session {}", sessionId, e);
            events.system(sessionId, "rfq.error", "Decision failed: " + e.getMessage(), payload("error", e.getClass().getSimpleName()));
            sessions.flagNeedsHuman(sessionId, "Decision failed: " + e.getMessage());
        }
        return true;
    }

    /** Stores the verdict of every candidate (inserting the combined ones) and returns the chosen one with its id. */
    private Candidate persist(long sessionId, Decision decision) {
        Candidate chosen = null;
        for (Evaluated e : decision.evaluated()) {
            Candidate c = e.candidate();
            if (c.id() == null) c = c.withId(repo.insertCandidate(sessionId, c));
            repo.markCandidate(c.id(), e.chosen(), e.rejectReason());
            if (e.chosen()) chosen = c;
        }
        return chosen;
    }
}
