package ai.aeiou.edgelab.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewaySettingsTest {

    @Test
    void defaultsWhenNothingIsSet() {
        GatewaySettings s = GatewaySettings.from(Map.<String, String>of()::get, null);
        assertEquals("https://api.start-hack.5u.ai/v1/gateway", s.baseUrl());
        assertEquals("gpt-4o-mini", s.visionModel());
        assertEquals(GatewaySettings.DEFAULT_AUDIO_MODEL, s.audioModel());
        assertEquals(GatewaySettings.AudioMethod.CHAT, s.audioMethod());
        assertEquals(Duration.ofSeconds(15), s.timeout());
        assertFalse(s.hasKey());
    }

    @Test
    void modelNamesMethodAndKeyComeFromTheEnvironment() {
        Map<String, String> env = Map.of(
                "TEAM_API_KEY", "k-123",
                "EDGELAB_AUDIO_MODEL", "gpt-4o-mini-transcribe",
                "EDGELAB_AUDIO_METHOD", "transcriptions",
                "EDGELAB_VISION_MODEL", "gpt-4.1-mini",
                "EDGELAB_GATEWAY_URL", "http://localhost:9/v1/gateway/",
                "EDGELAB_GATEWAY_TIMEOUT", "7");
        GatewaySettings s = GatewaySettings.from(env::get, null);
        assertEquals("k-123", s.apiKey());
        assertEquals("gpt-4o-mini-transcribe", s.audioModel());
        assertEquals(GatewaySettings.AudioMethod.TRANSCRIPTIONS, s.audioMethod());
        assertEquals("gpt-4.1-mini", s.visionModel());
        assertEquals("http://localhost:9/v1/gateway", s.baseUrl());
        assertEquals(Duration.ofSeconds(7), s.timeout());
    }

    @Test
    void keyFallsBackToTheEnvFile(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "# comment\nOTHER=1\nTEAM_API_KEY_OLD=nope\nexport TEAM_API_KEY = \"from-file\"\n");
        assertEquals("from-file", GatewaySettings.from(Map.<String, String>of()::get, env).apiKey());
        // The environment wins over the file.
        assertEquals("from-env", GatewaySettings.from(Map.of("TEAM_API_KEY", "from-env")::get, env).apiKey());
        assertNull(GatewaySettings.from(Map.<String, String>of()::get, dir.resolve("missing.env")).apiKey());
    }

    @Test
    void toStringNeverShowsTheKey() {
        GatewaySettings s = GatewaySettings.from(Map.of("TEAM_API_KEY", "super-secret-key")::get, null);
        assertFalse(s.toString().contains("super-secret-key"), s.toString());
        assertTrue(s.toString().contains("key=set"));
        assertFalse(new HttpGateway(s).toString().contains("super-secret-key"));
    }
}
