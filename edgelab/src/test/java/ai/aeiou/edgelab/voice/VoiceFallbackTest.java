package ai.aeiou.edgelab.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.gateway.Gateway;
import ai.aeiou.edgelab.gateway.GatewaySettings;
import ai.aeiou.edgelab.gateway.HttpGateway;

/** The voice reader's gateway fallback, with Whisper and the gateway both faked. */
class VoiceFallbackTest {

    private static final String WAV = "audio/vnd.wave";
    private static final byte[] RFQ = fixture("rfq-en.wav");

    /** Records what it was sent; answers with fixed text, or fails. */
    static final class FakeGateway implements Gateway {
        final List<byte[]> audio = new ArrayList<>();
        final List<String> formats = new ArrayList<>();
        private final Answer answer;

        FakeGateway(Answer answer) { this.answer = answer; }

        @Override public Answer transcribe(byte[] bytes, String format) {
            audio.add(bytes);
            formats.add(format);
            return answer;
        }

        @Override public Answer copyText(byte[] image, String mimeType) {
            throw new AssertionError("voice must not ask for image text");
        }
    }

    private static final Gateway.Answer GATEWAY_TEXT = new Gateway.Answer(
            "Please quote 340 kilograms from Munich to Chicago O'Hare, ready on the 24th.", null, "fake-audio", null);

    private static WhisperTranscriber.Segment seg(String text) {
        return new WhisperTranscriber.Segment(0, 1000, text);
    }

    private static Reading read(LocalTranscriber local, Gateway gateway) {
        return new VoiceReader(new AudioDecoder(VoiceReader.MAX_SECONDS), local, gateway).read("rfq.wav", RFQ, WAV);
    }

    @Test
    void whenWhisperFailsTheGatewayTextIsReturnedWithANote() {
        FakeGateway gw = new FakeGateway(GATEWAY_TEXT);

        Reading r = read(samples -> { throw new java.io.IOException("model missing"); }, gw);

        assertEquals(Reading.Status.OK, r.status(), () -> "notes: " + r.notes());
        assertEquals(GATEWAY_TEXT.text(), r.text());
        assertTrue(r.notes().contains("gateway fallback used (fake-audio)"), r.notes().toString());
        assertTrue(r.notes().get(0).startsWith("local Whisper failed: IOException: model missing"), r.notes().toString());
        assertTrue(r.confidence() > 0.5 && r.confidence() <= 0.8, "confidence " + r.confidence());
        // The decoded audio goes out as a 16 kHz wav.
        assertEquals(List.of("wav"), gw.formats);
        assertEquals("RIFF", new String(gw.audio.get(0), 0, 4, StandardCharsets.US_ASCII));
        assertTrue(gw.audio.get(0).length > 44 + AudioDecoder.SAMPLE_RATE * 2, "at least a second of audio");
    }

    @Test
    void whenWhisperHearsNothingOrAlmostNothingTheGatewayIsAsked() {
        for (LocalTranscriber weak : List.<LocalTranscriber>of(
                samples -> List.of(),
                samples -> List.of(seg("[BLANK_AUDIO]")),
                samples -> List.of(seg(" Hello. ")))) {
            FakeGateway gw = new FakeGateway(GATEWAY_TEXT);
            Reading r = read(weak, gw);
            assertEquals(1, gw.audio.size());
            assertEquals(GATEWAY_TEXT.text(), r.text());
            assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("gateway fallback used")), r.notes().toString());
        }
    }

    @Test
    void whenWhisperWorksTheGatewayIsNeverCalled() {
        FakeGateway gw = new FakeGateway(GATEWAY_TEXT);

        Reading r = read(samples -> List.of(seg("Hello, please quote 340 kilos from Munich to Chicago,"),
                seg("ready for pickup on the 24th of November.")), gw);

        assertEquals(Reading.Status.OK, r.status());
        assertTrue(gw.audio.isEmpty());
        assertTrue(r.text().startsWith("Hello, please quote 340"), r.text());
        assertTrue(r.notes().stream().noneMatch(n -> n.contains("gateway")), r.notes().toString());
    }

    @Test
    void withNoKeyTheFallbackIsUnreadableWithAClearNoteAndDoesNotThrow() {
        Gateway noKey = new HttpGateway(new GatewaySettings(GatewaySettings.DEFAULT_URL, null,
                GatewaySettings.DEFAULT_AUDIO_MODEL, GatewaySettings.AudioMethod.CHAT,
                GatewaySettings.DEFAULT_VISION_MODEL, Duration.ofSeconds(1)));

        Reading r = read(samples -> { throw new IllegalStateException("whisper crashed"); }, noKey);

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertEquals("", r.text());
        assertEquals(0.0, r.confidence());
        assertTrue(r.notes().contains("gateway fallback failed: TEAM_API_KEY not set"), r.notes().toString());
    }

    @Test
    void whenTheGatewayAlsoFailsTheLittleWhisperHeardIsKeptAsPartial() {
        FakeGateway gw = new FakeGateway(Gateway.Answer.failed("fake-audio", "gateway HTTP 503"));

        Reading r = read(samples -> List.of(seg("Munich")), gw);

        assertEquals(Reading.Status.PARTIAL, r.status());
        assertEquals("Munich", r.text());
        assertTrue(r.confidence() <= 0.3);
        assertTrue(r.notes().contains("gateway fallback failed: gateway HTTP 503"), r.notes().toString());
    }

    @Test
    void almostNothingMeansVeryFewWordsForTheLength() {
        assertFalse(VoiceReader.almostNothing("Yes.", 1.5));
        assertTrue(VoiceReader.almostNothing("Yes.", 10));
        assertFalse(VoiceReader.almostNothing("Hello please quote this", 10));
        assertTrue(VoiceReader.almostNothing("one two three four five", 60));
    }

    @Test
    void wavEncoderWritesAPlayable16BitMonoHeader() {
        byte[] wav = WavEncoder.encode(new float[] {0f, 1f, -1f, 2f}, 16_000);
        assertEquals(44 + 8, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(0x7F, wav[44 + 3] & 0xFF); // 1.0 -> 32767, little endian high byte
        assertEquals(0x7F, wav[44 + 7] & 0xFF); // 2.0 clipped to 1.0
        AudioDecoder.Result back = new AudioDecoder(10).decode(WavEncoder.encode(new float[16_000], 16_000));
        assertTrue(back.ok(), back.problem());
        assertEquals(1.0, back.seconds(), 0.05);
    }

    private static byte[] fixture(String name) {
        try (InputStream in = VoiceFallbackTest.class.getResourceAsStream("/voice/" + name)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("missing fixture " + name, e);
        }
    }
}
