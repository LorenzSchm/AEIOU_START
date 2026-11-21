package ai.aeiou.forwarder.mail;

import ai.aeiou.forwarder.config.AppProperties;
import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.fiveu.ApiResponse;
import ai.aeiou.forwarder.fiveu.FiveUClient;
import ai.aeiou.forwarder.fiveu.FiveUClient.SendAttachment;
import ai.aeiou.forwarder.fiveu.FiveUClient.SendRequest;
import ai.aeiou.forwarder.fiveu.FiveUMessage;
import ai.aeiou.forwarder.mail.MailRepository.NewAttachment;
import ai.aeiou.forwarder.mail.MailRepository.NewMail;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link MailGateway} over the 5U mailbox. With {@code app.mail.send-enabled=false} (the default)
 * nothing leaves the process: the mail is stored as a dry run with a negative external id.
 *
 * <p>Real sends pass a token bucket (5U allows 60 mails/min) and back off on 429 using
 * {@code Retry-After}. Outgoing mails inherit the thread of the mail they answer.
 */
@Service
public class JdbcMailGateway implements MailGateway {

    private static final Logger log = LoggerFactory.getLogger(JdbcMailGateway.class);
    private static final int MAX_SEND_ATTEMPTS = 4;

    private final MailRepository mails;
    private final FiveUClient fiveU;
    private final EventLog events;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final AppProperties props;
    private final SendRateLimiter limiter = new SendRateLimiter(5, 55);

    public JdbcMailGateway(MailRepository mails, FiveUClient fiveU, EventLog events, TransactionTemplate tx,
                           JsonMapper json, AppProperties props) {
        this.mails = mails;
        this.fiveU = fiveU;
        this.events = events;
        this.tx = tx;
        this.json = json;
        this.props = props;
    }

    @Override
    public StoredMail send(Long sessionId, OutgoingMail mail) {
        Optional<StoredMail> parent = mail.inReplyToExt() == null ? Optional.empty()
                : mails.findByExternalId(mail.inReplyToExt());
        List<Long> references = parent.map(JdbcMailGateway::referencesForReply).orElse(List.of());
        Long threadRoot = parent.map(p -> p.threadRootExt() != null ? p.threadRootExt() : p.externalId()).orElse(null);

        if (!props.mail().sendEnabled()) {
            long externalId = -mails.nextDryRunId();
            String raw = json.writeValueAsString(Map.of("dryRun", true));
            return store(sessionId, mail, externalId, references, threadRoot == null ? externalId : threadRoot,
                    OffsetDateTime.now(ZoneOffset.UTC), raw, true);
        }

        FiveUMessage sent = sendWithBackoff(sessionId, mail);
        List<Long> refs = sent.references().isEmpty() ? references : sent.references();
        long root = threadRoot != null ? threadRoot : sent.threadRootId();
        return store(sessionId, mail, sent.id(), refs, root, sent.sentAt(), json.writeValueAsString(sent.raw()), false);
    }

    @Override
    public Optional<StoredMail> findByExternalId(long externalId) {
        return mails.findByExternalId(externalId);
    }

    @Override
    public Optional<StoredMail> findById(long id) {
        return mails.findById(id);
    }

    @Override
    public byte[] attachmentContent(long attachmentId) {
        return mails.attachmentContent(attachmentId);
    }

    private FiveUMessage sendWithBackoff(Long sessionId, OutgoingMail mail) {
        List<SendAttachment> attachments = attachments(mail).stream()
                .map(a -> new SendAttachment(a.filename(), a.contentType(), a.content()))
                .toList();
        SendRequest request = new SendRequest(mail.to(), mail.subject(), mail.body(), mail.inReplyToExt(), attachments);
        ApiResponse<FiveUMessage> response = null;
        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            try {
                limiter.acquire();
                response = fiveU.send(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MailSendException(0, "interrupted while waiting for the send limit");
            } catch (ResourceAccessException e) {
                // Never retry blindly: the mail may have been sent. Surface it to the caller.
                throw failed(sessionId, mail, 0, e.getMessage());
            }
            if (response.isSuccess() && response.body() != null) return response.body();
            if (!response.isRateLimited()) break;
            Duration pause = Duration.ofSeconds(response.retryAfterSeconds().orElse(1 << attempt));
            log.warn("5U send limit hit, backing off {}s (attempt {})", pause.toSeconds(), attempt);
            limiter.backOff(pause);
        }
        throw failed(sessionId, mail, response == null ? 0 : response.status(),
                response == null ? "no response" : response.rawBody());
    }

    private MailSendException failed(Long sessionId, OutgoingMail mail, int status, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("to", mail.to());
        payload.put("subject", mail.subject());
        payload.put("status", status);
        payload.put("detail", detail == null ? "" : detail);
        events.append(sessionId, "mail.out.failed", Actor.system,
                "Sending to " + mail.to() + " failed (HTTP " + status + ")", payload);
        return new MailSendException(status, "Send to " + mail.to() + " failed with " + status + ": " + detail);
    }

    private StoredMail store(Long sessionId, OutgoingMail mail, long externalId, List<Long> references,
                             long threadRoot, OffsetDateTime sentAt, String raw, boolean dryRun) {
        NewMail row = new NewMail(externalId, "out", props.fiveu().mailboxAddress(), mail.to(), mail.subject(),
                mail.body(), mail.inReplyToExt(), references, threadRoot, sessionId, sentAt, raw);
        return tx.execute(status -> {
            long id = mails.insert(row).orElseThrow(() -> new IllegalStateException("mail " + externalId + " already stored"));
            for (OutgoingMail.Attachment a : attachments(mail)) {
                mails.insertAttachment(id, new NewAttachment(null, a.filename(), a.contentType(),
                        (long) a.content().length, a.content()));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dryRun", dryRun);
            payload.put("externalId", externalId);
            payload.put("to", mail.to());
            payload.put("subject", mail.subject());
            payload.put("inReplyToExt", mail.inReplyToExt());
            String summary = (dryRun ? "Dry run: mail to " : "Mail to ") + mail.to() + ": " + mail.subject();
            events.append(sessionId, id, "mail.out", Actor.system, summary, payload);
            return mails.findById(id).orElseThrow();
        });
    }

    private static List<OutgoingMail.Attachment> attachments(OutgoingMail mail) {
        return mail.attachments() == null ? List.of() : mail.attachments();
    }

    /** A reply's references: the parent's references followed by the parent itself. */
    private static List<Long> referencesForReply(StoredMail parent) {
        List<Long> refs = new ArrayList<>(parent.referencesExt());
        refs.add(parent.externalId());
        return List.copyOf(refs);
    }
}
