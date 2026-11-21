package ai.aeiou.forwarder.llm;

import java.util.List;

/**
 * OpenAI-compatible client for the 5U LLM gateway. Every call is logged to {@code llm_call},
 * its cost is added to {@code session.llm_cost_usd}, and an {@code llm.call} event is written.
 */
public interface LlmClient {

    /** Structured output: the model must answer with JSON matching {@code type} (strict JSON schema derived from the record). */
    <T> LlmResult<T> structured(LlmRequest request, Class<T> type);

    /** Plain text completion. */
    LlmResult<String> text(LlmRequest request);

    record LlmRequest(Long sessionId, String purpose, String model, List<ChatMessage> messages, Double temperature) {
        public static LlmRequest of(Long sessionId, String purpose, String model, List<ChatMessage> messages) {
            return new LlmRequest(sessionId, purpose, model, messages, null);
        }
    }

    record ChatMessage(String role, String content) {
        public static ChatMessage system(String c) { return new ChatMessage("system", c); }
        public static ChatMessage user(String c) { return new ChatMessage("user", c); }
        public static ChatMessage assistant(String c) { return new ChatMessage("assistant", c); }
    }

    record LlmResult<T>(T value, String model, int promptTokens, int completionTokens, double costUsd, long latencyMs) {}
}
