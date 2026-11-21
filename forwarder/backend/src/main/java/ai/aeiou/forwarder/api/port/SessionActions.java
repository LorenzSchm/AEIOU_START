package ai.aeiou.forwarder.api.port;

import ai.aeiou.forwarder.router.MailType;

/**
 * Human actions on a session, as triggered from the control panel. Every implementation writes a
 * {@code human.*} event (actor {@code human}) in the same transaction as the change.
 */
public interface SessionActions {

    /** Sends the pending draft unchanged. */
    void approve(long sessionId);

    /** Sends {@code body} instead of the pending draft. */
    void editAndSend(long sessionId, String body);

    /** Re-routes the session's latest inbound mail as {@code type}. */
    void reroute(long sessionId, MailType type);

    /** Autopilot off: the agents only prepare drafts for this session. */
    void takeOver(long sessionId);

    /** Autopilot back on. */
    void release(long sessionId);

    /** Clears the needs-human flag. */
    void resolve(long sessionId, String note);
}
