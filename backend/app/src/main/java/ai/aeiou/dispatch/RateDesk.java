package ai.aeiou.dispatch;

import ai.aeiou.freight.Catalog;
import ai.aeiou.freight.MailParser;
import ai.aeiou.freight.Offer;
import ai.aeiou.freight.Shipment;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

final class RateDesk {
  static final String ADDRESS = "ratedesk@hackloop.mail";
  private static final Pattern LANE = Pattern.compile(
      "(?<![A-Z])([A-Z]{3})\\h*(?:to\\h+|[-–—→/]\\h*)([A-Z]{3})(?![A-Z])");

  static Map<String, Object> request(long id, Shipment shipment) {
    return Map.of(
        "to", ADDRESS,
        "subject", "Portal rate request AEIOU-" + id + " | " + shipment.origin() + " to " + shipment.destination(),
        "body", """
            Hello,

            Could you quote the %s air freight option from %s to %s for %s kg gross and %s cbm?
            Please include the airline, one all-in air freight total in EUR, the transit time and rate validity.

            Thanks,
            Team AEIOU
            """.formatted(shipment.preference(), shipment.origin(), shipment.destination(),
                Shipment.displayKg(shipment.weightKg()), Shipment.displayCbm(shipment.volumeCbm())));
  }

  static Optional<Offer> offer(String body, Shipment shipment, Catalog catalog, Instant now) {
    String text = MailParser.authoredText(body);
    var routes = LANE.matcher(text).results().toList();
    if (routes.stream().anyMatch(route -> !shipment.origin().equals(route.group(1))
        || !shipment.destination().equals(route.group(2)))) return Optional.empty();
    String lower = text.toLowerCase(Locale.ROOT);
    var carriers = catalog.carriers.stream().filter(carrier -> !carrier.truck() && carrier.serves(shipment.destination()))
        .filter(carrier -> lower.contains(carrier.name().toLowerCase(Locale.ROOT))).toList();
    if (carriers.size() > 1) return Optional.empty();
    // The desk reply must unambiguously identify the airline and the terms for this shipment.
    if (carriers.isEmpty() || QuoteChecks.conditionalCarrier(text)) return Optional.empty();
    Instant expiry;
    try {
      expiry = MailParser.bidValidUntil(text);
    } catch (java.time.format.DateTimeParseException ex) {
      return Optional.empty();
    }
    if (expiry != null && !expiry.isAfter(now.plusSeconds(4))) return Optional.empty();
    return MailParser.bid(text, false).filter(bid -> bid.days() != null)
        .map(bid -> new Offer(carriers.getFirst().name(), bid.days(), bid.euros(), shipment.chargeableKg(),
            "rate desk", null, expiry));
  }

  private RateDesk() {}
}
