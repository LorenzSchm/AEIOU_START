package ai.aeiou.forwarder.router;

import java.util.Set;

/** Workflow beans implement this; the router dispatches each routed mail to every handler that supports its type. */
public interface MailHandler {

    Set<MailType> supports();

    void handle(RoutedMail mail);
}
