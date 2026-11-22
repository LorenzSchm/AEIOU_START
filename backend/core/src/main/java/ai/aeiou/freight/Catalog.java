package ai.aeiou.freight;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

public final class Catalog {
  public static final int MAX_INVITATIONS = 5;
  private static final YearMonth KIT_TARIFF_MONTH = YearMonth.of(2026, 11);

  public record Carrier(String name, String kind, String email, String serves) {
    public boolean serves(String location) {
      return location != null && Arrays.asList(serves.split(" ")).contains(location);
    }

    public boolean truck() {
      return kind.equals("trucker");
    }
  }

  public record Tariff(
      String airline,
      String origin,
      String destination,
      int days,
      BigDecimal minimum,
      BigDecimal fuel,
      BigDecimal security,
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

    /** A total that applies a higher break's rate to a weight below that break. */
    boolean misappliedBreak(BigDecimal weight, BigDecimal total) {
      int bracket = breaks.floorKey(weight.intValue());
      if (total.compareTo(price(weight).totalEur()) >= 0) return false;
      return breaks.tailMap(bracket, false).values().stream()
          .anyMatch(rate -> at(weight, rate).totalEur().subtract(total).abs().compareTo(new BigDecimal("0.02")) <= 0);
    }

    private Offer at(BigDecimal kg, BigDecimal rate) {
      return new Offer(
          airline,
          days,
          kg.multiply(rate).max(minimum).add(kg.multiply(fuel.add(security))),
          kg,
          "sheet",
          null,
          null);
    }
  }

  private final List<Tariff> kitTariffs;
  private final Path tariffDirectory;
  public final List<Carrier> carriers;
  public final Map<String, String> zones = new LinkedHashMap<>();
  public final Map<String, String> pickupAreas = new LinkedHashMap<>();
  public final Set<String> destinations = new HashSet<>();

  public Catalog(Reader tariffFile, Reader carrierFile, Reader zoneFile) {
    this(tariffFile, carrierFile, zoneFile, null);
  }

  public Catalog(Reader tariffFile, Reader carrierFile, Reader zoneFile, Path tariffDirectory) {
    this.tariffDirectory = tariffDirectory;
    carriers = rows(carrierFile).stream().map(r -> new Carrier(r[0], r[1], r[2], r[3])).toList();
    carriers.stream()
        .filter(c -> !c.truck())
        .forEach(c -> destinations.addAll(List.of(c.serves.split(" "))));
    for (var r : rows(zoneFile)) {
      zones.put(r[0], r[2]);
      pickupAreas.put(r[0], r[1]);
    }
    kitTariffs = readTariffs(tariffFile);
  }

