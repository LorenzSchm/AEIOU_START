package ai.aeiou.forwarder.tariff.parse;

import static org.assertj.core.api.Assertions.assertThat;

import ai.aeiou.forwarder.tariff.ParsedLane;
import ai.aeiou.forwarder.tariff.ParsedTariff;
import ai.aeiou.forwarder.tariff.TariffSanityCheck;
import ai.aeiou.forwarder.tariff.WeightBreak;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses every real start-kit file. Lane counts and the sum of all printed amounts were cross-checked against an
 * independent extraction (pdftotext / openpyxl / raw docx XML), so a parser bug that shifts or drops a column fails here.
 */
class KitTariffParsingTest {

    private final TariffParsers parsers = TariffParsers.standard(AirportResolver.NONE);

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // file, airline, lanes, sum of minimum+rates+fuel+security, amounts, breaks
            "cobalt-skylift-tariff-2026-11.pdf,      Cobalt Skylift,         21, 2396.25, 210, 0 45 100 300 500 1000 2000",
            "helix-air-freight-tariff-2026-11.pdf,   Helix Air Freight,      12, 1459.28, 120, 0 45 100 250 500 1000 2000",
            "orion-cargo-air-tariff-2026-11.pdf,     Orion Cargo Air,        14, 1616.49, 126, 0 45 100 300 500 1000",
            "elbflug-air-freight-tariff-2026-11.xlsx, Elbflug Air Freight,   19, 2219.56, 171, 0 45 100 300 500 1000",
            "kestrel-airways-tariff-2026-11.xlsx,    Kestrel Airways,        18, 2110.60, 180, 0 45 100 250 500 1000 2000",
            "vega-skyfreight-tariff-2026-11.xlsx,    Vega Skyfreight,        13, 1599.11, 130, 0 45 100 300 500 1000 2000",
            "wayzata-air-cargo-tariff-2026-11.xlsx,  Wayzata Air Cargo,      26, 3305.72, 234, 0 45 100 300 500 1000",
            "quetzal-air-freight-tariff-2026-11.docx, Quetzal Air Freight,   11, 1412.45, 110, 0 45 100 300 500 1000 2000",
            "yellowtail-air-freight-tariff-2026-11.txt, Yellowtail Air Freight, 15, 1710.28, 150, 0 45 100 250 500 1000 2000",
    })
    void parsesKitFile(String file, String airline, int lanes, BigDecimal sum, int amounts, String breaks) throws Exception {
        ParsedTariff t = parsers.parse(KitFiles.source(file));

        assertThat(t.airlineName()).isEqualTo(airline);
        assertThat(t.currency()).isEqualTo("EUR");
        assertThat(t.fxEurPerUnit()).isNull();
        assertThat(t.validFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(t.validTo()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(t.lanes()).hasSize(lanes);
        assertThat(TariffSanityCheck.structural(t)).isEmpty();

        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (ParsedLane l : t.lanes()) {
            assertThat(l.transitDays()).isNotNull().isPositive();
            assertThat(l.extras()).isEmpty();
            assertThat(l.breaks().stream().map(WeightBreak::fromKg).map(String::valueOf).toList())
                    .containsExactly(breaks.split(" "));
            total = total.add(l.minimum()).add(l.fuelPerKg()).add(l.securityPerKg());
            count += 3;
            for (WeightBreak b : l.breaks()) {
                total = total.add(b.rate());
                count++;
            }
        }
        assertThat(total).isEqualByComparingTo(sum);
        assertThat(count).isEqualTo(amounts);
    }

    @Test
    void cobaltPdfBlocksIncludingLaneSplitAcrossPages() throws Exception {
        ParsedLane syd = lane(parsers.parse(KitFiles.source("cobalt-skylift-tariff-2026-11.pdf")), "DUS-SYD");
        assertThat(syd.transitDays()).isEqualTo(4);
        assertThat(syd.minimum()).isEqualByComparingTo("68.00");
        assertThat(rates(syd)).containsExactly("6.53", "4.90", "3.63", "2.61", "2.24", "2.02", "1.47");
        assertThat(syd.fuelPerKg()).isEqualByComparingTo("0.85");
        assertThat(syd.securityPerKg()).isEqualByComparingTo("0.11");
    }

    @Test
    void helixTableWithSeparateSurchargeSection() throws Exception {
        ParsedLane lax = lane(parsers.parse(KitFiles.source("helix-air-freight-tariff-2026-11.pdf")), "FRA-LAX");
        assertThat(lax.transitDays()).isEqualTo(1);
        assertThat(lax.minimum()).isEqualByComparingTo("112.00");
        assertThat(rates(lax)).containsExactly("3.33", "2.73", "2.24", "2.24", "1.62", "1.25", "1.25");
        assertThat(lax.fuelPerKg()).isEqualByComparingTo("1.40");
        assertThat(lax.securityPerKg()).isEqualByComparingTo("0.21");
    }

    @Test
    void orionDecimalCommaBlocks() throws Exception {
        ParsedLane nbo = lane(parsers.parse(KitFiles.source("orion-cargo-air-tariff-2026-11.pdf")), "FRA-NBO");
        assertThat(nbo.minimum()).isEqualByComparingTo("96.46");
        assertThat(rates(nbo)).containsExactly("4.56", "4.01", "3.05", "2.37", "2.10", "1.64");
        assertThat(nbo.fuelPerKg()).isEqualByComparingTo("1.00");
        assertThat(nbo.securityPerKg()).isEqualByComparingTo("0.17");
    }

    @Test
    void elbflugOriginGroupsWithTextNumbers() throws Exception {
        ParsedLane tlv = lane(parsers.parse(KitFiles.source("elbflug-air-freight-tariff-2026-11.xlsx")), "DUS-TLV");
        assertThat(tlv.transitDays()).isEqualTo(1);
        assertThat(tlv.minimum()).isEqualByComparingTo("85.86");
        assertThat(rates(tlv)).containsExactly("3.52", "2.89", "2.49", "1.87", "1.35", "1.04");
        assertThat(tlv.fuelPerKg()).isEqualByComparingTo("1.34");
        assertThat(tlv.securityPerKg()).isEqualByComparingTo("0.14");
    }

    @Test
    void kestrelInlineSurchargeColumnsAndDPlusTransit() throws Exception {
        ParsedLane bom = lane(parsers.parse(KitFiles.source("kestrel-airways-tariff-2026-11.xlsx")), "FRA-BOM");
        assertThat(bom.transitDays()).isEqualTo(2);
        assertThat(bom.minimum()).isEqualByComparingTo("134.62");
        assertThat(rates(bom)).containsExactly("4.25", "3.23", "2.34", "2.34", "1.93", "1.70", "1.30");
        assertThat(bom.fuelPerKg()).isEqualByComparingTo("0.81");
        assertThat(bom.securityPerKg()).isEqualByComparingTo("0.13");
    }

    @Test
    void vegaCityNamedGroupsAndNumericCells() throws Exception {
        ParsedLane los = lane(parsers.parse(KitFiles.source("vega-skyfreight-tariff-2026-11.xlsx")), "CGN-LOS");
        assertThat(los.transitDays()).isEqualTo(3);
        assertThat(los.minimum()).isEqualByComparingTo("114.48");
        assertThat(rates(los)).containsExactly("4.60", "4.19", "3.73", "2.69", "2.42", "1.94", "1.69");
        assertThat(los.fuelPerKg()).isEqualByComparingTo("1.29");
        assertThat(los.securityPerKg()).isEqualByComparingTo("0.13");
    }

    @Test
    void wayzataArrowLanesAndDayTransit() throws Exception {
        ParsedLane doh = lane(parsers.parse(KitFiles.source("wayzata-air-cargo-tariff-2026-11.xlsx")), "FRA-DOH");
        assertThat(doh.transitDays()).isEqualTo(1);
        assertThat(doh.minimum()).isEqualByComparingTo("136");
        assertThat(rates(doh)).containsExactly("3.44", "2.64", "2.20", "1.67", "1.48", "1.15");
        assertThat(doh.fuelPerKg()).isEqualByComparingTo("1.21");
        assertThat(doh.securityPerKg()).isEqualByComparingTo("0.19");
    }

    @Test
    void quetzalWordTable() throws Exception {
        ParsedLane ruh = lane(parsers.parse(KitFiles.source("quetzal-air-freight-tariff-2026-11.docx")), "FRA-RUH");
        assertThat(ruh.transitDays()).isEqualTo(2);
        assertThat(ruh.minimum()).isEqualByComparingTo("129.00");
        assertThat(rates(ruh)).containsExactly("3.17", "2.63", "2.26", "1.79", "1.43", "1.26", "1.26");
        assertThat(ruh.fuelPerKg()).isEqualByComparingTo("0.95");
        assertThat(ruh.securityPerKg()).isEqualByComparingTo("0.18");
    }

    @Test
    void yellowtailPlainText() throws Exception {
        ParsedLane bog = lane(parsers.parse(KitFiles.source("yellowtail-air-freight-tariff-2026-11.txt")), "HAM-BOG");
        assertThat(bog.transitDays()).isEqualTo(2);
        assertThat(bog.minimum()).isEqualByComparingTo("127.20");
        assertThat(rates(bog)).containsExactly("4.62", "4.06", "3.53", "3.53", "2.44", "1.76", "1.29");
        assertThat(bog.fuelPerKg()).isEqualByComparingTo("1.43");
        assertThat(bog.securityPerKg()).isEqualByComparingTo("0.23");
    }

    @Test
    void danubeTranscriptionMatchesTheScans() throws Exception {
        ParsedTariff t;
        try (InputStream in = getClass().getResourceAsStream("/tariffs/danube-wings-cargo-tariff-2026-11.json")) {
            t = JsonMapper.builder().build().readValue(in, ParsedTariff.class);
        }
        assertThat(t.airlineName()).isEqualTo("Danube Wings Cargo");
        assertThat(t.currency()).isEqualTo("EUR");
        assertThat(t.validFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(t.validTo()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(t.lanes()).extracting(ParsedLane::key).containsExactly(
                "CGN-SYD", "DUS-MEX", "DUS-SYD", "DUS-YYZ", "FRA-DXB", "FRA-EZE", "MUC-JNB", "MUC-TPE", "STR-SGN");
        assertThat(TariffSanityCheck.structural(t)).isEmpty();
        ParsedLane dxb = lane(t, "FRA-DXB");
        assertThat(dxb.minimum()).isEqualByComparingTo("138.86");
        assertThat(rates(dxb)).containsExactly("2.90", "2.23", "2.05", "1.66", "1.45", "1.33");
        assertThat(dxb.fuelPerKg()).isEqualByComparingTo("1.12");
        assertThat(dxb.securityPerKg()).isEqualByComparingTo("0.22");
    }

    static ParsedLane lane(ParsedTariff t, String key) {
        return t.lanes().stream().filter(l -> l.key().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> rates(ParsedLane lane) {
        return lane.breaks().stream().map(b -> b.rate().setScale(2).toPlainString()).toList();
    }
}
