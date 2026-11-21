package ai.aeiou.freight;

import java.math.BigDecimal;
import java.util.*;

/** Rank complete door-to-port or airport-to-airport options with confirmed prices. */
public final class QuotePlanner {
  public record Plan(
      Offer best,
      List<Offer> options,
      List<String> notes,
      String trucker,
      BigDecimal pickupEur,
      Double pickupDays) {}

  public static Plan plan(
      Shipment s,
      List<Offer> published,
      List<Offer> airBids,
      List<Offer> trucks,
      Set<String> missingTruckTransit) {
    var offers = new ArrayList<>(published);
    if (!airBids.isEmpty()) offers.add(Offer.balancedBid(airBids, s.preference()));
    var truck =
        trucks.stream()
            .filter(
                t ->
                    !"fastest".equals(s.preference()) || !missingTruckTransit.contains(t.airline()))
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
            "Waiting for a confirmed pickup total"
                + ("fastest".equals(s.preference()) ? " and transit time" : "")
                + ".");
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
    return new Plan(best, ranked, notes, trucker, priced.pickupEur(), pickupDays);
  }
}
