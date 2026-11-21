package ai.aeiou.forwarder.tariff;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.pricing.PricingService;
import ai.aeiou.forwarder.reference.Carrier;
import ai.aeiou.forwarder.reference.ReferenceData;
import ai.aeiou.forwarder.reference.ReferenceDataSeeder;
import ai.aeiou.forwarder.tariff.parse.TariffParsers;
import ai.aeiou.forwarder.tariff.parse.TariffSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports the start-kit tariffs once (source {@code kit}, status {@code active}) and loads the pricing snapshot.
 * Idempotent: a file already imported is skipped. Scanned sheets (images) are read from a hand-verified
 * transcription on the classpath, {@code tariffs/<name without -pN>.json}.
 */
@Component
@Order(ReferenceDataSeeder.ORDER + 10)
public class TariffLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TariffLoader.class);

    private final Path startKitDir;
    private final TariffParsers parsers;
    private final TariffRepository repository;
    private final TariffAdminService admin;
    private final ReferenceData referenceData;
    private final PricingService pricing;
    private final EventLog events;
    private final JsonMapper json;

    public TariffLoader(@Value("${app.start-kit-dir}") Path startKitDir, TariffParsers parsers, TariffRepository repository,
                        TariffAdminService admin, ReferenceData referenceData, PricingService pricing, EventLog events, JsonMapper json) {
        this.startKitDir = startKitDir;
        this.parsers = parsers;
        this.repository = repository;
        this.admin = admin;
        this.referenceData = referenceData;
        this.pricing = pricing;
        this.events = events;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        importKit();
        pricing.reload();
    }

    /** @return number of tariffs newly imported */
    public int importKit() {
        if (!Files.isDirectory(startKitDir)) {
            log.warn("Start kit directory {} not found; no kit tariffs imported", startKitDir.toAbsolutePath());
            return 0;
        }
        int imported = 0;
        for (Map.Entry<String, List<Path>> doc : kitDocuments().entrySet()) {
            String sourceFile = doc.getKey();
            if (repository.kitFileImported(sourceFile)) continue;
            try {
                ParsedTariff parsed = canonical(read(doc.getValue()));
                List<String> problems = TariffSanityCheck.structural(parsed);
                TariffStatus status = problems.isEmpty() ? TariffStatus.active : TariffStatus.pending_review;
                StoredTariff stored = repository.find(repository.insert(parsed, TariffAdminService.versionOf(parsed), "kit", null,
                        sourceFile, status)).orElseThrow();
                Map<String, Object> payload = TariffAdminService.payload(stored);
                payload.put("problems", problems);
                events.append(null, problems.isEmpty() ? "tariff.ingested" : "tariff.rejected", Actor.system,
                        "Kit tariff " + stored.airlineName() + " " + stored.version() + " (" + stored.laneCount() + " lanes) " + status, payload);
                imported++;
            } catch (DuplicateKeyException e) {
                log.info("Kit tariff {} imported concurrently by another instance", sourceFile);
            } catch (TariffParseException | IOException | RuntimeException e) {
                log.error("Kit tariff {} could not be imported: {}", sourceFile, e.getMessage());
                events.append(null, "tariff.rejected", Actor.system, "Kit tariff " + sourceFile + " could not be read",
                        Map.of("file", sourceFile, "error", String.valueOf(e.getMessage())));
            }
        }
        log.info("Imported {} kit tariffs from {}", imported, startKitDir);
        return imported;
    }

    /** Tariff files keyed by source name; the pages of a scanned sheet are grouped into one document. */
    private Map<String, List<Path>> kitDocuments() {
        Map<String, List<Path>> docs = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(startKitDir)) {
            files.filter(p -> p.getFileName().toString().contains("tariff")).sorted().forEach(p -> {
                String name = p.getFileName().toString();
                TariffSource.Format format = new TariffSource(name, null, new byte[0]).format();
                String key = format == TariffSource.Format.IMAGE ? scanBaseName(name) + ".scan" : name;
                docs.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            });
        } catch (IOException e) {
            log.error("Cannot list start kit {}: {}", startKitDir, e.getMessage());
        }
        return docs;
    }

    private ParsedTariff read(List<Path> files) throws IOException, TariffParseException {
        Path first = files.getFirst();
        String name = first.getFileName().toString();
        TariffSource source = new TariffSource(name, null, Files.readAllBytes(first));
        if (source.format() != TariffSource.Format.IMAGE) return parsers.parse(source);
        ClassPathResource transcription = new ClassPathResource("tariffs/" + scanBaseName(name) + ".json");
        if (!transcription.exists()) throw new TariffParseException(List.of("Scanned sheet " + name + " has no transcription"));
        try (InputStream in = transcription.getInputStream()) {
            return json.readValue(in, ParsedTariff.class);
        }
    }

    private ParsedTariff canonical(ParsedTariff t) {
        Optional<Carrier> carrier = referenceData.airlineByName(t.airlineName());
        return carrier.map(c -> t.withAirline(c.name(), t.airlineCode() != null ? t.airlineCode() : c.code())).orElse(t);
    }

    static String scanBaseName(String filename) {
        return filename.replaceFirst("(?i)(-p(age)?\\d+)?\\.(jpe?g|png|tiff?|gif|bmp|webp)$", "");
    }
}
