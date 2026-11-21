package ai.aeiou.forwarder.tariff;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.llm.LlmClient;
import ai.aeiou.forwarder.llm.LlmClient.ChatMessage;
import ai.aeiou.forwarder.llm.LlmClient.LlmRequest;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.reference.Carrier;
import ai.aeiou.forwarder.reference.ReferenceData;
import ai.aeiou.forwarder.router.MailHandler;
import ai.aeiou.forwarder.router.MailType;
import ai.aeiou.forwarder.router.RoutedMail;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.session.SessionStatus;
import ai.aeiou.forwarder.settings.SettingsService;
import ai.aeiou.forwarder.tariff.parse.TariffParsers;
import ai.aeiou.forwarder.tariff.parse.TariffSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Handles airlines' monthly tariff mails (ARCHITECTURE.md §4.3): parse each attachment deterministically,
 * fall back to one LLM extraction if no parser fits, sanity-check against the version in force, then activate
 * or park it as pending_review for a human. Images are never guessed at: they go straight to a human.
 */
@Component
public class TariffIngestionHandler implements MailHandler {

    /** Setting: activate LLM-extracted tariffs automatically when they pass the sanity checks. */
    public static final String AUTO_ACTIVATE_LLM = "tariffAutoActivateLlm";
    private static final Logger log = LoggerFactory.getLogger(TariffIngestionHandler.class);
    private static final int MAX_LLM_CHARS = 60_000;

    private final MailGateway mailGateway;
    private final TariffParsers parsers;
    private final TariffAdminService admin;
    private final ReferenceData referenceData;
    private final LlmClient llm;
    private final SessionService sessions;
    private final SettingsService settings;
    private final EventLog events;
    private final String extractModel;

    public TariffIngestionHandler(MailGateway mailGateway, TariffParsers parsers, TariffAdminService admin, ReferenceData referenceData,
                                  LlmClient llm, SessionService sessions, SettingsService settings, EventLog events,
                                  @Value("${app.llm.model-extract:gpt-4o-mini}") String extractModel) {
        this.mailGateway = mailGateway;
        this.parsers = parsers;
        this.admin = admin;
        this.referenceData = referenceData;
        this.llm = llm;
        this.sessions = sessions;
        this.settings = settings;
        this.events = events;
        this.extractModel = extractModel;
    }

    @Override
    public Set<MailType> supports() {
        return Set.of(MailType.TARIFF);
    }

    @Override
    public void handle(RoutedMail routed) {
        StoredMail mail = routed.mail();
        Long sessionId = routed.sessionId();
        List<String> needsHuman = new ArrayList<>();
        for (TariffSource source : sources(mail)) {
            try {
                Optional<String> problem = ingest(source, mail, sessionId);
                problem.ifPresent(needsHuman::add);
            } catch (RuntimeException e) {
                log.error("Tariff ingestion of {} failed", source.filename(), e);
                reject(sessionId, mail, source, "ingestion failed: " + e.getMessage(), Map.of());
                needsHuman.add(source.filename() + ": ingestion failed");
            }
        }
        if (sessionId == null) return;
        if (needsHuman.isEmpty()) sessions.setStatus(sessionId, SessionStatus.CLOSED);
        else sessions.flagNeedsHuman(sessionId, "Tariff needs review: " + String.join("; ", needsHuman));
    }

