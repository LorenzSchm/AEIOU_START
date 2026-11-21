package ai.aeiou.forwarder.api.query;

import ai.aeiou.forwarder.api.dto.ActiveShipment;
import ai.aeiou.forwarder.api.dto.Booking;
import ai.aeiou.forwarder.api.dto.Enums.BookingStatus;
import ai.aeiou.forwarder.api.dto.Page;
import ai.aeiou.forwarder.api.dto.ShipmentPoint;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BookingQueries {

    private static final String BOOKING_FROM = """
            SELECT b.id, b.session_id, s.public_ref, b.rate_id, b.team_reference, b.booking_reference,
                   b.airline_code, b.airline_name, b.origin, b.destination, b.price, b.currency, b.transit_days,
                   b.status, b.booked_at, b.confirmed_at, b.departure_at, b.eta_at
            FROM booking b JOIN session s ON s.id = b.session_id
            """;

    private static final RowMapper<Booking> BOOKING = (rs, n) -> new Booking(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getString("public_ref"),
            rs.getString("rate_id"),
            rs.getString("team_reference"),
            rs.getString("booking_reference"),
            rs.getString("airline_code"),
            rs.getString("airline_name"),
            rs.getString("origin"),
            rs.getString("destination"),
            Rows.money(rs, "price"),
            rs.getString("currency"),
            Rows.intOrNull(rs, "transit_days"),
            BookingStatus.valueOf(rs.getString("status")),
            Rows.time(rs, "booked_at"),
            Rows.time(rs, "confirmed_at"),
            Rows.time(rs, "departure_at"),
            Rows.time(rs, "eta_at"));

    private final JdbcClient jdbc;

    public BookingQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first, keyset on {@code id}. */
    public Page<Booking> list(BookingStatus status, int limit, Cursor cursor) {
        Where where = new Where();
        if (status != null) where.and("b.status = :status").param("status", status.name());
        where.idBefore("b.id", cursor);
        List<Booking> rows = jdbc.sql(BOOKING_FROM + where.sql() + " ORDER BY b.id DESC LIMIT :limit")
                .params(where.params())
                .param("limit", limit + 1)
                .query(BOOKING)
                .list();
        return Page.of(rows, limit, b -> Cursor.of(b.id()));
    }

    public Optional<Booking> latestForSession(long sessionId) {
        return jdbc.sql(BOOKING_FROM + " WHERE b.session_id = :id ORDER BY b.id DESC LIMIT 1")
                .param("id", sessionId)
                .query(BOOKING)
                .optional();
    }

    /** Globe data. Bookings whose airports have no coordinates yet are left out. */
    public List<ActiveShipment> activeShipments() {
        return jdbc.sql("""
                        SELECT b.id, b.session_id, s.public_ref, b.booking_reference, b.airline_name, b.status,
                               o.iata AS o_iata, o.city AS o_city, o.lat AS o_lat, o.lon AS o_lon,
                               d.iata AS d_iata, d.city AS d_city, d.lat AS d_lat, d.lon AS d_lon,
                               b.departure_at, b.eta_at, b.transit_days, b.price, b.currency
                        FROM booking b
                        JOIN session s ON s.id = b.session_id
                        JOIN airport o ON o.iata = b.origin
                        JOIN airport d ON d.iata = b.destination
                        WHERE b.status IN ('booked', 'confirmed')
                        ORDER BY b.id DESC""")
                .query((rs, n) -> new ActiveShipment(
                        rs.getLong("id"),
                        rs.getLong("session_id"),
                        rs.getString("public_ref"),
                        rs.getString("booking_reference"),
                        rs.getString("airline_name"),
                        BookingStatus.valueOf(rs.getString("status")),
                        new ShipmentPoint(rs.getString("o_iata"), rs.getString("o_city"), rs.getDouble("o_lat"),
                                rs.getDouble("o_lon")),
                        new ShipmentPoint(rs.getString("d_iata"), rs.getString("d_city"), rs.getDouble("d_lat"),
                                rs.getDouble("d_lon")),
                        Rows.time(rs, "departure_at"),
                        Rows.time(rs, "eta_at"),
                        Rows.intOrNull(rs, "transit_days"),
                        Rows.money(rs, "price"),
                        rs.getString("currency")))
                .list();
    }
}
