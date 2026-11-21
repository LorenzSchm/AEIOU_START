package ai.aeiou.forwarder.llm;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one gateway call: an {@code llm_call} row, its cost on {@code session.llm_cost_usd}
 * and an {@code llm.call} event, all in one transaction. Runs after the HTTP call, so no DB
 * connection is held while waiting for the model.
 */
@Component
class LlmCallRecorder {

    /** One recorded attempt. {@code request}/{@code response} are JSON strings. */
    record Call(Long sessionId, String purpose, String model, int promptTokens, int completionTokens, double costUsd,
                long latencyMs, boolean success, String request, String response, String error) {}

    private final JdbcClient jdbc;
    private final EventLog events;

    LlmCallRecorder(JdbcClient jdbc, EventLog events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public void record(Call call) {
        BigDecimal cost = BigDecimal.valueOf(call.costUsd()).setScale(6, RoundingMode.HALF_UP);
        long id = jdbc.sql("""
                        INSERT INTO llm_call (session_id, purpose, model, prompt_tokens, completion_tokens, cost_usd,
                                              latency_ms, success, request, response)
                        VALUES (:sessionId, :purpose, :model, :prompt, :completion, :cost, :latency, :success,
                                CAST(:request AS jsonb), CAST(:response AS jsonb))
                        RETURNING id""")
                .param("sessionId", call.sessionId())
                .param("purpose", call.purpose())
                .param("model", call.model())
                .param("prompt", call.promptTokens())
                .param("completion", call.completionTokens())
                .param("cost", cost)
                .param("latency", (int) call.latencyMs())
                .param("success", call.success())
                .param("request", call.request())
                .param("response", call.response())
                .query(Long.class)
                .single();

        if (call.sessionId() != null && cost.signum() > 0) {
            jdbc.sql("UPDATE session SET llm_cost_usd = llm_cost_usd + ?, updated_at = now() WHERE id = ?")
                    .params(cost, call.sessionId()).update();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("llmCallId", id);
        payload.put("purpose", call.purpose());
        payload.put("model", call.model());
        payload.put("promptTokens", call.promptTokens());
        payload.put("completionTokens", call.completionTokens());
        payload.put("costUsd", cost);
        payload.put("latencyMs", call.latencyMs());
        payload.put("success", call.success());
        if (call.error() != null) payload.put("error", call.error());
        String summary = "LLM %s · %s · %d+%d tok · $%s · %d ms%s".formatted(call.purpose(), call.model(),
                call.promptTokens(), call.completionTokens(), cost.toPlainString(), call.latencyMs(),
                call.success() ? "" : " · failed");
        events.append(call.sessionId(), "llm.call", Actor.llm, summary, payload);
    }
}
