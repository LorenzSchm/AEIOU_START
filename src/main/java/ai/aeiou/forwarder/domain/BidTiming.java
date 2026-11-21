package ai.aeiou.forwarder.domain;

import java.time.*;
import java.util.*;

/** Empirical conditional arrival estimate. Future and right-censored intervals have no label. */
public final class BidTiming {
  public record Observation(
      String carrier,
      Instant requested,
      Instant observedUntil,
      Instant response,
      boolean improves) {}

  public record Estimate(int samples, int carrierSamples, double probability, boolean waitForBid) {}

  private BidTiming() {}

  public static Estimate estimate(
      List<Observation> history,
      String carrier,
      Instant cutoff,
      int elapsed,
      int interval,
      int remaining) {
    int total = 0, hits = 0, local = 0, localHits = 0;
    for (var o : history) {
      if (!o.requested.isBefore(cutoff)) continue;
      var from = o.requested.plusSeconds(elapsed);
      var to = from.plusSeconds(interval);
      Instant visible = o.observedUntil.isBefore(cutoff) ? o.observedUntil : cutoff;
      boolean responded = o.response != null && !o.response.isAfter(visible);
      if (responded && !o.response.isAfter(from)) continue;
      if (!responded && visible.isBefore(to)) continue;
      boolean success = responded && !o.response.isAfter(to) && o.improves;
      total++;
      if (success) hits++;
      if (carrier.equals(o.carrier)) {
        local++;
        if (success) localHits++;
      }
    }
    double pooled = total == 0 ? 0 : (double) hits / total;
    double shrunk = (localHits + 5 * pooled) / (local + 5);
    return new Estimate(
        total, local, shrunk, total >= 10 && remaining > interval + 20 && shrunk >= 0.35);
  }
}
