package ai.aeiou.edgelab.gateway;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Calls the real 5U gateway (costs points). Skipped by default; run with
 * {@code ./gradlew test -PrealGateway --tests '*RealGatewayTest'} and TEAM_API_KEY set
 * (environment or repo-root .env).
 */
@Tag("real-gateway")
@EnabledIfSystemProperty(named = "edgelab.gateway.enabled", matches = "true")
class RealGatewayTest {

    @Test
    void realGatewayTranscribesTheEnglishRfq() throws Exception {
        byte[] wav;
        try (InputStream in = RealGatewayTest.class.getResourceAsStream("/voice/rfq-en.wav")) {
            wav = in.readAllBytes();
        }
        Gateway.Answer a = HttpGateway.fromSettings().transcribe(wav, "wav");
        assertTrue(a.ok(), a.problem());
        assertTrue(a.text().toLowerCase(Locale.ROOT).contains("munich"), a.text());
    }
}
