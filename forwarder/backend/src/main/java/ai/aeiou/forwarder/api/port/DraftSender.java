package ai.aeiou.forwarder.api.port;

/**
 * Sends a session's pending draft. Implemented by the RFQ module.
 * Wired in {@link ai.aeiou.forwarder.config.ModuleWiring}.
 */
@FunctionalInterface
public interface DraftSender {

    /** @param overrideBody body to send instead of the draft's, or null to send the draft unchanged */
    void sendDraft(long sessionId, String overrideBody);
}
