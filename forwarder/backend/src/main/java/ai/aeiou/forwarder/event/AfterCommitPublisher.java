package ai.aeiou.forwarder.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes Spring application events only once the surrounding transaction has committed,
 * so listeners (SSE hub) never see rows that might still roll back. Without an active
 * transaction the event is published immediately.
 */
@Component
public class AfterCommitPublisher {

    private final ApplicationEventPublisher publisher;

    public AfterCommitPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(Object event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publishEvent(event);
                }
            });
        } else {
            publisher.publishEvent(event);
        }
    }

    /** Shorthand for {@code publish(new SessionChanged(sessionId))}. */
    public void sessionChanged(long sessionId) {
        publish(new SessionChanged(sessionId));
    }
}
