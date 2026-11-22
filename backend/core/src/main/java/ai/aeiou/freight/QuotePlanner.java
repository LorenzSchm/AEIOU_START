package ai.aeiou.freight;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Rank complete door-to-port or airport-to-airport options with confirmed prices. */
public final class QuotePlanner {
  public record Plan(
      Offer best,
      List<Offer> options,
      List<String> notes,
      String trucker,
      BigDecimal pickupEur,
      Double pickupDays,
      Instant pickupValidUntil) {}

  public static Plan plan(
      Shipment s,
      List<Offer> published,
      List<Offer> airBids,
      List<Offer> trucks,
      Set<String> missingTruckTransit) {
    var offers = new ArrayList<>(published);
    offers.addAll(airBids);
    var truck = balancedTruck(trucks, missingTruckTransit);
    if (truck == null)
      truck =
          trucks.stream()
              .filter(
                  t ->
                      !"fastest".equals(s.preference())
                          || !missingTruckTransit.contains(t.airline()))
              .min(Offer.order(s.preference()))
              .orElse(null);
    var priced = s;
    String trucker = null;
    Double pickupDays = null;
    List<String> notes = new ArrayList<>();
    if (s.pickupZone() != null) {
      if (s.pickupEur() != null) {
        pickupDays = s.pickupDays();
      } else if (truck == null) {
        offers.clear();
        notes.add(
            "fastest".equals(s.preference()) && !missingTruckTransit.isEmpty()
                ? "Pickup prices received. Waiting for confirmed pickup-to-airport transit time."
                : "Waiting for a confirmed pickup total"
                    + ("fastest".equals(s.preference()) ? " and transit time" : "") + ".");
      } else {
        priced = s.withPickup(truck.totalEur(), truck.days());
        trucker = truck.airline();
        pickupDays = missingTruckTransit.contains(trucker) ? null : truck.days();
      }
    }
    final var shipment = priced;
    var pickupExpiry = trucker == null ? null : truck.validUntil();
    var ranked =
        offers.stream()
            .map(o -> o.addPickup(shipment, pickupExpiry))
            .sorted(Offer.order(s.preference()))
            .toList();
    var best = ranked.isEmpty() ? null : ranked.getFirst();
    if (best == null && notes.isEmpty())
      notes.add("Waiting for a confirmed air price and transit time.");
    return new Plan(best, ranked, notes, trucker, priced.pickupEur(), pickupDays, pickupExpiry);
  }

  /**
   * The brief: "Among carrier bids, the right one is the most balanced: price and speed weigh 50/50."
   * Each firm truck bid with a confirmed road transit scores 0.5 * price / lowest price + 0.5 *
   * transit / shortest transit, and the lowest score wins whatever the customer's air preference.
   * A bid without a confirmed transit never wins against one that has it. Returns null when no bid
   * has a confirmed transit, so the caller keeps its fallback for unknown timing.
   */
  public static Offer balancedTruck(List<Offer> trucks, Set<String> missingTruckTransit) {
    var timed =
        trucks.stream()
            .filter(t -> !missingTruckTransit.contains(t.airline()) && t.days() > 0)
            .toList();
    if (timed.isEmpty()) return null;
    return timed.stream().min(balance(timed)).orElseThrow();
  }

  /** Orders truck bids with a confirmed transit from the most to the least balanced. */
  public static Comparator<Offer> balance(List<Offer> timed) {
    double lowestPrice =
        timed.stream().mapToDouble(t -> t.totalEur().doubleValue()).min().orElse(0);
    double shortest = timed.stream().mapToDouble(Offer::days).filter(d -> d > 0).min().orElse(0);
    Comparator<Offer> score =
        Comparator.comparingDouble(
            t ->
                0.5 * (lowestPrice > 0 ? t.totalEur().doubleValue() / lowestPrice : 1)
                    + 0.5 * (shortest > 0 ? t.days() / shortest : 1));
    return score.thenComparing(Offer.order("cheapest"));
  }
}
