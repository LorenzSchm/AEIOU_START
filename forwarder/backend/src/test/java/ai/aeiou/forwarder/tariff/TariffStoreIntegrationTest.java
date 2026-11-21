package ai.aeiou.forwarder.tariff;

import static org.assertj.core.api.Assertions.assertThat;

import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.pricing.PriceQuote;
import ai.aeiou.forwarder.pricing.FxRates;
import ai.aeiou.forwarder.pricing.SheetPricingService;
import ai.aeiou.forwarder.pricing.Shipment;
import ai.aeiou.forwarder.reference.Carrier;
import ai.aeiou.forwarder.reference.ReferenceData;
import ai.aeiou.forwarder.reference.ReferenceDataSeeder;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.tariff.parse.DocxTariffParser;
import ai.aeiou.forwarder.tariff.parse.PdfTariffParser;
import ai.aeiou.forwarder.tariff.parse.TariffParsers;
import ai.aeiou.forwarder.tariff.parse.TextTariffParser;
import ai.aeiou.forwarder.tariff.parse.XlsxTariffParser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "app.start-kit-dir=../../start-kit")
@Import({TariffStoreIntegrationTest.Config.class, ReferenceData.class, ReferenceDataSeeder.class, TariffRepository.class,
        TariffParsers.class, XlsxTariffParser.class, DocxTariffParser.class, PdfTariffParser.class, TextTariffParser.class,
        TariffAdminService.class, TariffLoader.class, SheetPricingService.class, FxRates.class})
class TariffStoreIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        MutableClock clock() {
            return new MutableClock(LocalDate.of(2026, 11, 21));
        }
    }

    @MockitoBean EventLog events;
    @MockitoBean SessionService sessions;
    @MockitoBean MailGateway mailGateway;

    @Autowired ReferenceDataSeeder seeder;
    @Autowired ReferenceData referenceData;
    @Autowired TariffLoader loader;
    @Autowired TariffRepository repository;
    @Autowired TariffAdminService admin;
    @Autowired SheetPricingService pricing;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;

    @Test
    void seedsReferenceDataIdempotently() throws Exception {
        seeder.seed();
        seeder.seed();

        assertThat(jdbc.sql("SELECT count(*) FROM pickup_zone").query(Long.class).single()).isEqualTo(32);
        assertThat(referenceData.airlinesServing("DXB")).extracting(Carrier::name)
                .contains("Danube Wings Cargo", "Elbflug Air Freight", "Orion Cargo Air");
        assertThat(referenceData.originAirportForZip("80331")).contains("MUC");
        assertThat(referenceData.truckersServingZip("60311")).extracting(Carrier::name)
                .containsExactlyInAnyOrder("Taunusfracht", "Isarwelle Spedition", "Domblick Kurierdienst", "Westfalenroute", "Bodenseefracht");
        assertThat(referenceData.carrierByEmail("Helix Desk <HELIX-AIR-FREIGHT@hackloop.mail>")).map(Carrier::name).contains("Helix Air Freight");
        assertThat(referenceData.airlineByName("helix")).map(Carrier::name).contains("Helix Air Freight");
        assertThat(referenceData.airlineByName("Cobalt Skylift GmbH")).map(Carrier::name).contains("Cobalt Skylift");
        assertThat(referenceData.iataForCityOrCode("Frankfurt am Main")).contains("FRA");
        assertThat(referenceData.iataForCityOrCode("Düsseldorf")).contains("DUS");

        Set<String> missing = new HashSet<>();
        for (Carrier c : referenceData.airlines()) {
            c.serves().stream().filter(iata -> referenceData.airport(iata).isEmpty()).forEach(missing::add);
        }
        for (String zoneAirport : jdbc.sql("SELECT DISTINCT airport FROM pickup_zone").query(String.class).list()) {
            if (referenceData.airport(zoneAirport).isEmpty()) missing.add(zoneAirport);
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void importsTheKitOnceAndPricesFromIt() throws Exception {
        // the ApplicationRunners already seeded and imported the kit at startup; doing it again changes nothing
        seeder.seed();
        assertThat(loader.importKit()).isZero();
        pricing.reload();

        List<StoredTariff> tariffs = admin.list();
        assertThat(tariffs).hasSize(10).allMatch(t -> t.status() == TariffStatus.active && t.source().equals("kit"));
        assertThat(tariffs).extracting(StoredTariff::laneCount).containsExactlyInAnyOrder(21, 9, 19, 12, 18, 14, 11, 13, 26, 15);
        for (ai.aeiou.forwarder.tariff.RateCard card : repository.activeRateCards()) {
            assertThat(referenceData.airport(card.lane().origin())).as(card.lane().origin()).isPresent();
            assertThat(referenceData.airport(card.lane().destination())).as(card.lane().destination()).isPresent();
        }

        // FRA-DXB, 95 kg: Elbflug 95×2.10+95×1.11 = 304.95 → at 100: 193.00+111.00 = 304.00; Danube → 339.00
        List<PriceQuote> quotes = pricing.priceFromSheets(new Shipment("FRA", "DXB", BigDecimal.valueOf(95), null));
        assertThat(quotes).extracting(PriceQuote::airlineName).containsExactly("Elbflug Air Freight", "Danube Wings Cargo");
        assertThat(quotes).extracting(q -> q.totalEur().toPlainString()).containsExactly("304.00", "339.00");
        assertThat(quotes.getFirst().breakdown()).containsEntry("usedBreakFromKg", 100).containsEntry("fxSource", "none");
        assertThat(pricing.sheetAirlines("fra", "dxb")).containsExactlyInAnyOrder("Elbflug Air Freight", "Danube Wings Cargo");
    }

    @Test
    void nextMonthsTariffTakesOverFromItsValidFrom() throws Exception {
        seeder.seed();
        loader.importKit();
        pricing.reload();
        Shipment shipment = new Shipment("MUC", "TPE", BigDecimal.valueOf(10), null);
        // November Danube MUC-TPE: 10×4.10 = 41 < 69 → 69.00 + 10×1.36 = 13.60 → 82.60
        assertThat(pricing.priceFromSheets(shipment).getFirst().total()).isEqualByComparingTo("82.60");

        TariffAdminService.TariffDetail november = admin.inForce("Danube Wings Cargo").orElseThrow();
        ParsedLane old = november.lanes().stream().filter(l -> l.key().equals("MUC-TPE")).findFirst().orElseThrow();
        ParsedLane cheaper = new ParsedLane("MUC", "TPE", 5, new BigDecimal("50.00"), old.breaks(), old.fuelPerKg(), old.securityPerKg(), List.of());
        ParsedTariff december = new ParsedTariff("Danube Wings Cargo", null, "EUR", null,
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), List.of(cheaper), List.of());
        StoredTariff stored = admin.store(december, "2026-12-mail1", "mail", null, "dec.xlsx", TariffStatus.active);

        // still November: the old version stays in force and active
        assertThat(pricing.priceFromSheets(shipment).getFirst().total()).isEqualByComparingTo("82.60");
        assertThat(repository.find(november.tariff().id()).orElseThrow().status()).isEqualTo(TariffStatus.active);

        clock.set(LocalDate.of(2026, 12, 1));
        // December: 50.00 + 13.60 = 63.60, and the November version is superseded on reload
        assertThat(pricing.priceFromSheets(shipment).getFirst().total()).isEqualByComparingTo("63.60");
        pricing.reload();
        assertThat(repository.find(november.tariff().id()).orElseThrow().status()).isEqualTo(TariffStatus.superseded);
        assertThat(admin.inForce("Danube Wings Cargo").orElseThrow().tariff().id()).isEqualTo(stored.id());
        // lanes only in the old sheet are gone with it
        assertThat(pricing.sheetAirlines("FRA", "EZE")).doesNotContain("Danube Wings Cargo");
    }

    /** A clock the test can move; pricing reads "today" from it. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(LocalDate day) {
            set(day);
        }

        void set(LocalDate day) {
            now = day.atTime(12, 0).toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
