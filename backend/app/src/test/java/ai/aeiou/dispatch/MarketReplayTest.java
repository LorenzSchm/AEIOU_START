package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.zip.ZipFile;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MarketReplayTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
  private static final Instant START = Instant.parse("2026-11-21T15:00:00Z");
  @TempDir Path temp;
  private Ledger ledger;
  private List<JsonNode> rounds;

  @BeforeEach void setup() throws Exception {
    ledger = new Ledger(temp.resolve("market.sqlite").toString(), mapper);
    rounds = new ArrayList<>();
    mapper.readTree(getClass().getResource("/six-wave-rfqs.json")).forEach(rounds::add);
    rounds.add(mapper.readTree(getClass().getResource("/round-nine-rfq.json")));
  }

  // Expose only names and a bracket on discovery. Each recorded price is revealed on its
  // own rate read. The fixtures lack portal metadata: use one stable version and no unrecorded buy-ups.
  private class Portal extends FiveU {
    final List<String> reads = new ArrayList<>();
    final Map<String, Offer> recorded = new LinkedHashMap<>();
    String version = "fixture-v1";
    int limitAfter = Integer.MAX_VALUE;
    Shipment shipment;
    Instant now;

    Portal() { super("http://localhost", "fixture", ledger); }

    void load(int index) throws Exception {
      reads.clear();
      recorded.clear();
      var row = rounds.get(index);
      for (var o : mapper.treeToValue(row.get("offers"), Offer[].class)) recorded.put(o.airline(), o);
      shipment = mapper.treeToValue(row.get("shipment"), Shipment.class);
      now = START.plusSeconds(360L * (index == 6 ? 8 : index));
    }

    @Override Map<String, Object> get(String uri, Object... args) {
      if (uri.endsWith("/ports"))
        return Map.of("lanes", List.of(Map.of("origin", shipment.origin(), "destination",
            shipment.destination(), "airlines", recorded.keySet().stream()
                .map(a -> Map.of("code", a, "name", a)).toList())));
      if (uri.contains("/brackets")) return Map.of("breaks_kg", List.of(0, 500));
      String name = (String) args[2];
      if (reads.size() == limitAfter) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Retry-After", "35");
        throw org.springframework.web.client.HttpClientErrorException.create(
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "limited", headers, null, null);
      }
      reads.add(name);
      var o = recorded.get(name);
      return Map.of("airline", name, "currency", "EUR", "total", o.totalEur(),
          "chargeable_weight_kg", o.billedKg(), "transit_days", (int) o.days(),
          "valid_until", now.plusSeconds(900).toString(), "rate_id", o.rateId(),
          "dataset_version", version);
    }
  }

  @Test void archivedFirstWavesReachMoreDocumentedAirlines() throws Exception {
    var file = Path.of("../../aeiou-data-2026-11-21.zip");
    var db = temp.resolve("archive.sqlite");
    var catalog = new DispatchApplication().catalog();
    try (var archive = new ZipFile(file.toFile())) {
      String state = "aeiou-data/backend/state/";
      try (var input = archive.getInputStream(archive.getEntry(state + "dispatch.sqlite3"))) {
        Files.copy(input, db);
      }
      var prior = new Ledger(db.toString(), mapper);
      var ports = mapper.readTree(archive.getInputStream(archive.getEntry(state + "prewave-live-rates.json")))
          .required("lanes").required("lanes");
      var cases = prior.jdbc().queryForList("""
          SELECT request_id, MIN(terms) AS shipment FROM tender WHERE leg='airline' GROUP BY request_id
          """);
      int before = 0, after = 0, slots = 0;
      for (var row : cases) {
        var shipment = prior.read(row.get("shipment"), Shipment.class);
        var declared = prior.airlineRoutesBefore(Ledger.id(row.get("request_id")));
        var documented = new HashSet<String>();
        for (var lane : ports) {
          if (shipment.origin().equals(lane.path("origin").asText())
              && shipment.destination().equals(lane.path("destination").asText()))
            lane.required("airlines").forEach(c -> documented.add(c.required("name").asText()));
        }
        catalog.price(shipment, LocalDate.of(2026, 11, 21)).forEach(o -> documented.add(o.airline()));
        var baseline = catalog.invitations(shipment, declared);
        var revised = catalog.invitations(shipment, declared, documented);
        assertEquals(baseline.stream().filter(Catalog.Carrier::truck).toList(),
            revised.stream().filter(Catalog.Carrier::truck).toList());
        assertEquals(baseline.size(), revised.size());
        slots += (int) revised.stream().filter(c -> !c.truck()).count();
        before += (int) baseline.stream().filter(c -> documented.contains(c.name())).count();
        after += (int) revised.stream().filter(c -> documented.contains(c.name())).count();
      }
      assertEquals(25, cases.size());
      assertEquals(83, slots);
      assertEquals(60, before);
      assertEquals(69, after);
    }
  }

  @Test void chronologicalPortalReplay() throws Exception {
    int reads = 0, available = 0, firstTwo = 0, baselineFirstTwo = 0;
    for (int i = 0; i < rounds.size(); i++) {
      // Rebuild the adapter between every round; learned observations must survive restart.
      var portal = new Portal();
      portal.load(i);
      var result = portal.rates(portal.shipment, portal.now);
      var order = Offer.order(portal.shipment.preference());
      var winner = Collections.min(portal.recorded.values(), order);
      assertTrue(result.complete());
      assertEquals(winner.rateId(), Collections.min(result.offers(), order).rateId());
      if (portal.reads.stream().limit(2).anyMatch(winner.airline()::equals)) firstTwo++;
      if (portal.recorded.keySet().stream().limit(2).anyMatch(winner.airline()::equals)) baselineFirstTwo++;
      reads += portal.reads.size();
      available += portal.recorded.size();
      if (i == 3) assertEquals(List.of("Xenon Cargo Airlines", "Aurelia Air Cargo"), portal.reads.subList(0, 2));
    }
    assertEquals(available, reads);
    assertEquals(baselineFirstTwo + 1, firstTwo);
    System.out.printf("Portal replay: %d rounds, %d/%d live reads, best in first two %d vs %d.%n",
        rounds.size(), reads, available, firstTwo, baselineFirstTwo);
  }

  @Test void changedWinnerReopensTheLaneBeforeTheQuote() throws Exception {
    var portal = new Portal();
    portal.load(0);
    portal.rates(portal.shipment, portal.now);
    portal.load(3);
    var old = portal.recorded.values().stream()
        .max(Offer.order("cheapest")).orElseThrow();
    portal.recorded.put(old.airline(), new Offer(old.airline(), old.days(), new BigDecimal("1.00"),
        old.billedKg(), old.source(), "changed-rate", old.validUntil()));
    var fresh = portal.rates(portal.shipment, portal.now);
    assertTrue(fresh.complete());
    assertEquals(4, portal.reads.size());
    assertTrue(portal.reads.indexOf(old.airline()) >= 2);
    assertEquals("changed-rate", Collections.min(fresh.offers(), Offer.order("cheapest")).rateId());
  }

  @Test void aVersionChangeForcesFreshReadsEvenWithIdenticalPrices() throws Exception {
    var portal = new Portal();
    portal.load(0);
    portal.rates(portal.shipment, portal.now);
    portal.load(3);
    portal.version = "fixture-v2";
    assertTrue(portal.rates(portal.shipment, portal.now).complete());
    assertEquals(4, portal.reads.size());
  }

  @Test void staleSamplesCannotShortenTheSearch() throws Exception {
    var portal = new Portal();
    portal.load(0);
    portal.rates(portal.shipment, portal.now);
    portal.load(3);
    portal.now = START.plusSeconds(3601);
    portal.rates(portal.shipment, portal.now);
    assertEquals(List.copyOf(portal.recorded.keySet()), portal.reads);
  }

  @Test void partialLookupResumesOnTheSameVersionAndDiscardsCacheOnChange() throws Exception {
    var portal = new Portal();
    portal.load(0);
    portal.limitAfter = 1;
    var confirmed = portal.rates(portal.shipment, portal.now);
    assertFalse(confirmed.complete());
    var partial = confirmed.offers();
    assertEquals(1, partial.size());
    var waiting = portal.rates(portal.shipment, portal.now.plusSeconds(34), partial, confirmed.version());
    assertTrue(waiting.offers().isEmpty());
    assertEquals(confirmed.version(), waiting.version());
    portal = new Portal();
    portal.load(0);
    var rest = portal.rates(portal.shipment, portal.now.plusSeconds(35), partial, confirmed.version());
    assertEquals(3, rest.offers().size());
    assertFalse(portal.reads.contains(partial.getFirst().airline()));
    portal.version = "new-dataset";
    var invalid = portal.rates(portal.shipment, portal.now.plusSeconds(70), partial, confirmed.version());
    assertTrue(invalid.reset());
    assertFalse(invalid.complete());
    assertTrue(invalid.offers().isEmpty());
    assertEquals(4, portal.rates(portal.shipment, portal.now.plusSeconds(71)).offers().size());
  }
}
