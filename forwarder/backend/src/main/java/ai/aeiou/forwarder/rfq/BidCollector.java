package ai.aeiou.forwarder.rfq;

import static ai.aeiou.forwarder.rfq.RfqEvents.payload;

import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.pricing.FxRates;
import ai.aeiou.forwarder.rfq.RfqRepository.BidRequestRow;
import ai.aeiou.forwarder.router.Extraction;
import ai.aeiou.forwarder.router.RoutedMail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Records airline, trucker and rate-desk answers against our requests and turns them into price candidates. */
@Component
public class BidCollector {

    private final RfqRepository repo;
    private final RfqEvents events;
    private final FxRates fx;
    private final QuoteOrchestrator orchestrator;

    public BidCollector(RfqRepository repo, RfqEvents events, FxRates fx, QuoteOrchestrator orchestrator) {
        this.repo = repo;
        this.events = events;
        this.fx = fx;
        this.orchestrator = orchestrator;
    }

    public void onReply(RoutedMail routed) {
        long sessionId = routed.sessionId();
        StoredMail mail = routed.mail();
        Optional<BidRequestRow> request = repo.findBidRequestForReply(sessionId, mail.fromAddr(), mail.inReplyToExt());
        if (request.isEmpty()) {
            events.system(sessionId, mail.id(), "bid.unmatched", "Answer from " + mail.fromAddr() + " matches none of our requests",
                    payload("from", mail.fromAddr()));
            return;
        }
        BidRequestRow row = request.get();
        Extraction.Bid bid = routed.extraction() == null ? null : routed.extraction().bid();
        boolean declined = bid != null && Boolean.TRUE.equals(bid.declined());
        String currency = bid == null ? null : FxRates.normalise(bid.currency());
        BigDecimal price = bid == null || declined ? null : bid.price();
        Integer transit = bid == null ? null : bid.transitDays();
        OffsetDateTime validUntil = bid == null ? null : parseValidity(bid.validUntil());
        repo.recordBidAnswer(row.id(), mail.id(), price, currency, transit, validUntil, declined);

        String who = who(row, bid);
        if (declined || price == null || price.signum() <= 0) {
            events.system(sessionId, mail.id(), declined ? "bid.declined" : "bid.unparsed",
                    who + (declined ? " declined" : " answered without a usable price"),
                    payload("bidRequestId", row.id(), "notes", bid == null ? null : bid.notes()));
        } else {
            recordCandidate(sessionId, mail, row, bid, who, price, currency, transit, validUntil);
        }
        orchestrator.onBidAnswered(sessionId);
    }

    private void recordCandidate(long sessionId, StoredMail mail, BidRequestRow row, Extraction.Bid bid, String who,
                                 BigDecimal price, String currency, Integer transit, OffsetDateTime validUntil) {
        Optional<BigDecimal> eur = fx.toEur(price, currency);
        if (eur.isEmpty()) {
            events.system(sessionId, mail.id(), "pricing.fx_unknown", who + " quoted in " + currency + ", no EUR rate known",
                    payload("bidRequestId", row.id()));
            return;
        }
        CandidateSource source = switch (row.kind()) {
            case "trucker" -> CandidateSource.truck;
            case "ratedesk" -> CandidateSource.ratedesk;
            default -> CandidateSource.bid;
        };
        Candidate c = new Candidate(null, source, row.carrierCode(), who, price, currency, eur.get(), transit, null,
                validUntil, payload("bidRequestId", row.id(), "mailId", mail.id(), "notes", bid.notes()));
        long id = repo.insertCandidate(sessionId, c);
        boolean late = repo.findRfq(sessionId).map(r -> r.decidedAt() != null).orElse(false);
        events.system(sessionId, mail.id(), "bid.received", who + ": " + currency + " " + price
                        + (transit == null ? "" : ", " + transit + " days") + (late ? " (after the decision)" : ""),
                payload("bidRequestId", row.id(), "candidateId", id, "kind", row.kind(), "totalEur", eur.get(), "late", late));
    }

    /** The rate desk answers for an airline; carriers answer for themselves. */
    private static String who(BidRequestRow row, Extraction.Bid bid) {
        if ("ratedesk".equals(row.kind())) {
            return bid != null && bid.airlineOrCompany() != null ? bid.airlineOrCompany() : "Rate desk";
        }
        return row.carrierName() != null ? row.carrierName() : row.toAddr();
    }

    static OffsetDateTime parseValidity(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.trim());
        } catch (DateTimeParseException notDateTime) {
            try {
                return LocalDate.parse(s.trim()).atTime(23, 59).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException notDate) {
                return null;
            }
        }
    }
}
