package ai.aeiou.forwarder.mail;

import java.util.List;

/** {@code inReplyToExt} is the 5U mailbox id of the mail we answer (keeps the thread). */
public record OutgoingMail(String to, String subject, String body, Long inReplyToExt, List<Attachment> attachments) {

    public static OutgoingMail reply(StoredMail original, String body) {
        String subj = original.subject() == null ? "" : original.subject();
        if (!subj.regionMatches(true, 0, "Re:", 0, 3)) subj = "Re: " + subj;
        return new OutgoingMail(original.fromAddr(), subj, body, original.externalId(), List.of());
    }

    public static OutgoingMail fresh(String to, String subject, String body) {
        return new OutgoingMail(to, subject, body, null, List.of());
    }

    public record Attachment(String filename, String contentType, byte[] content) {}
}
