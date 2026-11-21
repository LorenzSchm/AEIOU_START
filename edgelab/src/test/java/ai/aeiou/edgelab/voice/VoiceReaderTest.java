package ai.aeiou.edgelab.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.Reading;

/**
 * Voice fixtures were made with Windows' built-in voices (Zira, en-US; Hedda, de-DE).
 * Tests that need Whisper skip with a message if the model cannot be downloaded.
 */
class VoiceReaderTest {

    private static final EdgeReader READER = new EdgeReader();
    private static final Map<String, Reading> CACHE = new ConcurrentHashMap<>();
    private static String modelProblem;

    @BeforeAll
    static void fetchModelOnce() {
        try {
            WhisperModelCache.fromSettings().ensure();
        } catch (Exception e) {
            modelProblem = "Whisper model not available (" + e + "); skipping voice transcription tests";
            System.err.println(modelProblem);
        }
    }

    private static Reading read(String fixture) {
        assumeTrue(modelProblem == null, () -> modelProblem);
        return CACHE.computeIfAbsent(fixture, f -> READER.read(f, bytes(f)));
    }

    private static byte[] bytes(String fixture) {
        try (InputStream in = VoiceReaderTest.class.getResourceAsStream("/voice/" + fixture)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("missing fixture " + fixture, e);
        }
    }

    /** Upper case, letters and digits only: "O. R. D." and "O-R-D" both become "ORD". */
    private static String squashed(String text) {
        return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9ÄÖÜ]", "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"rfq-en.mp3", "rfq-en.wav", "trucker-offer-de.mp3", "trucker-offer-de.wav"})
    void audioFixturesComeBackAsSpokenText(String fixture) {
        Reading r = read(fixture);

        assertEquals(Reading.Status.OK, r.status(), () -> "notes: " + r.notes());
        assertTrue(r.detectedType().startsWith("audio/"), r.detectedType());
        assertTrue(r.text().length() > 40, r.text());
        assertTrue(r.confidence() >= 0.6, () -> r.confidence() + " for " + r.text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rfq-en.mp3", "rfq-en.wav"})
    void englishRfqKeepsWeightRouteAndDate(String fixture) {
        String text = read(fixture).text();

        assertTrue(text.contains("340"), text);
        assertTrue(squashed(text).contains("ORD") || text.contains("O'Hare"), text);
        assertTrue(text.toLowerCase(Locale.ROOT).contains("munich"), text);
        assertTrue(text.contains("24"), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"trucker-offer-de.mp3", "trucker-offer-de.wav"})
    void germanTruckerOfferIsTranscribedInGermanWithPriceAndOfferNumber(String fixture) {
        String text = read(fixture).text();
        String lower = text.toLowerCase(Locale.ROOT);

        assertTrue(text.contains("185"), text);
        assertTrue(squashed(text).contains("4711"), text);
        // German, not translated to English.
        assertTrue(Arrays.stream(new String[] {"angebot", "abholung", "spedition", "grüße", "nummer", "euro"})
                .filter(lower::contains).count() >= 2, text);
        assertFalse(lower.contains("offer"), text);
    }

    /** Phone recordings: m4a with the index at the end of the file (ticket 02b). One Whisper run. */
    @Test
    void m4aVoiceMessageWithItsIndexAtTheEndIsTranscribed() {
        assumeTrue(modelProblem == null, () -> modelProblem);
        Reading r = READER.read("voice-message.m4a", M4aFixtures.get(M4aFixtures.MOOV_AT_END));

        assertEquals(Reading.Status.OK, r.status(), () -> "notes: " + r.notes());
        assertTrue(r.detectedType().startsWith("audio/"), r.detectedType());
        assertTrue(r.text().contains("340"), r.text());
        assertTrue(r.text().toLowerCase(Locale.ROOT).contains("munich"), r.text());
    }

    @Test
    void modelIsDownloadedOnceThenReusedFromTheCache() throws Exception {
        assumeTrue(modelProblem == null, () -> modelProblem);
        WhisperModelCache cache = WhisperModelCache.fromSettings();
        assertTrue(cache.isCached());
        long before = Files.getLastModifiedTime(cache.file()).toMillis();

        // A fresh cache object on the same folder finds the file and does not download again.
        WhisperModelCache again = WhisperModelCache.fromSettings();
        assertEquals(cache.file(), again.ensure());
        assertEquals(before, Files.getLastModifiedTime(again.file()).toMillis());
        assertTrue(cache.file().startsWith(System.getProperty("user.home"))
                || System.getenv("EDGELAB_WHISPER_DIR") != null
                || System.getProperty("edgelab.whisper.dir") != null);
    }

    @Test
    void corruptAudioIsUnreadableWithANoteAndNeverThrows() {
        byte[] wavHeaderThenJunk = new byte[4000];
        byte[] header = "RIFF\u0000\u0010\u0000\u0000WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(header, 0, wavHeaderThenJunk, 0, header.length);
        for (int i = header.length; i < wavHeaderThenJunk.length; i++) wavHeaderThenJunk[i] = (byte) 0xFF;

        byte[] id3ThenJunk = new byte[3000];
        System.arraycopy("ID3".getBytes(StandardCharsets.ISO_8859_1), 0, id3ThenJunk, 0, 3);
        id3ThenJunk[3] = 4;
        Arrays.fill(id3ThenJunk, 10, id3ThenJunk.length, (byte) 0x7F);

        for (byte[] corrupt : new byte[][] {wavHeaderThenJunk, id3ThenJunk}) {
            Reading r = READER.read("voice.mp3", corrupt);
            assertEquals(Reading.Status.UNREADABLE, r.status(), () -> r.detectedType() + " " + r.text());
            assertFalse(r.notes().isEmpty());
            assertEquals("", r.text());
        }
    }
}
