package ai.aeiou.forwarder.router;

import ai.aeiou.forwarder.mail.StoredMail;

/** A mail after routing. {@code sessionId} is already assigned (new or existing session). {@code extraction} may be null for stage-1 decisions. */
public record RoutedMail(StoredMail mail, MailType type, double confidence, int stage, Long sessionId, Extraction extraction) {}