    /** @return a reason for a human when the tariff was not activated */
    private Optional<String> ingest(TariffSource source, StoredMail mail, Long sessionId) {
        if (source.format() == TariffSource.Format.IMAGE) {
            String reason = source.filename() + " is an image; transcribe it by hand";
            reject(sessionId, mail, source, reason, Map.of());
            return Optional.of(reason);
        }
        Extracted extracted = extract(source, sessionId);
        if (extracted.tariff() == null) {
            String reason = source.filename() + " could not be read: " + String.join("; ", extracted.problems());
            reject(sessionId, mail, source, reason, Map.of("parseProblems", extracted.problems()));
            return Optional.of(reason);
        }

        List<String> problems = new ArrayList<>();
        ParsedTariff tariff = identifyAirline(extracted.tariff(), mail, problems);
        Optional<TariffAdminService.TariffDetail> current = admin.inForce(tariff.airlineName());
        TariffSanityCheck.Result check = TariffSanityCheck.check(tariff,
                current.map(TariffAdminService.TariffDetail::tariff).orElse(null),
                current.map(TariffAdminService.TariffDetail::lanes).orElse(List.of()), admin.today());
        problems.addAll(check.problems());
        if (extracted.byLlm() && !settings.getBool(AUTO_ACTIVATE_LLM, true)) {
            problems.add("extracted by the LLM; auto-activation is off");
        }

        TariffStatus status = problems.isEmpty() ? TariffStatus.active : TariffStatus.pending_review;
        String version = TariffAdminService.versionOf(tariff) + "-mail" + mail.id();
        StoredTariff stored = admin.store(tariff, version, "mail", mail.id(), source.filename(), status);

        Map<String, Object> payload = TariffAdminService.payload(stored);
        payload.put("method", extracted.byLlm() ? "llm" : "parser");
        payload.put("diff", check.diff());
        payload.put("problems", problems);
        if (status == TariffStatus.active) {
            events.append(sessionId, mail.id(), "tariff.ingested", extracted.byLlm() ? Actor.llm : Actor.system,
                    "Tariff " + stored.airlineName() + " " + stored.version() + " active (" + stored.laneCount() + " lanes)", payload);
            return Optional.empty();
        }
        events.append(sessionId, mail.id(), "tariff.rejected", Actor.system,
                "Tariff " + stored.airlineName() + " " + stored.version() + " held for review: " + String.join("; ", problems), payload);
        return Optional.of(stored.airlineName() + " " + stored.version() + ": " + String.join("; ", problems));
    }

    private record Extracted(ParsedTariff tariff, boolean byLlm, List<String> problems) {}

    private Extracted extract(TariffSource source, Long sessionId) {
        List<String> problems = new ArrayList<>();
        try {
            return new Extracted(parsers.parse(source), false, List.of());
        } catch (TariffParseException e) {
            problems.addAll(e.problems());
        }
        String text = parsers.text(source);
        if (text.isBlank()) {
            problems.add("no text to extract from");
            return new Extracted(null, false, problems);
        }
        try {
            String content = "File: " + source.filename() + "\n\n" + (text.length() > MAX_LLM_CHARS ? text.substring(0, MAX_LLM_CHARS) : text);
            LlmTariffExtraction result = llm.structured(LlmRequest.of(sessionId, "tariff.extract", extractModel,
                    List.of(ChatMessage.system(LlmTariffExtraction.INSTRUCTIONS), ChatMessage.user(content))), LlmTariffExtraction.class).value();
            if (result == null) throw new IllegalStateException("empty LLM answer");
            return new Extracted(result.toParsedTariff(), true, problems);
        } catch (RuntimeException e) {
            problems.add("LLM extraction failed: " + e.getMessage());
            return new Extracted(null, false, problems);
        }
    }

    /** Names the tariff after the carrier list; a sheet naming another airline than its sender is suspicious. */
    private ParsedTariff identifyAirline(ParsedTariff t, StoredMail mail, List<String> problems) {
        Optional<Carrier> sender = referenceData.carrierByEmail(mail.fromAddr()).filter(Carrier::isAirline);
        Optional<Carrier> named = referenceData.airlineByName(t.airlineName());
        if (sender.isPresent() && named.isPresent() && sender.get().id() != named.get().id()) {
            problems.add("sheet names " + named.get().name() + " but was sent by " + sender.get().name());
        }
        Optional<Carrier> airline = sender.or(() -> named);
        if (airline.isEmpty()) {
            if (t.airlineName() == null || t.airlineName().isBlank()) {
                problems.add("airline unknown");
                return t.withAirline("Unknown airline <" + mail.fromAddr() + ">", t.airlineCode());
            }
            problems.add("airline " + t.airlineName() + " is not on the carrier list");
            return t;
        }
        Carrier c = airline.get();
        return t.withAirline(c.name(), t.airlineCode() != null ? t.airlineCode() : c.code());
    }

    private List<TariffSource> sources(StoredMail mail) {
        List<TariffSource> sources = new ArrayList<>();
        if (mail.attachments() != null) {
            for (StoredMail.AttachmentRef a : mail.attachments()) {
                sources.add(new TariffSource(a.filename(), a.contentType(), mailGateway.attachmentContent(a.id())));
            }
        }
        if (sources.isEmpty() && mail.body() != null && !mail.body().isBlank()) {
            sources.add(new TariffSource("mail-body.txt", "text/plain", mail.body().getBytes(StandardCharsets.UTF_8)));
        }
        return sources;
    }

    private void reject(Long sessionId, StoredMail mail, TariffSource source, String reason, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("file", source.filename());
        payload.put("reason", reason);
        events.append(sessionId, mail.id(), "tariff.rejected", Actor.system, "Tariff not ingested: " + reason, payload);
    }
}
