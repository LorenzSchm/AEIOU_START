package ai.aeiou.forwarder.llm;

/** The gateway call failed (HTTP error, refusal, or an answer that did not match the schema) after retries. */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