  private static List<Tariff> readTariffs(Reader tariffFile) {
    var tariffs = new ArrayList<Tariff>();
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
              new BigDecimal(r[5]),
              new BigDecimal(r[6]),
              prices));
    }
    return List.copyOf(tariffs);
  }

  private List<Tariff> tariffsFor(YearMonth month) {
    if (tariffDirectory != null) {
      Path file = tariffDirectory.resolve("tariffs-" + month + ".csv");
      try {
        return readTariffs(Files.newBufferedReader(file));
      } catch (NoSuchFileException ex) {
        // The original kit tariff is available as a fallback for November 2026.
      } catch (IOException ex) {
        throw new UncheckedIOException("Could not open monthly tariffs " + file, ex);
      }
    }
    return KIT_TARIFF_MONTH.equals(month) ? kitTariffs : List.of();
  }

  private static List<String[]> rows(Reader source) {
    try (var reader = new BufferedReader(source)) {
      return reader.lines().skip(1).filter(s -> !s.isBlank()).map(s -> s.split(",", -1)).toList();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  public int size() {
    return kitTariffs.size();
  }

  public List<Offer> price(Shipment s, LocalDate date) {
    return price(s, date, List.of());
  }

  public List<Offer> price(Shipment s, LocalDate date, List<Tariff> approved) {
    return tariffs(s, date, approved).stream()
        .map(t -> t.price(s.chargeableKg()))
        .toList();
  }

  /** Whether an airline's total bills the chargeable weight at a break the shipment does not reach. */
  public boolean misappliedBreak(Shipment s, LocalDate date, List<Tariff> approved, String airline, BigDecimal total) {
    return tariffs(s, date, approved).stream()
        .anyMatch(t -> t.airline.equals(airline) && t.misappliedBreak(s.chargeableKg(), total));
  }

  private Collection<Tariff> tariffs(Shipment s, LocalDate date, List<Tariff> approved) {
    var byCarrier = new LinkedHashMap<String, Tariff>();
    tariffsFor(YearMonth.from(date)).stream()
        .filter(t -> t.origin.equals(s.origin()) && t.destination.equals(s.destination()))
        .forEach(t -> byCarrier.put(t.airline, t));
    approved.stream()
        .filter(t -> t.origin.equals(s.origin()) && t.destination.equals(s.destination()))
        .forEach(t -> byCarrier.put(t.airline, t));
    return byCarrier.values();
  }

  public Optional<Carrier> contact(String address) {
    return carriers.stream().filter(c -> c.email.equalsIgnoreCase(address)).findFirst();
  }

  public List<Carrier> invitations(Shipment s) {
    return invitations(s, Map.of());
  }

  public List<Carrier> invitations(Shipment s, Map<String, Set<String>> airlineRoutes) {
    return invitations(s, airlineRoutes, carrier -> true);
  }

  public List<Carrier> invitations(Shipment s, Map<String, Set<String>> airlineRoutes,
      java.util.function.Predicate<Carrier> eligible) {
    return invitations(s, airlineRoutes, Map.of(), eligible);
  }

  public List<Carrier> invitations(Shipment s, Map<String, Set<String>> airlineRoutes,
      Map<String, Double> truckTransit, java.util.function.Predicate<Carrier> eligible) {
    return invitations(s, airlineRoutes, truckTransit, Set.of(), eligible);
  }

  public List<Carrier> invitations(Shipment s, Map<String, Set<String>> airlineRoutes,
      Map<String, Double> truckTransit, Set<String> portalAirlines, java.util.function.Predicate<Carrier> eligible) {
    String route = s.origin() + "-" + s.destination();
    var airlines = carriers.stream()
        .filter(c -> !c.truck())
        .filter(eligible)
        .filter(c -> airlineRoutes.containsKey(c.email())
            ? airlineRoutes.get(c.email()).contains(route) : c.serves(s.destination()))
        .sorted(Comparator.comparing(c -> !airlineRoutes.containsKey(c.email())))
        .toList();
    var trucks = carriers.stream().filter(c -> c.truck() && c.serves(s.pickupZone()))
        .filter(eligible).toList();
    boolean cheapestPickup = s.pickupZone() != null && "cheapest".equals(s.preference());
    if (cheapestPickup && "60".equals(s.pickupZone())) {
      // Use the pre-wave comparison for invitation order. Every quote still needs a fresh bid.
      var priority = Map.of("bodenseefracht@hackloop.mail", 0, "westfalenroute@hackloop.mail", 1,
          "domblick-kurierdienst@hackloop.mail", 2);
      trucks = trucks.stream()
          .sorted(Comparator.comparingInt(c -> priority.getOrDefault(c.email(), 3)))
          .toList();
    } else if ("fastest".equals(s.preference()) && !truckTransit.isEmpty()) {
      // Observed transit orders invitations. Only fresh bids can supply the quote terms.
      trucks = trucks.stream()
          .sorted(Comparator.comparingDouble(c -> truckTransit.getOrDefault(c.email(), Double.POSITIVE_INFINITY)))
          .toList();
    }
    int airlineLimit = s.pickupZone() == null ? MAX_INVITATIONS : 3;
    if (cheapestPickup) {
      // Keep a second airline bid when the directory only confirms the destination.
      boolean exactAirLane = airlines.stream().anyMatch(carrier -> airlineRoutes.containsKey(carrier.email()));
      int minimumAirlines = exactAirLane ? 1 : 2;
      airlineLimit = Math.min(airlineLimit, Math.max(minimumAirlines, MAX_INVITATIONS - trucks.size()));
    }
    var selected = new ArrayList<>(airlines.stream().limit(airlineLimit).toList());
    int extraTrucks = trucks.size() - (MAX_INVITATIONS - selected.size());
    if (extraTrucks > 0 && !portalAirlines.isEmpty()) {
      var covered = selected.stream().filter(c -> portalAirlines.contains(c.name().toLowerCase(Locale.ROOT)))
          .limit(extraTrucks).toList();
      selected.removeAll(covered);
    }
    selected.addAll(trucks.stream().limit(MAX_INVITATIONS - selected.size()).toList());
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
