package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/**
 * Prices a citywide collection from the firm truck bids the ledger already holds for that city,
 * for a customer who named only the town and truckers who will not quote without a postcode.
 */
final class PickupEstimate {
  /** Bids older than this no longer describe today's road market. */
  static final Duration HISTORY = Duration.ofHours(24);
  /** Only weights within this factor of a carrier's nearest bid are estimated from it. */
  private static final double WEIGHT_RANGE = 2;
  /**
   * Cities whose own postcodes span more two-digit zones than the zone file names after them,
   * e.g. Köln 50667–51149 or Hamburg 20095–22769.
   */
  private static final Map<String, Set<String>> CITY_ZONES = Map.of(
      "cologne", Set.of("50", "51"),
      "hamburg", Set.of("20", "21", "22"),
      "frankfurt am main", Set.of("60", "65"),
      "munich", Set.of("80", "81"));

  record Bid(String carrier, BigDecimal chargeableKg, BigDecimal eur, double days, Instant received) {}

  record Estimate(String carrier, BigDecimal eur, double days, String basis) {
    Offer offer(BigDecimal kg) {
      return new Offer(carrier, days, eur, kg, "pickup estimate", null, null);
    }
  }

  private PickupEstimate() {}

  /** The pickup zones inside {@code area}: the confirmed zone and every zone named after that city. */
  static Set<String> zones(Catalog catalog, String area, String zone) {
    var zones = new TreeSet<String>();
    if (zone != null) zones.add(zone);
    if (area == null) return zones;
    String city = area.strip().toLowerCase(Locale.ROOT);
    catalog.pickupAreas.forEach((code, name) -> {
      String lower = name.toLowerCase(Locale.ROOT);
      if (lower.equals(city) || lower.startsWith(city + " ")) zones.add(code);
    });
    zones.addAll(CITY_ZONES.getOrDefault(city, Set.of()));
    return zones;
  }

  /** Firm truck bids with an EUR total and a road transit for any of {@code zones} since {@code since}. */
  static List<Bid> history(Ledger ledger, Catalog catalog, Set<String> zones, long requestId, Instant since,
      Instant until) {
    var bids = new ArrayList<Bid>();
    for (var row : ledger.jdbc().queryForList("""
        SELECT t.email, t.euros, t.days, t.terms, r.received, COALESCE(r.carrier_offer_text, r.body) AS body
        FROM tender t JOIN mail r ON r.id=t.reply_id AND r.sender=t.email
        WHERE t.leg='trucker' AND t.euros IS NOT NULL AND t.days IS NOT NULL AND t.request_id<>?
          AND julianday(r.received)>=julianday(?) AND julianday(r.received)<=julianday(?)
        """, requestId, since.toString(), until.toString())) {
      var terms = ledger.read(row.get("terms"), Shipment.class);
      if (terms == null || !zones.contains(terms.pickupZone()) || terms.weightKg() == null
          || terms.volumeCbm() == null) continue;
      String body = Ledger.str(row, "body");
      if (QuoteChecks.conditionalCarrier(body) || QuoteChecks.pickupExtras(body)) continue;
      var carrier = catalog.contact(Ledger.str(row, "email")).filter(Catalog.Carrier::truck).orElse(null);
      double days = ((Number) row.get("days")).doubleValue();
      BigDecimal eur;
      try {
        eur = new BigDecimal(row.get("euros").toString());
      } catch (NumberFormatException ex) {
        continue;
      }
      if (carrier == null || days <= 0 || eur.signum() <= 0) continue;
      bids.add(new Bid(carrier.name(), terms.chargeableKg(), eur, days,
          Ledger.instant(row.get("received"))));
    }
    return bids;
  }

  /**
   * Each carrier's price at {@code kg}: linear between its nearest lighter and heavier bids, its nearest
   * heavier bid unchanged below its range, and the per-kg rate of its heaviest bid above it. Neither
   * extrapolation undercuts what the carrier has charged. The transit is the slower of the bids used.
   */
  static List<Estimate> perCarrier(List<Bid> bids, BigDecimal kg) {
    var byCarrier = new TreeMap<String, TreeMap<BigDecimal, Bid>>();
    for (var bid : bids) {
      var points = byCarrier.computeIfAbsent(bid.carrier(), name -> new TreeMap<>());
      points.merge(bid.chargeableKg(), bid,
          (old, current) -> current.received().isAfter(old.received()) ? current : old);
    }
    var estimates = new ArrayList<Estimate>();
    double target = kg.doubleValue();
    byCarrier.forEach((carrier, points) -> {
      var lower = points.floorEntry(kg);
      var upper = points.ceilingEntry(kg);
      BigDecimal eur;
      double days;
      String basis;
      if (lower != null && upper != null && lower.getKey().compareTo(upper.getKey()) != 0) {
        var low = lower.getValue();
        var high = upper.getValue();
        double share = (target - lower.getKey().doubleValue())
            / (upper.getKey().doubleValue() - lower.getKey().doubleValue());
        eur = BigDecimal.valueOf(low.eur().doubleValue() + share * (high.eur().doubleValue() - low.eur().doubleValue()));
        days = Math.max(low.days(), high.days());
        basis = "EUR %s at %s kg and EUR %s at %s kg".formatted(low.eur().toPlainString(),
            Shipment.displayKg(lower.getKey()), high.eur().toPlainString(), Shipment.displayKg(upper.getKey()));
      } else {
        var nearest = lower != null ? lower : upper;
        double ratio = target / nearest.getKey().doubleValue();
        if (ratio > WEIGHT_RANGE || ratio < 1 / WEIGHT_RANGE) return;
        var bid = nearest.getValue();
        eur = ratio > 1 ? BigDecimal.valueOf(bid.eur().doubleValue() * ratio) : bid.eur();
        days = bid.days();
        basis = "EUR %s at %s kg".formatted(bid.eur().toPlainString(), Shipment.displayKg(nearest.getKey()));
      }
      estimates.add(new Estimate(carrier, eur.setScale(0, RoundingMode.CEILING).setScale(2), days, basis));
    });
    return estimates;
  }

  /**
   * The truck the deployed rule would pick among the carriers' estimates: the most balanced price and
   * transit, falling back to the customer's preference.
   */
  static Optional<Estimate> choose(List<Estimate> estimates, Shipment shipment) {
    if (estimates.isEmpty()) return Optional.empty();
    var kg = shipment.chargeableKg();
    var offers = estimates.stream().map(estimate -> estimate.offer(kg)).toList();
    var truck = QuotePlanner.balancedTruck(offers, Set.of());
    if (truck == null) truck = offers.stream().min(Offer.order(shipment.preference())).orElseThrow();
    String carrier = truck.airline();
    return estimates.stream().filter(estimate -> estimate.carrier().equals(carrier)).findFirst();
  }

  static Optional<Estimate> estimate(Ledger ledger, Catalog catalog, Shipment shipment, String area,
      long requestId, Instant now, Predicate<String> excluded) {
    if (shipment.pickupZone() == null || area == null) return Optional.empty();
    var zones = zones(catalog, area, shipment.pickupZone());
    var bids = history(ledger, catalog, zones, requestId, now.minus(HISTORY), now).stream()
        .filter(bid -> !excluded.test(bid.carrier())).toList();
    return choose(perCarrier(bids, shipment.chargeableKg()), shipment);
  }
}
