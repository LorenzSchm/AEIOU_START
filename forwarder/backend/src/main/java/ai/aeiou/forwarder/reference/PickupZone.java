package ai.aeiou.forwarder.reference;

/** A two-digit German zip prefix, its area and the origin airport trucks deliver to (pickup_zones.csv). */
public record PickupZone(String zone, String area, String airport) {}
