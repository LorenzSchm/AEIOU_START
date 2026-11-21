package ai.aeiou.forwarder.router;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.session.SessionService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Hands a routed mail to every {@link MailHandler} that supports its type. A failing handler
 * never affects the others: its exception becomes a {@code system.error} event and the session
 * is flagged for a human.
 */
@Component
class HandlerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HandlerDispatcher.class);

    private final ObjectProvider<MailHandler> handlers;
    private final EventLog events;
    private final SessionService sessions;

    HandlerDispatcher(ObjectProvider<MailHandler> handlers, EventLog events, SessionService sessions) {
        this.handlers = handlers;
        this.events = events;
        this.sessions = sessions;
    }

    void dispatch(RoutedMail routed) {
        List<MailHandler> matching = handlers.orderedStream().filter(h -> h.supports().contains(routed.type())).toList();
        if (matching.isEmpty() && routed.type() != MailType.OTHER) {
            events.append(routed.sessionId(), routed.mail().id(), "route.unhandled", Actor.system,
                    "No handler for " + routed.type(), Map.of("type", routed.type()));
        }
        for (MailHandler handler : matching) {
            String name = ClassUtils.getUserClass(handler).getSimpleName();
            try {
                handler.handle(routed);
            } catch (RuntimeException e) {
                log.error("Handler {} failed on mail {}", name, routed.mail().id(), e);
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                events.append(routed.sessionId(), routed.mail().id(), "system.error", Actor.system,
                        name + " failed: " + message,
                        Map.of("handler", name, "type", routed.type(), "error", e.getClass().getName(), "message", message));
                sessions.flagNeedsHuman(routed.sessionId(), name + " failed: " + message);
            }
        }
    }
}
