package ai.aeiou.forwarder.rfq;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.OutgoingMail;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.session.SessionStatus;
import ai.aeiou.forwarder.settings.SettingsService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Small in-test implementations of the other agents' contracts, backed by the test database where FKs need rows. */
final class RfqTestDoubles {

    private RfqTestDoubles() {}

    /** Stores every sent mail as a real {@code mail} row (bid_request.out_mail_id references it). Never sends. */
    static final class FakeMailGateway implements MailGateway {
        final List<OutgoingMail> sent = new CopyOnWriteArrayList<>();
        final AtomicLong externalIds = new AtomicLong(900_000);
        volatile Predicate<OutgoingMail> failWhen = m -> false;
        private final JdbcClient jdbc;
        private final Map<Long, StoredMail> byId = new ConcurrentHashMap<>();

        FakeMailGateway(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public StoredMail send(Long sessionId, OutgoingMail mail) {
            if (failWhen.test(mail)) throw new IllegalStateException("mailbox down");
            sent.add(mail);
            return store(sessionId, "out", "aeiou@hackloop.mail", mail.to(), mail.subject(), mail.body(), mail.inReplyToExt());
        }

        StoredMail store(Long sessionId, String direction, String from, String to, String subject, String body, Long inReplyTo) {
            long ext = externalIds.incrementAndGet();
            long id = jdbc.sql("""
                            INSERT INTO mail (external_id, direction, from_addr, to_addr, subject, body, in_reply_to_ext, session_id, sent_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now()) RETURNING id""")
                    .params(ext, direction, from, to, subject, body, inReplyTo, sessionId)
                    .query(Long.class).single();
            StoredMail m = new StoredMail(id, ext, direction, from, to, subject, body, inReplyTo, List.of(), null, sessionId,
                    OffsetDateTime.now(), List.of());
            byId.put(id, m);
            return m;
        }

        List<OutgoingMail> sentTo(String address) {
            return sent.stream().filter(m -> m.to().equals(address)).toList();
        }

        @Override
        public Optional<StoredMail> findByExternalId(long externalId) {
            return byId.values().stream().filter(m -> m.externalId() == externalId).findFirst();
        }

        @Override
        public Optional<StoredMail> findById(long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public byte[] attachmentContent(long attachmentId) {
            return new byte[0];
        }
    }

    /** One RFQ session with a real {@code claimQuote} guard. */
    static final class FakeSessions implements SessionService {
        final long id;
        final String customer;
        final OffsetDateTime createdAt = OffsetDateTime.now();
        volatile SessionStatus status = SessionStatus.NEW;
        volatile boolean needsHuman;
        volatile String needsHumanReason;
        volatile OffsetDateTime quotedAt;
        volatile BigDecimal quotedTotal;
        volatile String quotedCurrency;
        volatile String mode;
        final AtomicInteger claims = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        FakeSessions(long id, String customer) {
            this.id = id;
            this.customer = customer;
        }

        @Override
        public long create(String type, String customerAddr, String subject, OffsetDateTime deadlineAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SessionView> find(long sessionId) {
            if (sessionId != id) return Optional.empty();
            return Optional.of(new SessionView(id, "AEI-TEST" + id, "RFQ", status, customer, "RFQ", mode, needsHuman, true,
                    createdAt.plusMinutes(6), quotedAt, quotedTotal, quotedCurrency, false, createdAt));
        }

        @Override
        public Optional<SessionView> findByPublicRef(String publicRef) {
            return Optional.empty();
        }

        @Override
        public void setStatus(long sessionId, SessionStatus s) {
            status = s;
        }

        @Override
        public void setMode(long sessionId, String m) {
            mode = m;
        }

        @Override
        public void setType(long sessionId, String type, OffsetDateTime deadlineAt) {
        }

        @Override
        public void flagNeedsHuman(long sessionId, String reason) {
            needsHuman = true;
            needsHumanReason = reason;
        }

        @Override
        public void clearNeedsHuman(long sessionId) {
            needsHuman = false;
        }

        @Override
        public synchronized boolean claimQuote(long sessionId, BigDecimal total, String currency, BigDecimal totalEur) {
            claims.incrementAndGet();
            if (quotedAt != null) return false;
            quotedAt = OffsetDateTime.now();
            quotedTotal = total;
            quotedCurrency = currency;
            return true;
        }

        @Override
        public synchronized void releaseQuote(long sessionId) {
            releases.incrementAndGet();
            quotedAt = null;
        }

        @Override
        public void markBooked(long sessionId) {
        }
    }

    static final class RecordingEventLog implements EventLog {
        final List<String> kinds = new CopyOnWriteArrayList<>();
        final List<Object> payloads = new CopyOnWriteArrayList<>();
        private final AtomicLong ids = new AtomicLong();

        @Override
        public EventRecord append(Long sessionId, Long mailId, String kind, Actor actor, String summary, Object payload) {
            kinds.add(kind);
            payloads.add(payload);
            return new EventRecord(ids.incrementAndGet(), sessionId, mailId, kind, actor, summary, OffsetDateTime.now());
        }

        long count(String kind) {
            return kinds.stream().filter(kind::equals).count();
        }
    }

    /** Defaults from the caller, overridable per test. */
    static final class MapSettings implements SettingsService {
        final Map<String, Object> values = new ConcurrentHashMap<>();

        @Override
        public int getInt(String key, int defaultValue) {
            return values.containsKey(key) ? ((Number) values.get(key)).intValue() : defaultValue;
        }

        @Override
        public double getDouble(String key, double defaultValue) {
            return values.containsKey(key) ? ((Number) values.get(key)).doubleValue() : defaultValue;
        }

        @Override
        public boolean getBool(String key, boolean defaultValue) {
            return values.containsKey(key) ? (Boolean) values.get(key) : defaultValue;
        }

        @Override
        public String getString(String key, String defaultValue) {
            return values.containsKey(key) ? values.get(key).toString() : defaultValue;
        }

        @Override
        public void set(String key, Object value, String updatedBy) {
            values.put(key, value);
        }
    }
}
