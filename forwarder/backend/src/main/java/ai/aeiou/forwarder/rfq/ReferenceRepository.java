package ai.aeiou.forwarder.rfq;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only access to the seeded reference tables {@code carrier} and {@code pickup_zone}. */
@Repository
public class ReferenceRepository {

    private final JdbcClient jdbc;

    public ReferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Carrier> airlinesServing(String destination) {
        return carriers("airline", destination.toUpperCase());
    }

    /** Truckers serving a 2-digit postcode zone. */
    public List<Carrier> truckersServing(String zone) {
        return carriers("trucker", zone);
    }

    public Optional<String> airportForZone(String zone) {
        return jdbc.sql("SELECT airport FROM pickup_zone WHERE zone = ?").param(zone).query(String.class).optional();
    }

    /** Best effort when the customer names a city but no postcode, e.g. "Darmstadt". */
    public Optional<String> airportForCity(String city) {
        return jdbc.sql("SELECT airport FROM pickup_zone WHERE lower(area) = lower(?) ORDER BY zone LIMIT 1")
                .param(city.trim())
                .query(String.class)
                .optional();
    }

    private List<Carrier> carriers(String kind, String serves) {
        return jdbc.sql("""
                        SELECT id, name, code, kind, email, serves
                        FROM carrier WHERE kind = ? AND ? = ANY (serves) ORDER BY name""")
                .param(kind)
                .param(serves)
                .query((rs, n) -> new Carrier(rs.getLong("id"), rs.getString("name"), rs.getString("code"),
                        rs.getString("kind"), rs.getString("email"),
                        Arrays.asList((String[]) rs.getArray("serves").getArray())))
                .list();
    }

    public record Carrier(long id, String name, String code, String kind, String email, List<String> serves) {}
}
