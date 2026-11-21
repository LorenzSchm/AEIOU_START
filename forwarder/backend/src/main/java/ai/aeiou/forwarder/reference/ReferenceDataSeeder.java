package ai.aeiou.forwarder.reference;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upserts carrier-list.csv and pickup_zones.csv from the start kit on every startup (idempotent),
 * then refreshes {@link ReferenceData}. Airports come from the Flyway seed (V3).
 */
@Component
@Order(ReferenceDataSeeder.ORDER)
public class ReferenceDataSeeder implements ApplicationRunner {

    public static final int ORDER = 10;
    private static final Logger log = LoggerFactory.getLogger(ReferenceDataSeeder.class);
    private static final CSVFormat CSV = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).get();

    private final JdbcClient jdbc;
    private final ReferenceData referenceData;
    private final Path startKitDir;

    public ReferenceDataSeeder(JdbcClient jdbc, ReferenceData referenceData, @Value("${app.start-kit-dir}") Path startKitDir) {
        this.jdbc = jdbc;
        this.referenceData = referenceData;
        this.startKitDir = startKitDir;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        seed();
    }

    public void seed() throws IOException {
        Path carriers = startKitDir.resolve("carrier-list.csv");
        Path zones = startKitDir.resolve("pickup_zones.csv");
        if (Files.isReadable(carriers)) {
            log.info("Seeded {} carriers from {}", upsertCarriers(carriers), carriers);
        } else {
            log.warn("No carrier list at {}; carrier table left unchanged", carriers.toAbsolutePath());
        }
        if (Files.isReadable(zones)) {
            log.info("Seeded {} pickup zones from {}", upsertZones(zones), zones);
        } else {
            log.warn("No pickup zones at {}; pickup_zone table left unchanged", zones.toAbsolutePath());
        }
        referenceData.refresh();
    }

    private int upsertCarriers(Path file) throws IOException {
        int count = 0;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (CSVRecord r : CSV.parse(in)) {
                String[] serves = Arrays.stream(r.get("serves").trim().split("\\s+"))
                        .filter(s -> !s.isBlank())
                        .map(s -> s.toUpperCase(Locale.ROOT))
                        .toArray(String[]::new);
                jdbc.sql("""
                                INSERT INTO carrier (name, kind, email, serves) VALUES (:name, :kind, :email, :serves)
                                ON CONFLICT (email) DO UPDATE SET name = EXCLUDED.name, kind = EXCLUDED.kind, serves = EXCLUDED.serves
                                """)
                        .param("name", r.get("name"))
                        .param("kind", r.get("kind").toLowerCase(Locale.ROOT))
                        .param("email", r.get("email").toLowerCase(Locale.ROOT))
                        .param("serves", serves)
                        .update();
                count++;
            }
        }
        return count;
    }

    private int upsertZones(Path file) throws IOException {
        int count = 0;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (CSVRecord r : CSV.parse(in)) {
                jdbc.sql("""
                                INSERT INTO pickup_zone (zone, area, airport) VALUES (:zone, :area, :airport)
                                ON CONFLICT (zone) DO UPDATE SET area = EXCLUDED.area, airport = EXCLUDED.airport
                                """)
                        .param("zone", r.get("zone"))
                        .param("area", r.get("area"))
                        .param("airport", r.get("airport").toUpperCase(Locale.ROOT))
                        .update();
                count++;
            }
        }
        return count;
    }
}
