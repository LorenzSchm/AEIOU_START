package ai.aeiou.forwarder.event;

/**
 * Spring application event published after a change to a {@code session} row was committed.
 * The SSE hub reloads the session summary and pushes it as {@code event: session}.
 */
public record SessionChanged(long sessionId) {}
