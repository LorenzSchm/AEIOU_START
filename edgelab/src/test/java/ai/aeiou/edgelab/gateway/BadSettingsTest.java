package ai.aeiou.edgelab.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import ai.aeiou.edgelab.gateway.GatewaySettings.AudioMethod;

/** A broken base URL or key must come back as a failed Answer: HttpGateway never throws. No network. */
class BadSettingsTest {

    private static final String KEY = "test-key-SECRET-9";

    private static void assertFailsQuietly(Gateway.Answer a, String key) {
        assertFalse(a.ok());
        assertTrue(a.problem().contains("could not be built"), a.problem());
        assertFalse(a.problem().contains(key), a.problem());
    }

    @Test
    void baseUrlThatIsNotAUri() {
        for (AudioMethod m : AudioMethod.values()) {
            HttpGateway gw = new HttpGateway(new GatewaySettings("http://bad host/v1", KEY, "a", m, "v",
                    Duration.ofSeconds(1)));
            assertFailsQuietly(gw.copyText(new byte[] {1, 2, 3}, "image/jpeg"), KEY);
            assertFailsQuietly(gw.transcribe(new byte[] {1, 2, 3}, "wav"), KEY);
        }
    }

    @Test
    void keyThatCannotBeAHeader() {
        String key = "bad\nkey-SECRET";
        HttpGateway gw = new HttpGateway(new GatewaySettings("http://127.0.0.1:9/v1", key, "a", AudioMethod.CHAT, "v",
                Duration.ofSeconds(1)));
        assertFailsQuietly(gw.copyText(new byte[] {1, 2, 3}, "image/jpeg"), "SECRET");
    }
}
