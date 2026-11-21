package ai.aeiou.forwarder.mail;

import java.time.OffsetDateTime;
import java.util.List;

/** A mail row as stored in the {@code mail} table. {@code id} is ours, {@code externalId} is the 5U mailbox id. */
public record StoredMail(
        long id,
        long externalId,
        String direction,
        String fromAddr,
        String toAddr,
        String subject,
        String body,
        Long inReplyToExt,
        List<Long> referencesExt,
        Long threadRootExt,
        Long sessionId,
        OffsetDateTime sentAt,
        List<AttachmentRef> attachments) {

    public record AttachmentRef(long id, Long externalId, String filename, String contentType, Long size) {}
}
