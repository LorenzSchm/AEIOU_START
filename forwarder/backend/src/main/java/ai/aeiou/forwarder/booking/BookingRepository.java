package ai.aeiou.forwarder.booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the booking and tracking workflows: bookings, the quoted candidate, the RFQ and reference data. */
@Repository
public class BookingRepository {

    /** The quoted option of a session, as written by the RFQ workflow ({@code chosen = true}). */
    public record ChosenCandidate(long id, String source, String airlineCode, String airlineName, BigDecimal total,
                                  String currency, BigDecimal totalEur, Integer transitDays, String rateId,
                                  OffsetDateTime validUntil) {
        public String airline() {
            return airlineName != null ? airlineName : airlineCode;
        }
    }

    public record RfqInfo(String originAirport, String destination, BigDecimal weightKg, BigDecimal volumeCbm,
                          Integer pieces, LocalDate readyDate, String commodity, String language, String customerName) {}

    private static final String BOOKING_COLUMNS = """
            id, session_id, rate_id, team_reference, booking_reference, airline_code, airline_name, origin, destination,
            price, currency, transit_days, status, booked_at, confirmed_at, departure_at, eta_at""";

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChosenCandidate> chosenCandidate(long sessionId) {
        return jdbc.sql("""
                        SELECT id, source, airline_code, airline_name, total, currency, total_eur, transit_days, rate_id, valid_until
                        FROM price_candidate WHERE session_id = ? AND chosen ORDER BY id DESC LIMIT 1""")
                .param(sessionId)
                .query(ChosenCandidate.class)
                .optional();
    }

    public Optional<RfqInfo> rfq(long sessionId) {
        return jdbc.sql("""
                        SELECT origin_airport, destination, weight_kg, volume_cbm, pieces, ready_date, commodity, language, customer_name
                        FROM rfq WHERE session_id = ?""")
                .param(sessionId)
                .query(RfqInfo.class)
                .optional();
    }

    public long insert(BookingRecord b) {
        return jdbc.sql("""
                        INSERT INTO booking (session_id, rate_id, team_reference, booking_reference, airline_code, airline_name,
                                             origin, destination, price, currency, transit_days, status, departure_at, eta_at)
                        VALUES (:sessionId, :rateId, :teamReference, :bookingReference, :airlineCode, :airlineName,
                                :origin, :destination, :price, :currency, :transitDays, :status, :departureAt, :etaAt)
                        RETURNING id""")
                .paramSource(b)
                .query(Long.class)
                .single();
    }

    /** Marks a booking confirmed and fills in the airline's reference if we booked by email and did not have it yet. */
    public void confirm(long bookingId, String bookingReference) {
        jdbc.sql("""
                        UPDATE booking SET status = 'confirmed', confirmed_at = coalesce(confirmed_at, now()),
                            booking_reference = coalesce(booking_reference, ?)
                        WHERE id = ?""")
                .param(bookingReference)
                .param(bookingId)
                .update();
    }

    public Optional<BookingRecord> findById(long id) {
        return jdbc.sql("SELECT " + BOOKING_COLUMNS + " FROM booking WHERE id = ?").param(id).query(BookingRecord.class).optional();
    }

    /** The current booking of a session (ignores failed attempts). */
    public Optional<BookingRecord> findActiveBySession(long sessionId) {
        return jdbc.sql("SELECT " + BOOKING_COLUMNS + " FROM booking WHERE session_id = ? AND status <> 'failed' ORDER BY id DESC LIMIT 1")
                .param(sessionId).query(BookingRecord.class).optional();
    }

    public Optional<BookingRecord> findByBookingReference(String reference) {
        return jdbc.sql("SELECT " + BOOKING_COLUMNS + " FROM booking WHERE upper(booking_reference) = upper(?)")
                .param(reference).query(BookingRecord.class).optional();
    }

    public Optional<BookingRecord> findByTeamReference(String reference) {
        return jdbc.sql("SELECT " + BOOKING_COLUMNS + " FROM booking WHERE upper(team_reference) = upper(?) AND status <> 'failed' ORDER BY id DESC LIMIT 1")
                .param(reference).query(BookingRecord.class).optional();
    }

