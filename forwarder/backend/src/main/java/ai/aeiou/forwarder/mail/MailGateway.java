package ai.aeiou.forwarder.mail;

import java.util.Optional;

public interface MailGateway {

    /**
     * Sends via POST /v1/mailbox/messages, stores the sent mail (direction 'out') linked to the session
     * and writes a {@code mail.out} event. When app.mail.send-enabled=false it only logs a dry-run event
     * and returns a stored mail with a negative externalId.
     */
    StoredMail send(Long sessionId, OutgoingMail mail);

    Optional<StoredMail> findByExternalId(long externalId);

    Optional<StoredMail> findById(long id);

    byte[] attachmentContent(long attachmentId);
}
