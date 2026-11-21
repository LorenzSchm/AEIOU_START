package ai.aeiou.forwarder.api.query;

import ai.aeiou.forwarder.api.dto.LlmCall;
import ai.aeiou.forwarder.api.dto.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lists skip the {@code request}/{@code response} JSONB; only the detail endpoint loads them. */
@Repository
public class LlmCallQueries {

    private static final String COLUMNS = """
            SELECT id, session_id, purpose, model, prompt_tokens, completion_tokens, cost_usd, latency_ms, success,
                   created_at""";

    private final JdbcClient jdbc;
    private final JsonColumns json;

    public LlmCallQueries(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Newest first, keyset on {@code id}. */
    public Page<LlmCall> list(Long sessionId, int limit, Cursor cursor) {
        Where where = new Where();
        if (sessionId != null) where.and("session_id = :sessionId").param("sessionId", sessionId);
        where.idBefore("id", cursor);
        List<LlmCall> rows = jdbc.sql(COLUMNS + " FROM llm_call" + where.sql() + " ORDER BY id DESC LIMIT :limit")
                .params(where.params())
                .param("limit", limit + 1)
                .query((rs, n) -> map(rs, null, null))
                .list();
        return Page.of(rows, limit, c -> Cursor.of(c.id()));
    }

    public Optional<LlmCall> detail(long id) {
        return jdbc.sql(COLUMNS + ", request::text AS request, response::text AS response FROM llm_call WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs, json.value(rs.getString("request")), json.value(rs.getString("response"))))
                .optional();
    }

    private static LlmCall map(ResultSet rs, Object request, Object response) throws SQLException {
        return new LlmCall(rs.getLong("id"), Rows.longOrNull(rs, "session_id"), rs.getString("purpose"),
                rs.getString("model"), Rows.intOrNull(rs, "prompt_tokens"), Rows.intOrNull(rs, "completion_tokens"),
                Rows.decimal(rs, "cost_usd"), Rows.intOrNull(rs, "latency_ms"), rs.getBoolean("success"),
                Rows.time(rs, "created_at"), request, response);
    }
}
