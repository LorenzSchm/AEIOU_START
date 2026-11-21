package ai.aeiou.freight;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

public final class Catalog {
  public record Carrier(String name, String kind, String email, String serves) {
    public boolean serves(String location) {
      return location != null && Arrays.asList(serves.split(" ")).contains(location);
    }

    public boolean truck() {
      return kind.equals("trucker");
    }
  }

  record Tariff(
      String airline,
      String origin,
      String destination,
      int days,
      BigDecimal minimum,
      BigDecimal surcharge,
      NavigableMap<Integer, BigDecimal> breaks) {
    Offer price(BigDecimal weight) {
      int bracket = breaks.floorKey(weight.intValue());
      var best = at(weight, breaks.get(bracket));
      for (var e : breaks.tailMap(bracket, false).entrySet()) {
        var candidate = at(BigDecimal.valueOf(e.getKey()), e.getValue());
        if (candidate.totalEur().compareTo(best.totalEur()) < 0) best = candidate;
      }
      return best;
    }

    private Offer at(BigDecimal kg, BigDecimal rate) {
      return new Offer(
          airline,
          days,
          kg.multiply(rate).max(minimum).add(kg.multiply(surcharge)),
          kg,
          "sheet",
          null,
          null);
    }
  }

  private final List<Tariff> tariffs = new ArrayList<>();
  public final List<Carrier> carriers;
  public final Map<String, String> zones = new LinkedHashMap<>();
  public final Map<String, String> pickupAreas = new LinkedHashMap<>();
  public final Set<String> destinations = new HashSet<>();

  public Catalog(Reader tariffFile, Reader carrierFile, Reader zoneFile) {
    carriers = rows(carrierFile).stream().map(r -> new Carrier(r[0], r[1], r[2], r[3])).toList();
    carriers.stream()
        .filter(c -> !c.truck())
        .forEach(c -> destinations.addAll(List.of(c.serves.split(" "))));
    for (var r : rows(zoneFile)) {
      zones.put(r[0], r[2]);
      pickupAreas.put(r[0], r[1]);
    }
    int[] breaks = {0, 45, 100, 250, 300, 500, 1000, 2000};
    for (var r : rows(tariffFile)) {
      var prices = new TreeMap<Integer, BigDecimal>();
      for (int i = 0; i < breaks.length; i++)
        if (!r[7 + i].isBlank()) prices.put(breaks[i], new BigDecimal(r[7 + i]));
      tariffs.add(
          new Tariff(
              r[0],
              r[1],
              r[2],
              Integer.parseInt(r[3]),
              new BigDecimal(r[4]),
              new BigDecimal(r[5]).add(new BigDecimal(r[6])),
              prices));
    }
  }

  private static List<String[]> rows(Reader source) {
    try (var reader = new BufferedReader(source)) {
      return reader.lines().skip(1).filter(s -> !s.isBlank()).map(s -> s.split(",", -1)).toList();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  public int size() {
    return tariffs.size();
  }

  public List<Offer> price(Shipment s, LocalDate date) {
    if (!YearMonth.from(date).equals(YearMonth.of(2026, 11))) return List.of();
    return tariffs.stream()
        .filter(t -> t.origin.equals(s.origin()) && t.destination.equals(s.destination()))
        .map(t -> t.price(s.chargeableKg()))
        .toList();
  }

  public Optional<Carrier> contact(String address) {
    return carriers.stream().filter(c -> c.email.equalsIgnoreCase(address)).findFirst();
  }

  public List<Carrier> invitations(Shipment s) {
    var airlines = carriers.stream().filter(c -> !c.truck() && c.serves(s.destination())).toList();
    var trucks = carriers.stream().filter(c -> c.truck() && c.serves(s.pickupZone())).toList();
    // In zone 60 these truckers returned the lowest initial bids in the pre-wave comparison.
    // This is an invitation preference only. Each shipment still needs a fresh confirmed rate.
    if ("60".equals(s.pickupZone()) && "cheapest".equals(s.preference())) {
      var priority = Map.of("bodenseefracht@hackloop.mail", 0, "westfalenroute@hackloop.mail", 1);
      trucks = trucks.stream()
          .sorted(Comparator.comparingInt(c -> priority.getOrDefault(c.email(), 2)))
          .toList();
    }
    var selected =
        new ArrayList<>(airlines.stream().limit(s.pickupZone() == null ? 5 : 3).toList());
    selected.addAll(trucks.stream().limit(5 - selected.size()).toList());
    return selected;
  }

  public String problem(Shipment s) {
    if (s == null || !zones.containsValue(s.origin()) || !destinations.contains(s.destination()))
      return "Confirm origin and destination airports.";
    if (s.weightKg() == null
        || s.weightKg().signum() <= 0
        || s.weightKg().compareTo(new BigDecimal("100000")) > 0)
      return "Confirm total gross weight in kg.";
    if (s.volumeCbm() == null
        || s.volumeCbm().signum() < 0
        || s.volumeCbm().compareTo(new BigDecimal("1000")) > 0)
      return "Confirm total shipment volume in cubic metres.";
    if (!"cheapest".equals(s.preference()) && !"fastest".equals(s.preference()))
      return "Confirm cheapest or fastest service.";
    if (s.pickupZone() != null && !s.origin().equals(zones.get(s.pickupZone())))
      return "Confirm the pickup zone and matching airport.";
    if (!Double.isFinite(s.pickupDays())
        || s.pickupDays() < 0
        || s.pickupDays() > 90
        || (s.pickupEur() != null && s.pickupEur().signum() < 0)
        || (s.pickupZone() == null && (s.pickupEur() != null || s.pickupDays() != 0)))
      return "Check the pickup cost, zone and transit.";
    return null;
  }
}
