package ai.aeiou.forwarder.tariff;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Plausibility checks before a received tariff goes live (ARCHITECTURE.md §4.3). A tariff that fails any check
 * is stored as pending_review for a human; it is never activated silently.
 */
public final class TariffSanityCheck {

    static final BigDecimal MAX_RELATIVE_DELTA = new BigDecimal("0.50");
    static final double MIN_LANE_SHARE = 0.5;
    private static final BigDecimal MAX_RATE = new BigDecimal("50");
    private static final BigDecimal MAX_SURCHARGE = new BigDecimal("10");
    private static final BigDecimal MAX_MINIMUM = new BigDecimal("5000");
    private static final int MAX_LISTED = 8;

    private TariffSanityCheck() {}

    public record Result(List<String> problems, Map<String, Object> diff) {
        public boolean passed() {
            return problems.isEmpty();
        }
    }

    /**
     * @param current the airline's version in force today and its lanes, or null for a first tariff
     */
    public static Result check(ParsedTariff candidate, StoredTariff current, List<ParsedLane> currentLanes, LocalDate today) {
        List<String> problems = new ArrayList<>(structural(candidate));
        if (candidate.validTo() != null && candidate.validTo().isBefore(today)) {
            problems.add("Tariff already expired on " + candidate.validTo());
        }
        if (candidate.validFrom() != null && candidate.validTo() != null && candidate.validTo().isBefore(candidate.validFrom())) {
            problems.add("Valid to " + candidate.validTo() + " is before valid from " + candidate.validFrom());
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("lanes", candidate.lanes().size());
        if (current != null) {
            compare(candidate, current, currentLanes, problems, diff);
        }
        return new Result(List.copyOf(problems), diff);
    }

    /** Checks that need no previous version: every number present, positive and in a sane range, breaks ascending. */
    public static List<String> structural(ParsedTariff t) {
        List<String> problems = new ArrayList<>();
        if (t.currency() == null || !t.currency().matches("[A-Z]{3}")) problems.add("Currency missing or invalid: " + t.currency());
        if (t.lanes().isEmpty()) problems.add("Tariff has no lanes");
        for (ParsedLane l : t.lanes()) {
            String k = l.key();
            if (l.origin() == null || !l.origin().matches("[A-Z]{3}") || l.destination() == null || !l.destination().matches("[A-Z]{3}")) {
                problems.add("Lane " + k + " is not an IATA pair");
            }
            if (l.minimum() == null || l.minimum().signum() < 0 || l.minimum().compareTo(MAX_MINIMUM) > 0) {
                problems.add("Lane " + k + " minimum out of range: " + l.minimum());
            }
            if (l.breaks().isEmpty()) problems.add("Lane " + k + " has no weight breaks");
            for (int i = 0; i < l.breaks().size(); i++) {
                WeightBreak b = l.breaks().get(i);
                if (b.rate() == null || b.rate().signum() <= 0 || b.rate().compareTo(MAX_RATE) > 0) {
                    problems.add("Lane " + k + " rate from " + b.fromKg() + " kg out of range: " + b.rate());
                }
                if (i > 0 && b.fromKg() <= l.breaks().get(i - 1).fromKg()) problems.add("Lane " + k + " breaks not ascending");
            }
            if (!inRange(l.fuelPerKg()) || !inRange(l.securityPerKg())) {
                problems.add("Lane " + k + " surcharges out of range: fuel " + l.fuelPerKg() + ", security " + l.securityPerKg());
            }
        }
        return problems.size() > MAX_LISTED ? summarize(problems) : problems;
    }

    private static void compare(ParsedTariff candidate, StoredTariff current, List<ParsedLane> currentLanes,
                                List<String> problems, Map<String, Object> diff) {
        diff.put("previousTariffId", current.id());
        diff.put("previousLanes", currentLanes.size());
        if (!current.currency().equalsIgnoreCase(String.valueOf(candidate.currency()))) {
            problems.add("Currency changed from " + current.currency() + " to " + candidate.currency());
        }
        if (candidate.lanes().size() < currentLanes.size() * MIN_LANE_SHARE) {
            problems.add("Lane count dropped from " + currentLanes.size() + " to " + candidate.lanes().size());
        }
        Map<String, ParsedLane> old = currentLanes.stream().collect(Collectors.toMap(ParsedLane::key, Function.identity(), (a, b) -> a));
        Map<String, ParsedLane> now = candidate.lanes().stream().collect(Collectors.toMap(ParsedLane::key, Function.identity(), (a, b) -> a));
        diff.put("added", now.keySet().stream().filter(k -> !old.containsKey(k)).sorted().toList());
        diff.put("removed", old.keySet().stream().filter(k -> !now.containsKey(k)).sorted().toList());

        List<String> jumps = new ArrayList<>();
        BigDecimal maxDelta = BigDecimal.ZERO;
        for (ParsedLane lane : candidate.lanes()) {
            ParsedLane before = old.get(lane.key());
            if (before == null) continue;
            Map<Integer, BigDecimal> oldRates = before.breaks().stream()
                    .collect(Collectors.toMap(WeightBreak::fromKg, WeightBreak::rate, (a, b) -> a));
            List<BigDecimal[]> pairs = new ArrayList<>();
            pairs.add(new BigDecimal[] {before.minimum(), lane.minimum()});
            for (WeightBreak b : lane.breaks()) {
                if (oldRates.containsKey(b.fromKg())) pairs.add(new BigDecimal[] {oldRates.get(b.fromKg()), b.rate()});
            }
            for (BigDecimal[] p : pairs) {
                BigDecimal delta = relativeDelta(p[0], p[1]);
                if (delta.compareTo(maxDelta) > 0) maxDelta = delta;
                if (delta.compareTo(MAX_RELATIVE_DELTA) > 0) {
                    jumps.add(lane.key() + " " + p[0] + " -> " + p[1]);
                }
            }
        }
        diff.put("maxRelativeDelta", maxDelta.round(new MathContext(3)));
        if (!jumps.isEmpty()) {
            problems.add("Rates moved more than 50% on " + jumps.size() + " values, e.g. "
                    + String.join(", ", jumps.subList(0, Math.min(3, jumps.size()))));
        }
    }

    private static BigDecimal relativeDelta(BigDecimal before, BigDecimal after) {
        if (before == null || after == null) return BigDecimal.ZERO;
        if (before.signum() == 0) return after.signum() == 0 ? BigDecimal.ZERO : BigDecimal.ONE;
        return after.subtract(before).abs().divide(before.abs(), MathContext.DECIMAL64);
    }

    private static boolean inRange(BigDecimal v) {
        return v != null && v.signum() >= 0 && v.compareTo(MAX_SURCHARGE) <= 0;
    }

    private static List<String> summarize(List<String> problems) {
        List<String> head = new ArrayList<>(problems.subList(0, MAX_LISTED));
        head.add("... and " + (problems.size() - MAX_LISTED) + " more problems");
        return head;
    }
}
