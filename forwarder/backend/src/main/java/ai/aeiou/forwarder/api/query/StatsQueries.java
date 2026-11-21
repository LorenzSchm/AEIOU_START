package ai.aeiou.forwarder.api.query;

import ai.aeiou.forwarder.api.dto.Stats;
import ai.aeiou.forwarder.session.SessionStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Dashboard KPIs in two aggregate queries: all-time totals and an hourly series.
 * <p>
 * Response time = first outbound mail to the customer at or after the quote claim (so a holding reply does not
 * count as the quote) minus the first inbound RFQ mail of the session.
 */
@Repository
public class StatsQueries {

    /** Per RFQ session: {@code rfq_in} and {@code quote_out}; both lookups use {@code mail_session_idx}. */
    private static final String RESPONSE_TIMES = """
            SELECT s.id, s.created_at, i.rfq_in, o.quote_out
            FROM session s
            CROSS JOIN LATERAL (
                SELECT min(m.sent_at) AS rfq_in FROM mail m WHERE m.session_id = s.id AND m.direction = 'in'
            ) i
            CROSS JOIN LATERAL (
                SELECT min(m.sent_at) AS quote_out FROM mail m
                WHERE m.session_id = s.id AND m.direction = 'out' AND s.quoted_at IS NOT NULL
                  AND lower(m.to_addr) = lower(s.customer_addr) AND m.sent_at >= s.quoted_at - interval '5 seconds'
            ) o
            WHERE s.type = 'RFQ'
            """;

    private static final String TOTALS = "WITH resp AS (" + RESPONSE_TIMES + """
            ), secs AS (
                SELECT extract(epoch FROM quote_out - rfq_in)::float8 AS s FROM resp
                WHERE quote_out IS NOT NULL AND rfq_in IS NOT NULL
            )
            SELECT (SELECT count(*) FROM session) AS sessions_total,
                   (SELECT count(*) FROM session WHERE type = 'RFQ') AS rfqs_total,
                   (SELECT count(*) FROM session WHERE quoted_at IS NOT NULL) AS quotes_sent,
                   (SELECT count(*) FROM session WHERE booked) AS booked,
                   (SELECT count(*) FROM session WHERE needs_human) AS needs_human,
                   (SELECT count(*) FROM board_result WHERE verdict = 'accepted') AS accepted,
                   (SELECT count(*) FROM board_result WHERE verdict = 'rejected') AS rejected,
                   (SELECT coalesce(sum(cost_usd), 0) FROM llm_call) AS llm_cost,
                   (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY s) FROM secs) AS median_secs,
                   (SELECT percentile_cont(0.9) WITHIN GROUP (ORDER BY s) FROM secs) AS p90_secs,
                   (SELECT coalesce(json_object_agg(status, n), '{}')::text
                    FROM (SELECT status, count(*) AS n FROM session GROUP BY status) c) AS status_counts
            """;

    private static final String HOURLY = """
            WITH hours AS (
                SELECT generate_series(date_trunc('hour', now()) - make_interval(hours => :hours - 1),
                                       date_trunc('hour', now()), interval '1 hour') AS hour
            ), since AS (
                SELECT min(hour) AS t FROM hours
            ), resp AS (""" + RESPONSE_TIMES + """
                  AND s.created_at >= (SELECT t FROM since)
            ), r AS (
                SELECT date_trunc('hour', rfq_in) AS hour, count(*) AS rfqs,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM quote_out - rfq_in)::float8)
                           AS median_secs
                FROM resp WHERE rfq_in IS NOT NULL GROUP BY 1
            ), q AS (
                SELECT date_trunc('hour', quote_out) AS hour, count(*) AS quotes
                FROM resp WHERE quote_out IS NOT NULL GROUP BY 1
            ), b AS (
                SELECT date_trunc('hour', coalesce(s.created_at, br.fetched_at)) AS hour,
                       count(*) FILTER (WHERE br.verdict = 'accepted') AS accepted,
                       count(*) FILTER (WHERE br.verdict = 'rejected') AS rejected
                FROM board_result br LEFT JOIN session s ON s.id = br.session_id
                WHERE coalesce(s.created_at, br.fetched_at) >= (SELECT t FROM since)
                GROUP BY 1
            ), l AS (
                SELECT date_trunc('hour', created_at) AS hour, sum(cost_usd) AS cost
                FROM llm_call WHERE created_at >= (SELECT t FROM since) GROUP BY 1
            )
            SELECT h.hour, coalesce(r.rfqs, 0) AS rfqs, coalesce(q.quotes, 0) AS quotes,
                   coalesce(b.accepted, 0) AS accepted, coalesce(b.rejected, 0) AS rejected,
                   coalesce(l.cost, 0) AS llm_cost, r.median_secs
            FROM hours h
            LEFT JOIN r USING (hour)
            LEFT JOIN q USING (hour)
            LEFT JOIN b USING (hour)
            LEFT JOIN l USING (hour)
            ORDER BY h.hour
            """;

    private final JdbcClient jdbc;
    private final JsonColumns json;

    public StatsQueries(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Stats stats(int hours) {
        List<Stats.Hour> hourly = jdbc.sql(HOURLY)
                .param("hours", hours)
                .query((rs, n) -> new Stats.Hour(Rows.time(rs, "hour"), rs.getLong("rfqs"), rs.getLong("quotes"),
                        rs.getLong("accepted"), rs.getLong("rejected"), Rows.decimal(rs, "llm_cost"),
                        Rows.doubleOrNull(rs, "median_secs")))
                .list();
        return jdbc.sql(TOTALS)
                .query((rs, n) -> {
                    long quotes = rs.getLong("quotes_sent");
                    BigDecimal cost = rs.getBigDecimal("llm_cost");
                    BigDecimal perQuote = quotes == 0 ? BigDecimal.ZERO
                            : cost.divide(BigDecimal.valueOf(quotes), 6, RoundingMode.HALF_UP);
                    return new Stats(rs.getLong("sessions_total"), rs.getLong("rfqs_total"), quotes,
                            rs.getLong("booked"), rs.getLong("needs_human"), rs.getLong("accepted"),
                            rs.getLong("rejected"), Rows.decimal(cost), Rows.decimal(perQuote),
                            Rows.doubleOrNull(rs, "median_secs"), Rows.doubleOrNull(rs, "p90_secs"), hourly,
                            statusCounts(rs.getString("status_counts")));
                })
                .single();
    }

    private Map<SessionStatus, Long> statusCounts(String raw) {
        Map<SessionStatus, Long> counts = new EnumMap<>(SessionStatus.class);
        for (SessionStatus s : SessionStatus.values()) counts.put(s, 0L);
        json.object(raw).forEach((status, n) -> {
            for (SessionStatus s : SessionStatus.values()) {
                if (s.name().equals(status) && n instanceof Number num) counts.put(s, num.longValue());
            }
        });
        return counts;
    }
}
