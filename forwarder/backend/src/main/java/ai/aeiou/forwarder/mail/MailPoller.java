package ai.aeiou.forwarder.mail;

import ai.aeiou.forwarder.config.CoreConfig;
import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.fiveu.FiveUClient;
import ai.aeiou.forwarder.fiveu.FiveUClient.MailboxPage;
import ai.aeiou.forwarder.fiveu.FiveUMessage;
import ai.aeiou.forwarder.mail.MailRepository.NewAttachment;
import ai.aeiou.forwarder.router.Router;
import ai.aeiou.forwarder.settings.SettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Polls the 5U mailbox (only when {@code app.poller.enabled=true}; exactly one instance may poll).
 * The runtime setting {@code pollingEnabled} pauses polling without a restart; it can never
 * enable a poller that the environment flag disabled.
 *
 * <p>Each tick reads {@code poller_state('mailbox')}, pages through {@code since_id} until it stops
 * advancing, stores every mail idempotently with its attachments, and hands each newly stored mail
 * to the {@link Router} on its own virtual thread, so one slow mail (LLM call) never delays the others.
 * The cursor moves past a mail only in the transaction that stored it.
 */
@Component
@ConditionalOnProperty(prefix = "app.poller", name = "enabled", havingValue = "true")
public class MailPoller {

    private static final Logger log = LoggerFactory.getLogger(MailPoller.class);

    private final FiveUClient fiveU;
    private final MailRepository mails;
    private final Router router;
    private final EventLog events;
    private final JsonMapper json;
    private final Executor routingExecutor;
    private final SettingsService settings;
    private final ReentrantLock running = new ReentrantLock();

    public MailPoller(FiveUClient fiveU, MailRepository mails, Router router, EventLog events, JsonMapper json,
                      @Qualifier(CoreConfig.MAIL_ROUTING_EXECUTOR) Executor routingExecutor, SettingsService settings) {
        this.fiveU = fiveU;
        this.mails = mails;
        this.router = router;
        this.events = events;
        this.json = json;
        this.routingExecutor = routingExecutor;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${app.poller.interval-ms:1000}")
    public void tick() {
        if (!settings.getBool("pollingEnabled", true)) return; // paused from the UI
        if (!running.tryLock()) return; // previous tick still busy
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.warn("Mailbox poll failed: {}", e.getMessage());
        } finally {
            running.unlock();
        }
    }

    /** Polls until the mailbox is drained. Returns the number of newly stored mails. */
    public int pollOnce() {
        long sinceId = mails.sinceId();
        int stored = 0;
        while (true) {
            MailboxPage page = fiveU.poll(sinceId, FiveUClient.MAX_POLL_LIMIT);
            for (FiveUMessage msg : page.messages()) {
                if (ingest(msg)) stored++;
            }
            if (page.nextSinceId() <= sinceId) break;
            mails.advanceSinceId(page.nextSinceId());
            sinceId = page.nextSinceId();
        }
        return stored;
    }

    private boolean ingest(FiveUMessage msg) {
        if (mails.existsByExternalId(msg.id())) {
            mails.advanceSinceId(msg.id());
            return false;
        }
        List<NewAttachment> attachments = download(msg); // outside the transaction: no connection held
        Optional<StoredMail> stored = mails.storeIncoming(msg, json.writeValueAsString(msg.raw()), attachments);
        stored.ifPresent(mail -> routingExecutor.execute(() -> routeSafely(mail)));
        return stored.isPresent();
    }

    private List<NewAttachment> download(FiveUMessage msg) {
        List<NewAttachment> out = new ArrayList<>();
        for (FiveUMessage.Attachment a : msg.attachments()) {
            byte[] content = null;
            try {
                content = fiveU.downloadAttachment(msg.id(), a.id());
            } catch (RuntimeException e) {
                // Store the mail anyway; a stuck attachment must not block the mailbox.
                log.warn("Attachment {} of mail {} could not be downloaded: {}", a.id(), msg.id(), e.getMessage());
                events.append(null, "system.error", Actor.system,
                        "Attachment " + a.filename() + " of mail " + msg.id() + " could not be downloaded",
                        Map.of("externalId", msg.id(), "attachmentId", a.id(), "error", String.valueOf(e.getMessage())));
            }
            out.add(new NewAttachment(a.id(), a.filename(), a.contentType(), a.size(), content));
        }
        return out;
    }

    private void routeSafely(StoredMail mail) {
        try {
            router.route(mail);
        } catch (RuntimeException e) {
            log.error("Routing mail {} failed", mail.id(), e);
            events.append(mail.sessionId(), mail.id(), "system.error", Actor.system,
                    "Routing failed for mail " + mail.externalId() + ": " + e.getMessage(),
                    Map.of("error", e.getClass().getName(), "message", String.valueOf(e.getMessage())));
        }
    }
}
