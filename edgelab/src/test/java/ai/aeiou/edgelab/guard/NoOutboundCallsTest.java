package ai.aeiou.edgelab.guard;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * edgelab only reads. Its one outbound call is the LLM gateway (plus the one-time Whisper
 * model download). No mailbox, portal or booking endpoints, anywhere in the main sources.
 */
class NoOutboundCallsTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /** API paths of the 5U mailbox, portal and booking endpoints. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "/v1/mailbox|/mailbox/messages|/portal|/v1/book|/bookings?\\b|/v1/rates|/v1/quotes");

    /** Only these files may open HTTP connections. */
    private static final List<String> HTTP_ALLOWED = List.of(
            "ai/aeiou/edgelab/gateway/HttpGateway.java",
            "ai/aeiou/edgelab/voice/WhisperModelCache.java");

    @Test
    void noMailboxPortalOrBookingCalls() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path f : javaFiles()) {
            String src = Files.readString(f, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (FORBIDDEN.matcher(src).find()) hits.add(rel(f));
        }
        assertTrue(hits.isEmpty(), "edgelab must not call mailbox, portal or booking APIs: " + hits);
    }

    @Test
    void onlyTheGatewayClientAndModelDownloadUseHttp() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path f : javaFiles()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            boolean http = src.contains("java.net.http") || src.contains("HttpURLConnection")
                    || src.contains("java.net.URL;") || src.contains("new Socket(");
            if (http && !HTTP_ALLOWED.contains(rel(f))) hits.add(rel(f));
        }
        assertTrue(hits.isEmpty(), "network access outside the gateway client: " + hits);
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String rel(Path f) {
        return MAIN.relativize(f).toString().replace('\\', '/');
    }
}
