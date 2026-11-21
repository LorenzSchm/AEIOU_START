package ai.aeiou.forwarder.tariff;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL access to {@code tariff} and {@code tariff_lane}. Breaks and extras are stored as JSONB. */
@Repository
public class TariffRepository {

    private static final TypeReference<List<WeightBreak>> BREAKS = new TypeReference<>() {};
    private static final TypeReference<List<ExtraCharge>> EXTRAS = new TypeReference<>() {};
    private static final String SUMMARY_SQL = """
            SELECT t.*, (SELECT count(*) FROM tariff_lane l WHERE l.tariff_id = t.id) AS lane_count
            FROM tariff t
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public TariffRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public long insert(ParsedTariff t, String version, String source, Long sourceMailId, String sourceFile, TariffStatus status) {
        long id = jdbc.sql("""
                        INSERT INTO tariff (airline_name, airline_code, version, source, source_mail_id, source_file,
                                            valid_from, valid_to, currency, fx_eur_per_unit, status, notes)
                        VALUES (:airline, :code, :version, :source, :mailId, :file, :from, :to, :currency, :fx, :status, :notes)
                        RETURNING id
                        """)
                .param("airline", t.airlineName())
                .param("code", t.airlineCode())
                .param("version", version)
                .param("source", source)
                .param("mailId", sourceMailId)
                .param("file", sourceFile)
                .param("from", t.validFrom())
                .param("to", t.validTo())
                .param("currency", t.currency())
                .param("fx", t.fxEurPerUnit())
                .param("status", status.name())
                .param("notes", t.notes().isEmpty() ? null : String.join("\n", t.notes()))
                .query(Long.class).single();
        for (ParsedLane lane : t.lanes()) {
            jdbc.sql("""
                            INSERT INTO tariff_lane (tariff_id, origin, destination, transit_days, minimum, breaks,
                                                     fuel_per_kg, security_per_kg, extra)
                            VALUES (:id, :origin, :dest, :transit, :minimum, CAST(:breaks AS jsonb), :fuel, :security, CAST(:extra AS jsonb))
                            """)
                    .param("id", id)
                    .param("origin", lane.origin())
                    .param("dest", lane.destination())
                    .param("transit", lane.transitDays())
                    .param("minimum", lane.minimum())
                    .param("breaks", json.writeValueAsString(lane.breaks()))
                    .param("fuel", lane.fuelPerKg())
                    .param("security", lane.securityPerKg())
                    .param("extra", json.writeValueAsString(lane.extras()))
                    .update();
        }
        return id;
    }

    public Optional<StoredTariff> find(long id) {
        return jdbc.sql(SUMMARY_SQL + " WHERE t.id = :id").param("id", id).query(TariffRepository::mapTariff).optional();
    }

    public List<StoredTariff> list() {
        return jdbc.sql(SUMMARY_SQL + " ORDER BY t.created_at DESC, t.id DESC").query(TariffRepository::mapTariff).list();
    }

    /** Active versions of one airline, newest validity first. */
    public List<StoredTariff> activeFor(String airlineName) {
        return jdbc.sql(SUMMARY_SQL + """
                         WHERE t.status = 'active' AND lower(t.airline_name) = lower(:airline)
                         ORDER BY t.valid_from DESC NULLS LAST, t.id DESC
                        """)
                .param("airline", airlineName)
                .query(TariffRepository::mapTariff).list();
    }

    public List<ParsedLane> lanes(long tariffId) {
        return jdbc.sql("SELECT * FROM tariff_lane WHERE tariff_id = :id ORDER BY origin, destination")
                .param("id", tariffId)
                .query((rs, n) -> mapLane(rs)).list();
    }

    /** Every lane of every active tariff, for the in-memory pricing snapshot. */
    public List<RateCard> activeRateCards() {
        return jdbc.sql("""
                        SELECT t.id AS t_id, t.version, t.airline_name, t.airline_code, t.currency, t.fx_eur_per_unit,
                               t.valid_from, t.valid_to, l.*
                        FROM tariff t JOIN tariff_lane l ON l.tariff_id = t.id
                        WHERE t.status = 'active'
                        """)
                .query((rs, n) -> new RateCard(rs.getLong("t_id"), rs.getString("version"), rs.getString("airline_name"),
                        rs.getString("airline_code"), rs.getString("currency"), rs.getBigDecimal("fx_eur_per_unit"),
                        rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class), mapLane(rs)))
                .list();
    }

    public void setStatus(long id, TariffStatus status) {
        jdbc.sql("UPDATE tariff SET status = :status WHERE id = :id").param("status", status.name()).param("id", id).update();
    }

    /**
     * Marks an active version superseded once a newer active version of the same airline is in force
     * (its valid_from has been reached). Versions valid from a future date stay active and wait.
     */
    public int supersedeOutdated(LocalDate today) {
        return jdbc.sql("""
                        UPDATE tariff t SET status = 'superseded'
                        WHERE t.status = 'active' AND EXISTS (
                            SELECT 1 FROM tariff n
                            WHERE n.status = 'active' AND n.id <> t.id
                              AND lower(n.airline_name) = lower(t.airline_name)
                              AND coalesce(n.valid_from, DATE '1900-01-01') <= :today
                              AND (coalesce(n.valid_from, DATE '1900-01-01') > coalesce(t.valid_from, DATE '1900-01-01')
                                   OR (n.valid_from IS NOT DISTINCT FROM t.valid_from AND n.id > t.id)))
                        """)
                .param("today", today)
                .update();
    }

    public boolean kitFileImported(String sourceFile) {
        return jdbc.sql("SELECT count(*) FROM tariff WHERE source = 'kit' AND source_file = :file")
                .param("file", sourceFile)
                .query(Long.class).single() > 0;
    }

    private ParsedLane mapLane(ResultSet rs) throws SQLException {
        String extra = rs.getString("extra");
        Integer transit = rs.getObject("transit_days", Integer.class);
        return new ParsedLane(rs.getString("origin"), rs.getString("destination"), transit, rs.getBigDecimal("minimum"),
                json.readValue(rs.getString("breaks"), BREAKS), rs.getBigDecimal("fuel_per_kg"), rs.getBigDecimal("security_per_kg"),
                extra == null || extra.isBlank() || !extra.trim().startsWith("[") ? List.of() : json.readValue(extra, EXTRAS));
    }

    private static StoredTariff mapTariff(ResultSet rs, int row) throws SQLException {
        return new StoredTariff(rs.getLong("id"), rs.getString("airline_name"), rs.getString("airline_code"), rs.getString("version"),
                rs.getString("source"), rs.getString("source_file"), rs.getObject("source_mail_id", Long.class),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class), rs.getString("currency"),
                rs.getBigDecimal("fx_eur_per_unit"),
                TariffStatus.valueOf(rs.getString("status")), rs.getInt("lane_count"), rs.getString("notes"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
