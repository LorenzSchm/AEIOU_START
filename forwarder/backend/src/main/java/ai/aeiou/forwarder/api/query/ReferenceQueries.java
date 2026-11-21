package ai.aeiou.forwarder.api.query;

import ai.aeiou.forwarder.api.dto.BoardResult;
import ai.aeiou.forwarder.api.dto.Carrier;
import ai.aeiou.forwarder.api.dto.Enums.CarrierKind;
import ai.aeiou.forwarder.api.dto.Enums.TariffSource;
import ai.aeiou.forwarder.api.dto.Enums.TariffStatus;
import ai.aeiou.forwarder.api.dto.Setting;
import ai.aeiou.forwarder.api.dto.TariffDetail;
import ai.aeiou.forwarder.api.dto.TariffSummary;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Small, unpaginated reference lists: tariffs, carriers, settings and the cached board results. */
@Repository
public class ReferenceQueries {

    private static final String TARIFF_FROM = """
            SELECT t.id, t.airline_name, t.airline_code, t.version, t.source, t.valid_from, t.valid_to, t.currency,
                   t.status, t.notes, t.created_at,
                   (SELECT count(*) FROM tariff_lane l WHERE l.tariff_id = t.id) AS lane_count
            FROM tariff t
            """;

    private static final RowMapper<TariffSummary> TARIFF = (rs, n) -> new TariffSummary(
            rs.getLong("id"),
            rs.getString("airline_name"),
            rs.getString("airline_code"),
            rs.getString("version"),
            TariffSource.valueOf(rs.getString("source")),
            Rows.date(rs, "valid_from"),
            Rows.date(rs, "valid_to"),
            rs.getString("currency"),
            TariffStatus.valueOf(rs.getString("status")),
            rs.getInt("lane_count"),
            rs.getString("notes"),
            Rows.time(rs, "created_at"));

    private final JdbcClient jdbc;
    private final JsonColumns json;

    public ReferenceQueries(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Review queue first, then by airline, newest version first. */
    public List<TariffSummary> tariffs() {
        return jdbc.sql(TARIFF_FROM + " ORDER BY t.status = 'pending_review' DESC, t.airline_name, t.created_at DESC")
                .query(TARIFF)
                .list();
    }

    public Optional<TariffSummary> tariff(long id) {
        return jdbc.sql(TARIFF_FROM + " WHERE t.id = :id").param("id", id).query(TARIFF).optional();
    }

    public Optional<TariffDetail> tariffDetail(long id) {
        return tariff(id).map(t -> TariffDetail.of(t, lanes(id)));
    }

    private List<TariffDetail.Lane> lanes(long tariffId) {
        return jdbc.sql("""
                        SELECT origin, destination, transit_days, minimum, breaks::text AS breaks, fuel_per_kg,
                               security_per_kg
                        FROM tariff_lane WHERE tariff_id = :id ORDER BY origin, destination""")
                .param("id", tariffId)
                .query((rs, n) -> new TariffDetail.Lane(rs.getString("origin"), rs.getString("destination"),
                        Rows.intOrNull(rs, "transit_days"), Rows.money(rs, "minimum"), breaks(rs.getString("breaks")),
                        Rows.decimal(rs, "fuel_per_kg"), Rows.decimal(rs, "security_per_kg")))
                .list();
    }

    /** {@code [{"fromKg":0,"rate":5.92}, ...]}; entries without numbers are skipped. */
    private List<TariffDetail.Break> breaks(String raw) {
        if (!(json.value(raw) instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(m -> m.get("fromKg") instanceof Number && m.get("rate") instanceof Number)
                .map(m -> new TariffDetail.Break(new BigDecimal(m.get("fromKg").toString()),
                        Rows.decimal(new BigDecimal(m.get("rate").toString()))))
                .toList();
    }

    public List<Carrier> carriers() {
        return jdbc.sql("SELECT id, name, code, kind, email, serves FROM carrier ORDER BY kind, name")
                .query((rs, n) -> new Carrier(rs.getLong("id"), rs.getString("name"), rs.getString("code"),
                        CarrierKind.valueOf(rs.getString("kind")), rs.getString("email"), Rows.strings(rs, "serves")))
                .list();
    }

    public List<Setting> settings() {
        return jdbc.sql("SELECT key, value::text AS value, updated_at, updated_by FROM setting ORDER BY key")
                .query(this::mapSetting)
                .list();
    }

    public Optional<Setting> setting(String key) {
        return jdbc.sql("SELECT key, value::text AS value, updated_at, updated_by FROM setting WHERE key = :key")
                .param("key", key)
                .query(this::mapSetting)
                .optional();
    }

    private Setting mapSetting(ResultSet rs, int n) throws SQLException {
        return new Setting(rs.getString("key"), json.value(rs.getString("value")), Rows.time(rs, "updated_at"),
                rs.getString("updated_by"));
    }

    /** Cached /v1/board/me rows, newest sessions first. */
    public List<BoardResult> boardResults() {
        return jdbc.sql("""
                        SELECT b.request_key, b.session_id, s.public_ref, b.round, b.verdict, b.reason_code,
                               b.points::text AS points, b.fetched_at
                        FROM board_result b LEFT JOIN session s ON s.id = b.session_id
                        ORDER BY b.session_id DESC NULLS LAST, b.request_key DESC""")
                .query((rs, n) -> new BoardResult(rs.getString("request_key"), Rows.longOrNull(rs, "session_id"),
                        rs.getString("public_ref"), rs.getString("round"), rs.getString("verdict"),
                        rs.getString("reason_code"), json.numbers(rs.getString("points")),
                        Rows.time(rs, "fetched_at")))
                .list();
    }
}
