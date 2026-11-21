package ai.aeiou.forwarder.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import ai.aeiou.forwarder.rfq.QuoteDecider.Decision;
import ai.aeiou.forwarder.rfq.QuoteDecider.Evaluated;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class QuoteDeciderTest {

    private final QuoteDecider decider = new QuoteDecider();
    private final AtomicLong ids = new AtomicLong();

    @Test
    void cheapestComparesInEurNotInNominalAmounts() {
        Candidate sheet = candidate(CandidateSource.sheet, "Helix", "1000.00", "EUR", "1000.00", 4);
        Candidate portalUsd = candidate(CandidateSource.portal, "Vega", "1050.00", "USD", "966.00", 6);
        Candidate pricier = candidate(CandidateSource.portal, "Orion", "1200.00", "EUR", "1200.00", 2);

        Decision d = decider.decide("cheapest", false, List.of(sheet, portalUsd, pricier));

        assertThat(d.chosen()).isSameAs(portalUsd);
        assertThat(d.evaluated()).hasSize(3).filteredOn(Evaluated::chosen).hasSize(1);
        assertThat(reason(d, sheet)).startsWith("pricier than Vega (portal)");
    }

    @Test
    void cheapestTieGoesToTheFasterOption() {
        Candidate slow = candidate(CandidateSource.sheet, "Slow", "900", "EUR", "900", 7);
        Candidate fast = candidate(CandidateSource.sheet, "Fast", "900", "EUR", "900", 3);

        assertThat(decider.decide("cheapest", false, List.of(slow, fast)).chosen()).isSameAs(fast);
    }

    @Test
    void fastestTakesFewestDaysAndBreaksTiesByPrice() {
        Candidate cheapSlow = candidate(CandidateSource.sheet, "A", "800", "EUR", "800", 6);
        Candidate fastDear = candidate(CandidateSource.portal, "B", "1400", "EUR", "1400", 2);
        Candidate fastCheaper = candidate(CandidateSource.sheet, "C", "1300", "EUR", "1300", 2);
        Candidate unknownTransit = candidate(CandidateSource.sheet, "D", "500", "EUR", "500", null);

        Decision d = decider.decide("fastest", false, List.of(cheapSlow, fastDear, fastCheaper, unknownTransit));

        assertThat(d.chosen()).isSameAs(fastCheaper);
        assertThat(reason(d, unknownTransit)).contains("transit unknown");
    }

    @Test
    void unknownModeFallsBackToCheapest() {
        Candidate a = candidate(CandidateSource.sheet, "A", "800", "EUR", "800", 6);
        Candidate b = candidate(CandidateSource.sheet, "B", "900", "EUR", "900", 1);

        assertThat(decider.decide(null, false, List.of(a, b)).chosen()).isSameAs(a);
    }

    @Test
    void carrierBidsAreReducedToTheMostBalancedOne() {
        Candidate mid = candidate(CandidateSource.bid, "Mid", "1000", "EUR", "1000", 5);
        Candidate balanced = candidate(CandidateSource.bid, "Balanced", "1300", "EUR", "1300", 2);
        Candidate cheapest = candidate(CandidateSource.bid, "Cheapest", "900", "EUR", "900", 9);

        Decision d = decider.decide("cheapest", false, List.of(mid, balanced, cheapest));

        // scores (min price 900, min transit 2): Mid 1.806, Balanced 1.222, Cheapest 2.750
        assertThat(d.chosen()).isSameAs(balanced);
        assertThat(reason(d, cheapest)).isEqualTo("less balanced bid than Balanced (bid) (score 2.750 vs 1.222)");
        assertThat(d.reasoning()).contains("most balanced bid Balanced (bid)");
    }

    @Test
    void theBalancedBidStillCompetesWithSheetAndPortalPrices() {
        Candidate bid = candidate(CandidateSource.bid, "Bidder", "1000", "EUR", "1000", 3);
        Candidate sheet = candidate(CandidateSource.sheet, "Sheet", "950", "EUR", "950", 5);

        assertThat(decider.decide("cheapest", false, List.of(bid, sheet)).chosen()).isSameAs(sheet);
        assertThat(decider.decide("fastest", false, List.of(bid, sheet)).chosen()).isSameAs(bid);
    }

    @Test
    void balanceScoreWeighsPriceAndSpeedEqually() {
        assertThat(QuoteDecider.balanceScore(new BigDecimal("1000"), new BigDecimal("1000"), 3, 3)).isEqualTo(1.0);
        // 20% dearer but 20% faster scores the same as the reverse
        double dearerFaster = QuoteDecider.balanceScore(new BigDecimal("1200"), new BigDecimal("1000"), 4, 4);
        double cheaperSlower = QuoteDecider.balanceScore(new BigDecimal("1000"), new BigDecimal("1000"), 6, 5);
        assertThat(dearerFaster).isCloseTo(cheaperSlower, within(1e-9));
    }

    @Test
    void doorPickupCombinesEveryAirOptionWithTheCheapestTrucker() {
        Candidate air = candidate(CandidateSource.portal, "Helix", "1000.00", "USD", "920.00", 4);
        Candidate air2 = candidate(CandidateSource.sheet, "Vega", "1000.00", "EUR", "1000.00", 3);
        Candidate truck = candidate(CandidateSource.truck, "Rheinufer", "150.00", "EUR", "150.00", null);
        Candidate pricierTruck = candidate(CandidateSource.truck, "Maintal", "190.00", "EUR", "190.00", null);

        Decision d = decider.decide("cheapest", true, List.of(air, air2, truck, pricierTruck));

        Candidate chosen = d.chosen();
        assertThat(chosen.source()).isEqualTo(CandidateSource.combined);
        assertThat(chosen.id()).isNull();
        assertThat(chosen.total()).isEqualByComparingTo("1070.00");
        assertThat(chosen.currency()).isEqualTo("EUR");
        assertThat(chosen.airlineName()).isEqualTo("Helix");
        assertThat(chosen.rateId()).isEqualTo(air.rateId());
        assertThat(chosen.breakdown()).containsEntry("truckCandidateId", truck.id()).containsEntry("airCandidateId", air.id());
        assertThat(reason(d, pricierTruck)).startsWith("pricier trucker");
        assertThat(d.evaluated()).hasSize(6); // 4 inputs + 2 combined
    }

    @Test
    void doorPickupWithoutTruckerPriceHasNothingToQuote() {
        Candidate air = candidate(CandidateSource.sheet, "Helix", "1000", "EUR", "1000", 4);

        Decision d = decider.decide("cheapest", true, List.of(air));

        assertThat(d.hasQuote()).isFalse();
        assertThat(reason(d, air)).contains("no trucker price");
    }

    @Test
    void candidatesWithoutUsablePriceAreRejected() {
        Candidate zero = candidate(CandidateSource.bid, "Zero", "0", "EUR", "0", 2);
        Candidate ok = candidate(CandidateSource.sheet, "Ok", "500", "EUR", "500", 4);

        Decision d = decider.decide("cheapest", false, List.of(zero, ok));

        assertThat(d.chosen()).isSameAs(ok);
        assertThat(reason(d, zero)).isEqualTo("no usable price");
        assertThat(decider.decide("cheapest", false, List.of()).hasQuote()).isFalse();
    }

    private Candidate candidate(CandidateSource source, String airline, String total, String currency, String totalEur,
                                Integer transit) {
        long id = ids.incrementAndGet();
        return new Candidate(id, source, airline.substring(0, 1), airline, new BigDecimal(total), currency,
                new BigDecimal(totalEur), transit, source == CandidateSource.portal ? "R-" + id : null, null, null);
    }

    private static String reason(Decision d, Candidate c) {
        return d.evaluated().stream().filter(e -> e.candidate() == c).findFirst().orElseThrow().rejectReason();
    }
}