    /** An email booking still waiting for the airline's answer, sent to this airline address. */
    public Optional<BookingRecord> findRequestedForAirlineEmail(String email) {
        return jdbc.sql("SELECT " + prefixed("b") + """
                         FROM booking b JOIN carrier c
                           ON (upper(c.code) = upper(b.airline_code) OR lower(c.name) = lower(b.airline_name))
                        WHERE b.status = 'requested' AND lower(c.email) = lower(?)
                        ORDER BY b.id DESC LIMIT 1""")
                .param(email).query(BookingRecord.class).optional();
    }

    /**
     * Bookings whose customer thread mentions {@code text}: the customer's own reference (PO number, order id) in the
     * session subject or any inbound mail. Most recent first.
     */
    public List<BookingRecord> findByCustomerText(String text) {
        String like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("SELECT " + prefixed("b") + """
                         FROM booking b JOIN session s ON s.id = b.session_id
                        WHERE b.status <> 'failed'
                          AND (s.subject ILIKE :like OR EXISTS (
                                SELECT 1 FROM mail m WHERE m.session_id = b.session_id AND m.direction = 'in'
                                   AND (m.subject ILIKE :like OR m.body ILIKE :like)))
                        ORDER BY b.id DESC LIMIT 5""")
                .param("like", like).query(BookingRecord.class).list();
    }

    /** The booking of any session that has a mail in this thread. */
    public Optional<BookingRecord> findByThread(long threadRootExt) {
        return jdbc.sql("SELECT " + prefixed("b") + """
                         FROM booking b
                        WHERE b.status <> 'failed' AND b.session_id IN (
                              SELECT m.session_id FROM mail m WHERE m.thread_root_ext = ? AND m.session_id IS NOT NULL)
                        ORDER BY b.id DESC LIMIT 1""")
                .param(threadRootExt).query(BookingRecord.class).optional();
    }

    public Optional<BookingRecord> findLatestByCustomer(String customerAddr) {
        return jdbc.sql("SELECT " + prefixed("b") + """
                         FROM booking b JOIN session s ON s.id = b.session_id
                        WHERE b.status <> 'failed' AND lower(s.customer_addr) = lower(?)
                        ORDER BY b.id DESC LIMIT 1""")
                .param(customerAddr).query(BookingRecord.class).optional();
    }

    public Optional<String> airlineEmail(String airlineName, String airlineCode) {
        return jdbc.sql("""
                        SELECT email FROM carrier
                        WHERE kind = 'airline' AND (lower(name) = lower(:name) OR upper(code) = upper(:code))
                        ORDER BY (lower(name) = lower(:name)) DESC LIMIT 1""")
                .param("name", airlineName == null ? "" : airlineName)
                .param("code", airlineCode == null ? "" : airlineCode)
                .query(String.class).optional();
    }

    /** The 5U id of the airline's answer to our bid request in this session (or of our request), to reply in that thread. */
    public Optional<Long> bidThreadMailExt(long sessionId, String carrierEmail) {
        return jdbc.sql("""
                        SELECT coalesce(mi.external_id, mo.external_id)
                        FROM bid_request br JOIN carrier c ON c.id = br.carrier_id
                        LEFT JOIN mail mi ON mi.id = br.in_mail_id
                        LEFT JOIN mail mo ON mo.id = br.out_mail_id
                        WHERE br.session_id = ? AND lower(c.email) = lower(?) AND coalesce(mi.external_id, mo.external_id) > 0
                        ORDER BY br.id DESC LIMIT 1""")
                .param(sessionId).param(carrierEmail)
                .query(Long.class).optional();
    }

    /** The 5U id of the customer's latest mail in the session: the mail we answer when informing them. */
    public Optional<Long> latestCustomerMailExt(long sessionId, String customerAddr) {
        return jdbc.sql("""
                        SELECT external_id FROM mail
                        WHERE session_id = ? AND direction = 'in' AND lower(from_addr) = lower(?)
                        ORDER BY sent_at DESC, id DESC LIMIT 1""")
                .param(sessionId).param(customerAddr == null ? "" : customerAddr)
                .query(Long.class).optional();
    }

    public Optional<String> airportCity(String iata) {
        if (iata == null) return Optional.empty();
        return jdbc.sql("SELECT city FROM airport WHERE iata = upper(?) AND city IS NOT NULL")
                .param(iata).query(String.class).optional();
    }

    public boolean hasEvent(long sessionId, String kind) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM event WHERE session_id = ? AND kind = ?)")
                .param(sessionId).param(kind).query(Boolean.class).single();
    }

    private static String prefixed(String alias) {
        return alias + "." + BOOKING_COLUMNS.replace("\n", " ").replace(", ", ", " + alias + ".");
    }
}
