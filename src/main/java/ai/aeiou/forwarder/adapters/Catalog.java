package ai.aeiou.forwarder.adapters;

import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.domain.Pricing;
import ai.aeiou.forwarder.infrastructure.Ledger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Catalog {
  private final Path kit;
  private final Ledger store;

  public record Contact(String name, String kind, String email, Set<String> serves) {}

  private List<Contact> directory = List.of();
  private final Map<String, String> zones = new HashMap<>();

  public Catalog(Ledger store, @Value("${forwarder.kit}") String kit) {
    this.store = store;
    this.kit = Path.of(kit);
  }

  @Transactional
  public void load(Instant at) throws IOException {
    store.lock();
    directory =
        Files.readAllLines(kit.resolve("carrier-list.csv")).stream()
            .skip(1)
            .map(l -> l.split(",", 4))
            .map(a -> new Contact(a[0], a[1], a[2], Set.of(a[3].split(" "))))
            .toList();
    for (String line :
        Files.readAllLines(kit.resolve("pickup_zones.csv")).stream().skip(1).toList()) {
      var fields = line.split(",");
      zones.put(fields[0], fields[2]);
    }
    try (var files = Files.list(kit)) {
      for (Path path :
          files
              .sorted()
              .filter(
                  p ->
                      p.getFileName().toString().contains("-tariff-")
                          && !p.toString().endsWith("-p2.jpg"))
              .toList()) ingest(path, at);
    }
  }

  public void ingest(Path path, Instant at) throws IOException {
    String digest = hash(Files.readAllBytes(path));
    String filename = path.getFileName().toString();
    if (filename.endsWith("-p1.jpg"))
      digest =
          hash(
              digest
                  + hash(
                      Files.readAllBytes(
                          path.resolveSibling(filename.replace("-p1.jpg", "-p2.jpg")))));
    if (store.db.queryForObject("select count(*) from tariff where digest=?", Integer.class, digest)
        > 0) return;
    var result = new TariffReader().read(path, at);
    register(result, at);
  }

  public boolean register(TariffReader.Result result, Instant at) {
    var t = result.tariff();
    if (store.db.queryForObject(
            "select count(*) from tariff where digest=?", Integer.class, str(t, "hash"))
        > 0) return false;
    for (var old : store.read("select data from tariff")) {
      if (str(old, "carrier").equals(str(t, "carrier"))
          && str(old, "validFrom").equals(str(t, "validFrom"))
          && str(old, "validTo").equals(str(t, "validTo"))
          && str(t, "state").equals("ACTIVE")
          && !str(old, "state").equals("SUPERSEDED")) {
        ((ObjectNode) old).put("state", "SUPERSEDED");
        store.db.update("update tariff set data=? where id=?", old.toString(), str(old, "id"));
      }
    }
    store.db.update(
        "insert into tariff values(?,?,?,?)",
        str(t, "id"),
        str(t, "hash"),
        t.toString(),
        result.lanes().toString());
    store.event(
        null,
        "TARIFF",
        "Imported " + str(t, "carrier") + " (" + t.get("laneCount") + " lanes)",
        at,
        null);
    return true;
  }

  public List<Contact> contacts(String kind, String coverage) {
    return contacts(kind, coverage, Set.of());
  }

  public List<Contact> contacts(String kind, String coverage, Set<String> preferred) {
    return directory.stream()
        .filter(c -> c.kind.equals(kind) && c.serves.contains(coverage))
        .sorted(Comparator.comparing(c -> !preferred.contains(c.name())))
        .limit(5)
        .toList();
  }

  public Optional<Contact> byEmail(String email) {
    return directory.stream().filter(c -> c.email.equalsIgnoreCase(email)).findFirst();
  }

  public Optional<Contact> byName(String name) {
    return directory.stream().filter(c -> c.name.equalsIgnoreCase(name)).findFirst();
  }

  public String airport(String zone) {
    return zones.get(zone);
  }

  public List<JsonNode> offers(
      String origin, String dest, BigDecimal kg, BigDecimal volume, int revision, Instant at) {
    String date = at.atZone(ZoneOffset.UTC).toLocalDate().toString();
    return store
        .db
        .query(
            "select data,lanes from tariff",
            (rs, i) -> {
              var t = parse(rs.getString(1));
              List<JsonNode> out = new ArrayList<>();
              if (!str(t, "state").equals("SUPERSEDED")
                  && date.compareTo(str(t, "validFrom")) >= 0
                  && date.compareTo(str(t, "validTo")) <= 0)
                for (var lane : parse(rs.getString(2)))
                  if (str(lane, "origin").equals(origin) && str(lane, "destination").equals(dest))
                    out.add(Pricing.tariff(t, lane, kg, volume, revision));
              return out;
            })
        .stream()
        .flatMap(List::stream)
        .toList();
  }
}
