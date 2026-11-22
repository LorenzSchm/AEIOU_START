package ai.aeiou.edgelab.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Gateway settings. Each is a system property, else an environment variable, else the default.
 *
 * <pre>
 *   edgelab.gateway.url     / EDGELAB_GATEWAY_URL      default https://api.start-hack.5u.ai/v1/gateway
 *   edgelab.gateway.enabled / EDGELAB_GATEWAY_ENABLED  default false
 *   edgelab.gateway.timeout / EDGELAB_GATEWAY_TIMEOUT  seconds per call, default 15
 *   edgelab.audio.model     / EDGELAB_AUDIO_MODEL      default gpt-4o-mini-audio-preview
 *   edgelab.audio.method    / EDGELAB_AUDIO_METHOD     chat (input_audio) | transcriptions, default chat
 *   edgelab.vision.model    / EDGELAB_VISION_MODEL     default gpt-4o-mini
 *   edgelab.env.file        / EDGELAB_ENV_FILE         .env to read TEAM_API_KEY from if it is not
 *                                                      in the environment; default: the nearest .env
 *                                                      in the working folder or up to 3 folders above
 * </pre>
 *
 * The key is {@code TEAM_API_KEY}. {@link #toString()} never shows it.
 */
public record GatewaySettings(
        String baseUrl,
        String apiKey,
        String audioModel,
        AudioMethod audioMethod,
        String visionModel,
        Duration timeout) {

    public static final String DEFAULT_URL = "https://api.start-hack.5u.ai/v1/gateway";
    public static final String DEFAULT_AUDIO_MODEL = "gpt-4o-mini-audio-preview";
    public static final String DEFAULT_VISION_MODEL = "gpt-4o-mini";
    public static final String KEY_NAME = "TEAM_API_KEY";

    /** How audio is sent: chat completions with an input_audio part, or the transcription endpoint. */
    public enum AudioMethod {
        CHAT, TRANSCRIPTIONS;

        static AudioMethod parse(String s) {
            return s.trim().toLowerCase(Locale.ROOT).startsWith("trans") ? TRANSCRIPTIONS : CHAT;
        }
    }

    public GatewaySettings {
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
    }

    public boolean hasKey() { return apiKey != null; }

    public GatewaySettings withKey(String key) {
        return new GatewaySettings(baseUrl, key, audioModel, audioMethod, visionModel, timeout);
    }

    public GatewaySettings withBaseUrl(String url) {
        return new GatewaySettings(url, apiKey, audioModel, audioMethod, visionModel, timeout);
    }

    /** Whether the gateway may be called at all ({@code edgelab.gateway.enabled}). */
    public static boolean enabled() {
        return Boolean.parseBoolean(setting(System::getenv, "edgelab.gateway.enabled", "EDGELAB_GATEWAY_ENABLED", "false"));
    }

    /** From system properties, the environment and (for the key only) a .env file. */
    public static GatewaySettings fromEnvironment() {
        return from(System::getenv, envFile());
    }

    /** Testable core: {@code env} stands in for the environment. */
    static GatewaySettings from(Function<String, String> env, Path envFile) {
        String key = env.apply(KEY_NAME);
        if ((key == null || key.isBlank()) && envFile != null) key = keyFromEnvFile(envFile);
        long seconds;
        try {
            seconds = Long.parseLong(setting(env, "edgelab.gateway.timeout", "EDGELAB_GATEWAY_TIMEOUT", "15"));
        } catch (NumberFormatException e) {
            seconds = 15;
        }
        return new GatewaySettings(
                setting(env, "edgelab.gateway.url", "EDGELAB_GATEWAY_URL", DEFAULT_URL),
                key,
                setting(env, "edgelab.audio.model", "EDGELAB_AUDIO_MODEL", DEFAULT_AUDIO_MODEL),
                AudioMethod.parse(setting(env, "edgelab.audio.method", "EDGELAB_AUDIO_METHOD", "chat")),
                setting(env, "edgelab.vision.model", "EDGELAB_VISION_MODEL", DEFAULT_VISION_MODEL),
                Duration.ofSeconds(Math.max(1, seconds)));
    }

    /** TEAM_API_KEY=... from a .env file, or null. Quotes and "export " are allowed. */
    static String keyFromEnvFile(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String l = line.strip();
                if (l.startsWith("export ")) l = l.substring(7).strip();
                if (!l.startsWith(KEY_NAME)) continue;
                String rest = l.substring(KEY_NAME.length()).strip();
                if (!rest.startsWith("=")) continue;
                String v = rest.substring(1).strip();
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                return v.isBlank() ? null : v;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static Path envFile() {
        String explicit = setting(System::getenv, "edgelab.env.file", "EDGELAB_ENV_FILE", null);
        if (explicit != null) return Path.of(explicit);
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up <= 3 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static String setting(Function<String, String> env, String prop, String envName, String fallback) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) v = env.apply(envName);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    @Override
    public String toString() {
        return "GatewaySettings[baseUrl=" + baseUrl + ", key=" + (hasKey() ? "set" : "not set")
                + ", audioModel=" + audioModel + ", audioMethod=" + audioMethod
                + ", visionModel=" + visionModel + ", timeout=" + timeout + "]";
    }
}
