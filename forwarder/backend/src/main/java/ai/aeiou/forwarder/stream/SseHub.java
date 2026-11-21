package ai.aeiou.forwarder.stream;

import ai.aeiou.forwarder.api.dto.EventItem;
import ai.aeiou.forwarder.api.dto.SessionSummary;
import ai.aeiou.forwarder.api.query.SessionQueries;
import ai.aeiou.forwarder.event.EventAppended;
import ai.aeiou.forwarder.event.SessionChanged;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-process fan-out of live updates to every connected control panel (ARCHITECTURE.md §9).
 * <ul>
 *   <li>{@code event: event} for every {@link EventAppended},</li>
 *   <li>{@code event: session} with a fresh {@link SessionSummary} after {@link SessionChanged}; bursts are
 *       coalesced per session and loaded with one query every {@link #COALESCE_WINDOW},</li>
 *   <li>{@code event: ping} every {@link #HEARTBEAT}.</li>
 * </ul>
 * Listeners never block the publishing thread: frames are serialized once and queued on a per-client virtual
 * thread, so a slow client only delays itself. Clients that fail or fall too far behind are dropped.
 */
@Component
public class SseHub implements DisposableBean {

    static final Duration HEARTBEAT = Duration.ofSeconds(15);
    static final Duration COALESCE_WINDOW = Duration.ofMillis(150);
    private static final int MAX_BACKLOG = 1_000;
    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final SessionQueries sessions;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Set<Long> changedSessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("sse-hub-timer").daemon().factory());

    public SseHub(SessionQueries sessions) {
        this.sessions = sessions;
        long ms = HEARTBEAT.toMillis();
        timer.scheduleAtFixedRate(this::heartbeat, ms, ms, TimeUnit.MILLISECONDS);
    }

    /** Registers a new client; the emitter never times out and is removed when it completes or fails. */
    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(0L);
        Client client = new Client(emitter);
        clients.add(client);
        emitter.onCompletion(() -> drop(client));
        emitter.onTimeout(() -> drop(client));
        emitter.onError(e -> drop(client));
        client.enqueue(ping());  // flushes headers through proxies right away
        return emitter;
    }

    public int clientCount() {
        return clients.size();
    }

    @EventListener
    public void onEventAppended(EventAppended e) {
        if (clients.isEmpty()) return;
        broadcast(frame(SseEmitter.event().id(Long.toString(e.id())).name("event")
                .data(EventItem.of(e), MediaType.APPLICATION_JSON)));
    }

    @EventListener
    public void onSessionChanged(SessionChanged e) {
        changedSessions.add(e.sessionId());
        if (flushScheduled.compareAndSet(false, true)) {
            timer.schedule(() -> Thread.startVirtualThread(this::flushSessions),
                    COALESCE_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void flushSessions() {
        // Reset first: a change arriving while we load schedules the next flush instead of being lost.
        flushScheduled.set(false);
        List<Long> ids = new ArrayList<>();
        for (Iterator<Long> it = changedSessions.iterator(); it.hasNext(); ) {
            ids.add(it.next());
            it.remove();
        }
        if (ids.isEmpty() || clients.isEmpty()) return;
        try {
            for (SessionSummary s : sessions.summaries(ids)) {
                broadcast(frame(SseEmitter.event().name("session").data(s, MediaType.APPLICATION_JSON)));
            }
        } catch (RuntimeException ex) {
            log.warn("Could not load changed sessions {} for SSE", ids, ex);
        }
    }

    private void heartbeat() {
        if (!clients.isEmpty()) broadcast(ping());
    }

    private static Set<DataWithMediaType> ping() {
        return frame(SseEmitter.event().name("ping").data("{}"));  // plain text, written verbatim
    }

    private static Set<DataWithMediaType> frame(SseEmitter.SseEventBuilder builder) {
        return builder.build();
    }

    private void broadcast(Set<DataWithMediaType> frame) {
        for (Client c : clients) c.enqueue(frame);
    }

    private void drop(Client client) {
        if (clients.remove(client)) client.close();
    }

    @Override
    public void destroy() {
        timer.shutdownNow();
        for (Client c : clients) {
            drop(c);
            c.emitter.complete();
        }
    }

    /** One connected browser with its own ordered send queue. */
    private final class Client {

        private final SseEmitter emitter;
        private final ExecutorService out = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("sse-client").factory());
        private final AtomicInteger backlog = new AtomicInteger();

        Client(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void enqueue(Set<DataWithMediaType> frame) {
            if (backlog.incrementAndGet() > MAX_BACKLOG) {
                log.info("Dropping SSE client that fell {} frames behind", MAX_BACKLOG);
                drop(this);
                emitter.complete();
                return;
            }
            try {
                out.execute(() -> {
                    backlog.decrementAndGet();
                    try {
                        emitter.send(frame);
                    } catch (IOException | IllegalStateException e) {
                        drop(this);  // client went away; the container completes the async request
                    }
                });
            } catch (RejectedExecutionException closed) {
                // client already dropped
            }
        }

        void close() {
            out.shutdown();
        }
    }
}
