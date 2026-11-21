package ai.aeiou.forwarder.rfq;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Persistence of the RFQ workflow: {@code rfq}, {@code bid_request}, {@code price_candidate}, {@code draft}. */
@Repository
public class RfqRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public RfqRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // rfq ------------------------------------------------------------------------------------------------------

    /** Inserts the RFQ once; returns false when the session already has one (re-delivered mail). */
    public boolean insertRfq(RfqRecord r, Object extraction) {
        return jdbc.sql("""
                        INSERT INTO rfq (session_id, mail_id, origin_zip, origin_airport, destination, weight_kg, volume_cbm,
                                         chargeable_kg, pieces, mode, door_pickup, ready_date, commodity, language,
                                         customer_name, extraction)
                        VALUES (:sessionId, :mailId, :originZip, :originAirport, :destination, :weightKg, :volumeCbm,
                                :chargeableKg, :pieces, :mode, :doorPickup, :readyDate, :commodity, :language,
                                :customerName, CAST(:extraction AS jsonb))
                        ON CONFLICT (session_id) DO NOTHING""")
                .param("sessionId", r.sessionId())
                .param("mailId", r.mailId())
                .param("originZip", r.originZip())
                .param("originAirport", r.originAirport())
                .param("destination", r.destination())
                .param("weightKg", r.weightKg())
                .param("volumeCbm", r.volumeCbm())
                .param("chargeableKg", r.chargeableKg())
                .param("pieces", r.pieces())
                .param("mode", r.mode())
                .param("doorPickup", r.doorPickup())
                .param("readyDate", r.readyDate())
                .param("commodity", r.commodity())
                .param("language", r.language())
                .param("customerName", r.customerName())
                .param("extraction", extraction == null ? null : json.writeValueAsString(extraction))
                .update() == 1;
    }

    public Optional<RfqRecord> findRfq(long sessionId) {
        return jdbc.sql("SELECT * FROM rfq WHERE session_id = ?").param(sessionId).query(RfqRepository::rfq).optional();
    }

    /** The one-time decision claim: true for exactly one caller per session. */
    public boolean claimDecision(long sessionId) {
        return jdbc.sql("UPDATE rfq SET decided_at = now() WHERE session_id = ? AND decided_at IS NULL")
                .param(sessionId).update() == 1;
    }

    /** RFQ sessions created after {@code since} that were neither decided nor quoted. */
    public List<Long> undecidedSessions(java.time.Instant since) {
        return jdbc.sql("""
                        SELECT r.session_id FROM rfq r JOIN session s ON s.id = r.session_id
                        WHERE r.decided_at IS NULL AND s.quoted_at IS NULL AND s.created_at > ?
                          AND r.destination IS NOT NULL AND r.weight_kg IS NOT NULL""")
                .param(OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC))
                .query(Long.class)
                .list();
    }

    // bid_request ------------------------------------------------------------------------------------------------

    public long insertBidRequest(long sessionId, Long carrierId, String kind, String toAddr, Long outMailId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO bid_request (session_id, carrier_id, kind, to_addr, out_mail_id) VALUES (?, ?, ?, ?, ?)")
                .params(sessionId, carrierId, kind, toAddr, outMailId)
                .update(keys, "id");
        return keys.getKeyAs(Long.class);
    }

    /** Requests that neither answered nor declined. */
    public List<BidRequestRow> openBidRequests(long sessionId) {
        return jdbc.sql(BID_SELECT + " WHERE b.session_id = ? AND b.answered_at IS NULL ORDER BY b.id")
                .param(sessionId).query(RfqRepository::bidRequest).list();
    }

    /**
     * Finds the request a reply answers: by the sender (we wrote to that address) or by the thread
     * (the reply's in_reply_to is our request mail). Unanswered requests win over answered ones.
     */
    public Optional<BidRequestRow> findBidRequestForReply(long sessionId, String fromAddr, Long inReplyToExt) {
        return jdbc.sql(BID_SELECT + """
                         LEFT JOIN mail m ON m.id = b.out_mail_id
                        WHERE b.session_id = :sessionId
                          AND (lower(b.to_addr) = lower(:fromAddr) OR (CAST(:inReplyTo AS BIGINT) IS NOT NULL AND m.external_id = :inReplyTo))
                        ORDER BY (b.answered_at IS NULL) DESC, b.id DESC
                        LIMIT 1""")
                .param("sessionId", sessionId)
                .param("fromAddr", fromAddr)
                .param("inReplyTo", inReplyToExt)
                .query(RfqRepository::bidRequest)
                .optional();
    }

    public void recordBidAnswer(long bidRequestId, long inMailId, BigDecimal price, String currency, Integer transitDays,
                                OffsetDateTime validUntil, boolean declined) {
        jdbc.sql("""
                        UPDATE bid_request SET in_mail_id = ?, answered_at = now(), price = ?, currency = ?,
                                               transit_days = ?, valid_until = ?, declined = ?
                        WHERE id = ?""")
                .params(inMailId, price, currency, transitDays, validUntil, declined, bidRequestId)
                .update();
    }

    // price_candidate --------------------------------------------------------------------------------------------

    public long insertCandidate(long sessionId, Candidate c) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO price_candidate (session_id, source, airline_code, airline_name, total, currency, total_eur,
                                                     transit_days, rate_id, valid_until, breakdown)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))""")
                .params(sessionId, c.source().name(), c.airlineCode(), c.airlineName(), Money.round(c.total()), c.currency(),
                        Money.round(c.totalEur()), c.transitDays(), c.rateId(), c.validUntil(),
                        c.breakdown() == null ? null : json.writeValueAsString(c.breakdown()))
                .update(keys, "id");
        return keys.getKeyAs(Long.class);
    }

    public List<Candidate> candidates(long sessionId) {
        return jdbc.sql("SELECT * FROM price_candidate WHERE session_id = ? ORDER BY id")
                .param(sessionId)
                .query((rs, n) -> new Candidate(rs.getLong("id"), CandidateSource.valueOf(rs.getString("source")),
                        rs.getString("airline_code"), rs.getString("airline_name"), rs.getBigDecimal("total"),
                        rs.getString("currency"), rs.getBigDecimal("total_eur"), (Integer) rs.getObject("transit_days"),
                        rs.getString("rate_id"), rs.getObject("valid_until", OffsetDateTime.class),
                        readMap(rs.getString("breakdown"))))
                .list();
    }

    public void markCandidate(long candidateId, boolean chosen, String rejectReason) {
        jdbc.sql("UPDATE price_candidate SET chosen = ?, reject_reason = ? WHERE id = ?")
                .params(chosen, rejectReason, candidateId).update();
    }

    // draft ------------------------------------------------------------------------------------------------------

    public void saveDraft(Draft d) {
        jdbc.sql("""
                        INSERT INTO draft (session_id, purpose, to_addr, subject, body, in_reply_to_ext, meta)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                        ON CONFLICT (session_id) DO UPDATE SET purpose = EXCLUDED.purpose, to_addr = EXCLUDED.to_addr,
                            subject = EXCLUDED.subject, body = EXCLUDED.body, in_reply_to_ext = EXCLUDED.in_reply_to_ext,
                            meta = EXCLUDED.meta, created_at = now()""")
                .params(d.sessionId(), d.purpose(), d.toAddr(), d.subject(), d.body(), d.inReplyToExt(),
                        d.meta() == null ? null : json.writeValueAsString(d.meta()))
                .update();
    }

    public Optional<Draft> findDraft(long sessionId) {
        return jdbc.sql("SELECT * FROM draft WHERE session_id = ?")
                .param(sessionId)
                .query((rs, n) -> new Draft(rs.getLong("session_id"), rs.getString("purpose"), rs.getString("to_addr"),
                        rs.getString("subject"), rs.getString("body"), (Long) rs.getObject("in_reply_to_ext"),
                        readMap(rs.getString("meta"))))
                .optional();
    }

    public void deleteDraft(long sessionId) {
        jdbc.sql("DELETE FROM draft WHERE session_id = ?").param(sessionId).update();
    }

    // mapping ----------------------------------------------------------------------------------------------------

    private static final String BID_SELECT = """
            SELECT b.id, b.session_id, b.carrier_id, b.kind, b.to_addr, b.out_mail_id, b.answered_at, b.declined,
                   c.name AS carrier_name, c.code AS carrier_code
            FROM bid_request b LEFT JOIN carrier c ON c.id = b.carrier_id""";

    private Map<String, Object> readMap(String value) {
        return value == null ? null : json.readValue(value, MAP);
    }

    private static RfqRecord rfq(ResultSet rs, int n) throws SQLException {
        return new RfqRecord(rs.getLong("session_id"), (Long) rs.getObject("mail_id"), rs.getString("origin_zip"),
                rs.getString("origin_airport"), rs.getString("destination"), rs.getBigDecimal("weight_kg"),
                rs.getBigDecimal("volume_cbm"), rs.getBigDecimal("chargeable_kg"), (Integer) rs.getObject("pieces"),
                rs.getString("mode"), rs.getBoolean("door_pickup"), rs.getObject("ready_date", LocalDate.class),
                rs.getString("commodity"), rs.getString("language"), rs.getString("customer_name"),
                rs.getObject("decided_at", OffsetDateTime.class));
    }

    private static BidRequestRow bidRequest(ResultSet rs, int n) throws SQLException {
        return new BidRequestRow(rs.getLong("id"), rs.getLong("session_id"), (Long) rs.getObject("carrier_id"),
                rs.getString("kind"), rs.getString("to_addr"), (Long) rs.getObject("out_mail_id"),
                rs.getString("carrier_name"), rs.getString("carrier_code"),
                rs.getObject("answered_at", OffsetDateTime.class), rs.getBoolean("declined"));
    }

    public record RfqRecord(long sessionId, Long mailId, String originZip, String originAirport, String destination,
                            BigDecimal weightKg, BigDecimal volumeCbm, BigDecimal chargeableKg, Integer pieces,
                            String mode, boolean doorPickup, LocalDate readyDate, String commodity, String language,
                            String customerName, OffsetDateTime decidedAt) {}

    public record BidRequestRow(long id, long sessionId, Long carrierId, String kind, String toAddr, Long outMailId,
                                String carrierName, String carrierCode, OffsetDateTime answeredAt, boolean declined) {}

    public record Draft(long sessionId, String purpose, String toAddr, String subject, String body, Long inReplyToExt,
                        Map<String, Object> meta) {}
}
