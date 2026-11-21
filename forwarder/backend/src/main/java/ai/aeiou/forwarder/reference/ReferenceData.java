package ai.aeiou.forwarder.reference;

import java.sql.Array;
import java.text.Normalizer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read-only lookups over carriers, pickup zones and airports. The tables are small, so they are held in an
 * immutable in-memory snapshot that {@link #refresh()} swaps atomically (after seeding or a code update).
 */
@Service
public class ReferenceData {

    private final JdbcClient jdbc;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

    public ReferenceData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void refresh() {
        List<Carrier> carriers = jdbc.sql("SELECT id, name, code, kind, email, serves FROM carrier ORDER BY name")
                .query(ReferenceData::mapCarrier).list();
        List<PickupZone> zones = jdbc.sql("SELECT zone, area, airport FROM pickup_zone ORDER BY zone")
                .query(PickupZone.class).list();
        List<Airport> airports = jdbc.sql("SELECT iata, name, city, country, lat, lon FROM airport ORDER BY iata")
                .query(Airport.class).list();
        snapshot.set(new Snapshot(carriers, zones, airports));
    }

    public List<Carrier> carriers() {
        return snapshot.get().carriers;
    }

    public List<Carrier> airlines() {
        return snapshot.get().carriers.stream().filter(Carrier::isAirline).toList();
    }

    /** Airlines whose carrier-list entry serves this destination IATA. */
    public List<Carrier> airlinesServing(String destinationIata) {
        return snapshot.get().carriers.stream()
                .filter(c -> c.isAirline() && c.serves(destinationIata))
                .toList();
    }

    /** Truckers serving the pickup zone of a German zip code (zone = first two digits). */
    public List<Carrier> truckersServingZip(String zip) {
        return zoneOf(zip).map(zone -> snapshot.get().carriers.stream()
                        .filter(c -> c.kind() == Carrier.Kind.trucker && c.serves(zone))
                        .toList())
                .orElse(List.of());
    }

    public Optional<PickupZone> pickupZone(String zip) {
        return zoneOf(zip).map(snapshot.get().zonesByCode::get);
    }

    /** The origin airport trucks from this zip deliver to, e.g. "60311" -> FRA. */
    public Optional<String> originAirportForZip(String zip) {
        return pickupZone(zip).map(PickupZone::airport);
    }

    public Optional<Carrier> carrierByEmail(String email) {
        if (email == null) return Optional.empty();
        return Optional.ofNullable(snapshot.get().carriersByEmail.get(bareAddress(email)));
    }

    /**
     * Finds an airline by its code, its exact name, its mail slug, or a unique partial name match
     * ("Helix" or "Helix Air Freight GmbH" both resolve to "Helix Air Freight").
     */
    public Optional<Carrier> airlineByName(String nameOrCode) {
        if (nameOrCode == null || nameOrCode.isBlank()) return Optional.empty();
        String raw = nameOrCode.trim();
        String key = normalize(raw);
        List<Carrier> airlines = airlines();
        for (Carrier c : airlines) {
            if (c.code() != null && c.code().equalsIgnoreCase(raw)) return Optional.of(c);
        }
        for (Carrier c : airlines) {
            if (normalize(c.name()).equals(key) || normalize(slugOf(c.email())).equals(key)) return Optional.of(c);
        }
        if (key.length() < 4) return Optional.empty();
        List<Carrier> partial = airlines.stream()
                .filter(c -> key.contains(normalize(c.name())) || normalize(c.name()).startsWith(key))
                .toList();
        return partial.size() == 1 ? Optional.of(partial.getFirst()) : Optional.empty();
    }

    public Optional<Airport> airport(String iata) {
        return iata == null ? Optional.empty()
                : Optional.ofNullable(snapshot.get().airportsByIata.get(iata.trim().toUpperCase(Locale.ROOT)));
    }

    public List<Airport> airports() {
        return snapshot.get().airports;
    }

    /** Resolves "Frankfurt", "Frankfurt am Main" or "FRA" to an IATA code we know. */
    public Optional<String> iataForCityOrCode(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String t = text.trim();
        if (t.length() == 3 && airport(t).isPresent()) return Optional.of(t.toUpperCase(Locale.ROOT));
        String key = normalize(t);
        return snapshot.get().airports.stream()
                .filter(a -> normalize(a.city()).equals(key) || key.startsWith(normalize(a.city())))
                .max(Comparator.comparingInt(a -> a.city().length()))
                .map(Airport::iata);
    }

    /** Stores the two-letter airline code once the portal or a tariff reveals it. */
    public void updateAirlineCode(long carrierId, String code) {
        jdbc.sql("UPDATE carrier SET code = :code WHERE id = :id")
                .param("code", code)
                .param("id", carrierId)
                .update();
        refresh();
    }

    static String normalize(String s) {
        if (s == null) return "";
        String german = s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        String ascii = Normalizer.normalize(german, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ascii.replaceAll("[^a-z0-9]", "");
    }

    static String bareAddress(String email) {
        String e = email.trim();
        int lt = e.indexOf('<');
        int gt = e.indexOf('>');
        if (lt >= 0 && gt > lt) e = e.substring(lt + 1, gt);
        return e.trim().toLowerCase(Locale.ROOT);
    }

    private static String slugOf(String email) {
        int at = email.indexOf('@');
        return at < 0 ? email : email.substring(0, at);
    }

    private static Optional<String> zoneOf(String zip) {
        if (zip == null) return Optional.empty();
        String digits = zip.replaceAll("\\D", "");
        return digits.length() >= 2 ? Optional.of(digits.substring(0, 2)) : Optional.empty();
    }

    private static Carrier mapCarrier(ResultSet rs, int row) throws SQLException {
        Array serves = rs.getArray("serves");
        List<String> list = serves == null ? List.of() : Arrays.asList((String[]) serves.getArray());
        return new Carrier(rs.getLong("id"), rs.getString("name"), rs.getString("code"),
                Carrier.Kind.valueOf(rs.getString("kind")), rs.getString("email"), List.copyOf(list));
    }

    private record Snapshot(List<Carrier> carriers, List<PickupZone> zones, List<Airport> airports,
                            Map<String, Carrier> carriersByEmail, Map<String, PickupZone> zonesByCode,
                            Map<String, Airport> airportsByIata) {

        static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), List.of());

        Snapshot(List<Carrier> carriers, List<PickupZone> zones, List<Airport> airports) {
            this(List.copyOf(carriers), List.copyOf(zones), List.copyOf(airports),
                    carriers.stream().collect(Collectors.toUnmodifiableMap(c -> c.email().toLowerCase(Locale.ROOT), Function.identity())),
                    zones.stream().collect(Collectors.toUnmodifiableMap(PickupZone::zone, Function.identity())),
                    airports.stream().collect(Collectors.toUnmodifiableMap(Airport::iata, Function.identity())));
        }
    }
}
