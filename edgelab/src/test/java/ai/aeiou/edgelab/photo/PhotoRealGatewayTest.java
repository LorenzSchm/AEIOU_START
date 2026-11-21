package ai.aeiou.edgelab.photo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.Reading;

/**
 * Reads the real Danube Wings tariff scan through the real 5U gateway (costs points).
 * Skipped by default; run with {@code ./gradlew test -PrealGateway --tests '*PhotoRealGatewayTest'}
 * and TEAM_API_KEY set (environment or repo-root .env).
 */
@Tag("real-gateway")
@EnabledIfSystemProperty(named = "edgelab.gateway.enabled", matches = "true")
class PhotoRealGatewayTest {

    @Test
    void realGatewayReadsTheDanubeWingsTariffScan() {
        byte[] scan = PhotoFixtures.resource("danube-wings-cargo-tariff-2026-11-p1.jpg");

        Reading r = new EdgeReader().read("danube-wings-cargo-tariff-2026-11-p1.jpg", scan);

        System.out.println(r.text());
        System.out.println(r.notes() + " confidence " + r.confidence());
        assertEquals(Reading.Status.OK, r.status(), r.notes().toString());
        assertTrue(r.text().contains("Danube"), r.text());
        assertTrue(r.text().contains("MUC-JNB") || r.text().contains("FRA-DXB"), r.text());
    }
}
