package ai.aeiou.forwarder.api.port;

import ai.aeiou.forwarder.router.MailType;

/**
 * Re-dispatches a stored mail as a different type. Implemented by the router.
 * Wired in {@link ai.aeiou.forwarder.config.ModuleWiring}.
 */
@FunctionalInterface
public interface MailRerouter {

    void reroute(long mailId, MailType type);
}
