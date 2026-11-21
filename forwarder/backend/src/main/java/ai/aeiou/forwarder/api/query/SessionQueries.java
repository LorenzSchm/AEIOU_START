package ai.aeiou.forwarder.api.query;

import ai.aeiou.forwarder.api.dto.BidRequest;
import ai.aeiou.forwarder.api.dto.Draft;
import ai.aeiou.forwarder.api.dto.Enums;
import ai.aeiou.forwarder.api.dto.Enums.BidKind;
import ai.aeiou.forwarder.api.dto.Enums.PriceSource;
import ai.aeiou.forwarder.api.dto.Enums.QuoteMode;
import ai.aeiou.forwarder.api.dto.Enums.SessionType;
import ai.aeiou.forwarder.api.dto.Page;
import ai.aeiou.forwarder.api.dto.PriceCandidate;
import ai.aeiou.forwarder.api.dto.Rfq;
import ai.aeiou.forwarder.api.dto.SessionDetail;
import ai.aeiou.forwarder.api.dto.SessionSummary;
import ai.aeiou.forwarder.session.SessionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read model for sessions. List views read only {@code session} plus the rfq lane and the latest board verdict,
 * never a JSONB column, and paginate by keyset on {@code (last_event_at, id)} (index {@code session_list_idx}).
 */
@Repository
public class SessionQueries {

    private static final String SUMMARY_FROM = """
            SELECT s.id, s.public_ref, s.type, s.status, s.customer_addr, s.subject, s.mode, s.needs_human,
                   s.needs_human_reason, s.autopilot, s.deadline_at, s.quoted_at, s.quoted_total, s.quoted_currency,
                   s.booked, s.llm_cost_usd, s.event_count, s.mail_count, s.last_event_at, s.last_event_summary,
                   s.created_at,
                   CASE WHEN coalesce(r.origin_airport, r.origin_zip, r.destination) IS NULL THEN NULL
                        ELSE coalesce(r.origin_airport, r.origin_zip, '?') || ' → ' || coalesce(r.destination, '?')
                   END AS lane,
                   bv.verdict AS board_verdict
            FROM session s
            LEFT JOIN rfq r ON r.session_id = s.id
            LEFT JOIN LATERAL (
                SELECT b.verdict FROM board_result b WHERE b.session_id = s.id
                ORDER BY b.fetched_at DESC, b.request_key DESC LIMIT 1
            ) bv ON true
            """;

    static final RowMapper<SessionSummary> SUMMARY = (rs, n) -> new SessionSummary(
            rs.getLong("id"),
            rs.getString("public_ref"),
            SessionType.valueOf(rs.getString("type")),
            SessionStatus.valueOf(rs.getString("status")),
            rs.getString("customer_addr"),
            rs.getString("subject"),
            Enums.parseOrNull(QuoteMode.class, rs.getString("mode")),
            rs.getBoolean("needs_human"),
            rs.getString("needs_human_reason"),
            rs.getBoolean("autopilot"),
            Rows.time(rs, "deadline_at"),
            Rows.time(rs, "quoted_at"),
            Rows.money(rs, "quoted_total"),
            rs.getString("quoted_currency"),
            rs.getBoolean("booked"),
            Rows.decimal(rs, "llm_cost_usd"),
            rs.getInt("event_count"),
            rs.getInt("mail_count"),
            Rows.time(rs, "last_event_at"),
            rs.getString("last_event_summary"),
            Rows.time(rs, "created_at"),
            rs.getString("lane"),
            rs.getString("board_verdict"));

    private final JdbcClient jdbc;
    private final JsonColumns json;
    private final BookingQueries bookings;

    public SessionQueries(JdbcClient jdbc, JsonColumns json, BookingQueries bookings) {
        this.jdbc = jdbc;
        this.json = json;
        this.bookings = bookings;
    }

    public Page<SessionSummary> list(SessionStatus status, SessionType type, Boolean needsHuman, String q,
                                     int limit, Cursor cursor) {
        Where where = new Where();
        if (status != null) where.and("s.status = :status").param("status", status.name());
        if (type != null) where.and("s.type = :type").param("type", type.name());
        if (needsHuman != null) where.and("s.needs_human = :needsHuman").param("needsHuman", needsHuman);
        if (q != null && !q.isBlank()) {
            where.and("(s.public_ref ILIKE :q OR s.subject ILIKE :q OR s.customer_addr ILIKE :q)")
                    .param("q", Rows.containsPattern(q.trim()));
        }
        where.before("s.last_event_at", "s.id", cursor);
        List<SessionSummary> rows = jdbc.sql(SUMMARY_FROM + where.sql()
                        + " ORDER BY s.last_event_at DESC, s.id DESC LIMIT :limit")
                .params(where.params())
                .param("limit", limit + 1)
                .query(SUMMARY)
                .list();
        return Page.of(rows, limit, s -> Cursor.of(s.lastEventAt(), s.id()));
    }

