package ai.aeiou.forwarder.api;

import ai.aeiou.forwarder.api.dto.Enums.MailDirection;
import ai.aeiou.forwarder.api.dto.MailDetail;
import ai.aeiou.forwarder.api.dto.MailSummary;
import ai.aeiou.forwarder.api.dto.Page;
import ai.aeiou.forwarder.api.query.Cursor;
import ai.aeiou.forwarder.api.query.MailQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "mails")
public class MailController {

    private final MailQueries mails;

    public MailController(MailQueries mails) {
        this.mails = mails;
    }

    @GetMapping("/api/mails")
    @Operation(operationId = "listMails", summary = "Mailbox, newest first; q is a full-text search")
    public Page<MailSummary> list(@RequestParam(required = false) MailDirection direction,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String cursor) {
        return mails.list(direction, q, Cursor.clampLimit(limit), Cursor.decode(cursor));
    }

    @GetMapping("/api/mails/{id}")
    @Operation(operationId = "getMail")
    public MailDetail get(@PathVariable long id) {
        return mails.detail(id).orElseThrow(() -> ApiException.notFound("Mail", id));
    }

    @GetMapping("/api/attachments/{id}")
    @Operation(operationId = "downloadAttachment", summary = "Raw attachment bytes",
            responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/octet-stream",
                    schema = @Schema(type = "string", format = "binary"))))
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        MailQueries.AttachmentContent a = mails.attachmentContent(id)
                .orElseThrow(() -> ApiException.notFound("Attachment", id));
        MediaType type;
        try {
            type = a.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(a.contentType());
        } catch (IllegalArgumentException e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(a.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(a.filename(), StandardCharsets.UTF_8).build().toString())
                .body(a.content());
    }
}
