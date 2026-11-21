package ai.aeiou.forwarder.tariff;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.pricing.PricingService;
import ai.aeiou.forwarder.session.SessionService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Stores tariff versions and moves them through their lifecycle. Used by the kit loader, mail ingestion and
 * the REST API (review queue: activate / reject). Every activation reloads the in-memory pricing snapshot.
 */
@Service
public class TariffAdminService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final TariffRepository repository;
    private final PricingService pricing;
    private final EventLog events;
    private final SessionService sessions;
    private final MailGateway mail;
    private final Clock clock;

    public TariffAdminService(TariffRepository repository, PricingService pricing, EventLog events, SessionService sessions,
                              MailGateway mail, ObjectProvider<Clock> clock) {
        this.repository = repository;
        this.pricing = pricing;
        this.events = events;
        this.sessions = sessions;
        this.mail = mail;
        this.clock = clock.getIfAvailable(Clock::systemDefaultZone);
    }

    public record TariffDetail(StoredTariff tariff, List<ParsedLane> lanes) {}

    public List<StoredTariff> list() {
        return repository.list();
    }

    public Optional<TariffDetail> find(long id) {
        return repository.find(id).map(t -> new TariffDetail(t, repository.lanes(id)));
    }

    /** The airline's version in force today (latest valid_from on or before today, else the earliest upcoming one). */
    public Optional<TariffDetail> inForce(String airlineName) {
        LocalDate today = today();
        List<StoredTariff> active = repository.activeFor(airlineName);
        Optional<StoredTariff> started = active.stream()
                .filter(t -> t.validFrom() == null || !t.validFrom().isAfter(today))
                .findFirst();
        Optional<StoredTariff> chosen = started.or(() -> active.stream().min(Comparator.comparing(StoredTariff::validFrom)));
        return chosen.map(t -> new TariffDetail(t, repository.lanes(t.id())));
    }

    /** Inserts a version; an active one supersedes older versions once its valid_from is reached. */
    public StoredTariff store(ParsedTariff tariff, String version, String source, Long sourceMailId, String sourceFile, TariffStatus status) {
        long id = repository.insert(tariff, version, source, sourceMailId, sourceFile, status);
        if (status == TariffStatus.active) pricing.reload();
        return repository.find(id).orElseThrow();
    }

    /** Human approval of a pending tariff (REST: POST /api/tariffs/{id}/activate). */
    public StoredTariff activate(long id) {
        StoredTariff t = repository.find(id).orElseThrow(() -> new NoSuchElementException("Tariff " + id + " not found"));
        repository.setStatus(id, TariffStatus.active);
        pricing.reload();
        StoredTariff updated = repository.find(id).orElseThrow();
        Long sessionId = sessionOf(t);
        events.append(sessionId, t.sourceMailId(), "human.tariff_activated", Actor.human,
                "Tariff " + t.airlineName() + " " + t.version() + " activated", payload(updated));
        if (sessionId != null) sessions.clearNeedsHuman(sessionId);
        return updated;
    }

    /** Human rejection of a pending (or active) tariff (REST: POST /api/tariffs/{id}/reject). */
    public StoredTariff reject(long id) {
        StoredTariff t = repository.find(id).orElseThrow(() -> new NoSuchElementException("Tariff " + id + " not found"));
        repository.setStatus(id, TariffStatus.rejected);
        if (t.status() == TariffStatus.active) pricing.reload();
        StoredTariff updated = repository.find(id).orElseThrow();
        Long sessionId = sessionOf(t);
        events.append(sessionId, t.sourceMailId(), "tariff.rejected", Actor.human,
                "Tariff " + t.airlineName() + " " + t.version() + " rejected", payload(updated));
        if (sessionId != null) sessions.clearNeedsHuman(sessionId);
        return updated;
    }

    /** "2026-11" from the validity, or "undated". */
    public static String versionOf(ParsedTariff t) {
        return t.validFrom() == null ? "undated" : t.validFrom().format(MONTH);
    }

    static Map<String, Object> payload(StoredTariff t) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tariffId", t.id());
        p.put("airline", t.airlineName());
        p.put("version", t.version());
        p.put("source", t.source());
        p.put("status", t.status().name());
        p.put("lanes", t.laneCount());
        p.put("currency", t.currency());
        p.put("validFrom", t.validFrom() == null ? null : t.validFrom().toString());
        p.put("validTo", t.validTo() == null ? null : t.validTo().toString());
        return p;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    private Long sessionOf(StoredTariff t) {
        return t.sourceMailId() == null ? null : mail.findById(t.sourceMailId()).map(StoredMail::sessionId).orElse(null);
    }
}
