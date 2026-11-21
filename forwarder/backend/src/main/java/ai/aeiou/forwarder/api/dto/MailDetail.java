package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.api.dto.Enums.MailDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "MailSummary plus body, threading headers and attachment metadata.")
public record MailDetail(
        long id,
        long externalId,
        MailDirection direction,
        String fromAddr,
        String toAddr,
        String subject,
        String snippet,
        @Schema(nullable = true) Long sessionId,
        @Schema(nullable = true) String sessionRef,
        @Schema(nullable = true) String routeType,
        @Schema(nullable = true) Double routeConfidence,
        OffsetDateTime sentAt,
        int attachmentCount,
        String body,
        @Schema(nullable = true) Long inReplyToExt,
        List<Long> referencesExt,
        List<Attachment> attachments) {

    public MailDetail withAttachments(List<Attachment> list) {
        return new MailDetail(id, externalId, direction, fromAddr, toAddr, subject, snippet, sessionId, sessionRef,
                routeType, routeConfidence, sentAt, list.size(), body, inReplyToExt, referencesExt, list);
    }
}