    public Optional<SessionSummary> summary(long id) {
        return jdbc.sql(SUMMARY_FROM + " WHERE s.id = :id").param("id", id).query(SUMMARY).optional();
    }

    /** Batch load for the SSE hub: one indexed query for a burst of changed sessions. */
    public List<SessionSummary> summaries(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(SUMMARY_FROM + " WHERE s.id IN (:ids)")
                .param("ids", List.copyOf(ids))
                .query(SUMMARY)
                .list();
    }

    public boolean exists(long id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM session WHERE id = :id)").param("id", id)
                .query(Boolean.class).single();
    }

    public Optional<SessionDetail> detail(long id) {
        return summary(id).map(s -> new SessionDetail(s, rfq(id).orElse(null), candidates(id), bids(id),
                bookings.latestForSession(id).orElse(null)));
    }

    public Optional<Draft> draft(long sessionId) {
        return jdbc.sql("""
                        SELECT session_id, to_addr, subject, body, in_reply_to_ext, created_at
                        FROM draft WHERE session_id = :id""")
                .param("id", sessionId)
                .query((rs, n) -> new Draft(rs.getLong("session_id"), rs.getString("to_addr"),
                        rs.getString("subject"), rs.getString("body"), Rows.longOrNull(rs, "in_reply_to_ext"),
                        Rows.time(rs, "created_at")))
                .optional();
    }

    private Optional<Rfq> rfq(long sessionId) {
        return jdbc.sql("""
                        SELECT origin_zip, origin_airport, destination, weight_kg, volume_cbm, chargeable_kg, pieces,
                               mode, door_pickup, ready_date, commodity, language, customer_name
                        FROM rfq WHERE session_id = :id""")
                .param("id", sessionId)
                .query((rs, n) -> new Rfq(rs.getString("origin_zip"), rs.getString("origin_airport"),
                        rs.getString("destination"), Rows.decimal(rs, "weight_kg"), Rows.decimal(rs, "volume_cbm"),
                        Rows.decimal(rs, "chargeable_kg"), Rows.intOrNull(rs, "pieces"), rs.getString("mode"),
                        rs.getBoolean("door_pickup"), Rows.date(rs, "ready_date"), rs.getString("commodity"),
                        rs.getString("language"), rs.getString("customer_name")))
                .optional();
    }

    private List<PriceCandidate> candidates(long sessionId) {
        return jdbc.sql("""
                        SELECT id, source, airline_code, airline_name, total, currency, total_eur, transit_days,
                               rate_id, valid_until, breakdown::text AS breakdown, chosen, reject_reason, created_at
                        FROM price_candidate WHERE session_id = :id
                        ORDER BY chosen DESC, total_eur, id""")
                .param("id", sessionId)
                .query(this::candidate)
                .list();
    }

    private PriceCandidate candidate(ResultSet rs, int n) throws SQLException {
        return new PriceCandidate(rs.getLong("id"), PriceSource.valueOf(rs.getString("source")),
                rs.getString("airline_code"), rs.getString("airline_name"), Rows.money(rs, "total"),
                rs.getString("currency"), Rows.money(rs, "total_eur"), Rows.intOrNull(rs, "transit_days"),
                rs.getString("rate_id"), Rows.time(rs, "valid_until"), json.object(rs.getString("breakdown")),
                rs.getBoolean("chosen"), rs.getString("reject_reason"), Rows.time(rs, "created_at"));
    }

    private List<BidRequest> bids(long sessionId) {
        return jdbc.sql("""
                        SELECT b.id, c.name, c.email, b.kind, b.sent_at, b.answered_at, b.price, b.currency,
                               b.transit_days, b.declined
                        FROM bid_request b JOIN carrier c ON c.id = b.carrier_id
                        WHERE b.session_id = :id ORDER BY b.sent_at, b.id""")
                .param("id", sessionId)
                .query((rs, n) -> new BidRequest(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                        BidKind.valueOf(rs.getString("kind")), Rows.time(rs, "sent_at"),
                        Rows.time(rs, "answered_at"), Rows.money(rs, "price"), rs.getString("currency"),
                        Rows.intOrNull(rs, "transit_days"), rs.getBoolean("declined")))
                .list();
    }
}
