package ai.aeiou.edgelab.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.Router;
import ai.aeiou.edgelab.gateway.Gateway;
import ai.aeiou.edgelab.photo.PhotoReader;
import ai.aeiou.edgelab.voice.AudioDecoder;
import ai.aeiou.edgelab.voice.LocalTranscriber;
import ai.aeiou.edgelab.voice.VoiceReader;
import ai.aeiou.edgelab.voice.WhisperModelCache;
import ai.aeiou.edgelab.voice.WhisperTranscriber;

/** read(filename, bytes) on generated videos. The gateway is always faked; Whisper is faked or local. */
class VideoReaderTest {

    static final String FRAME_TEXT = "Request for quotation\nFrom: Munich (MUC) To: Chicago (ORD)";
    static final String SPEECH = "Please quote 340 kilograms from Munich to Chicago O'Hare.";

    /** Vision answers with fixed text and counts the images; audio is never asked for. */
    static final class FakeGateway implements Gateway {
        final List<byte[]> images = new ArrayList<>();
        private final Answer answer;

        FakeGateway(Answer answer) { this.answer = answer; }

        FakeGateway() { this(new Answer(FRAME_TEXT, 0.9, "fake-vision", null)); }

        @Override public Answer transcribe(byte[] audio, String format) {
            return Answer.failed("fake-audio", "fake gateway: no audio");
        }

        @Override public synchronized Answer copyText(byte[] image, String mimeType) {
            images.add(image);
            return answer;
        }
    }

    /** Fake Whisper: remembers how much audio it got, answers with fixed speech. */
    static final class FakeWhisper implements LocalTranscriber {
        int samples = -1;

        @Override public List<WhisperTranscriber.Segment> transcribe(float[] s) {
            samples = s.length;
            return List.of(new WhisperTranscriber.Segment(0, 1000, SPEECH));
        }
    }

    private static Reading read(FakeGateway gw, LocalTranscriber whisper, String name, byte[] bytes) {
        VoiceReader voice = new VoiceReader(new AudioDecoder(VoiceReader.MAX_SECONDS), whisper, gw);
        VideoReader video = new VideoReader(new FrameSampler(), new PhotoReader(gw)::readImage, voice);
        return new EdgeReader(Router.empty().withVideo(video), EdgeReader.DEFAULT_MAX_BYTES).read(name, bytes);
    }

    @Test
    void fixtureVideoReturnsFrameTextAndSpeechInLabelledSections() {
        FakeGateway gw = new FakeGateway();
        FakeWhisper whisper = new FakeWhisper();

        Reading r = read(gw, whisper, "rfq.mp4", VideoFixtures.rfqVideo());

        assertEquals(Reading.Status.OK, r.status(), () -> "notes: " + r.notes());
        assertTrue(r.detectedType().startsWith("video/"), r.detectedType());
        assertEquals("What is shown:\n" + FRAME_TEXT + "\n\nWhat is said:\n" + SPEECH, r.text());
        assertTrue(r.confidence() > 0.6, "confidence " + r.confidence());
        assertEquals(List.of("video frames", "sound track"), r.parts().stream().map(Reading.Part::name).toList());
        assertTrue(r.parts().stream().allMatch(p -> p.status() == Reading.Status.OK), r.parts().toString());
        // The whole sound track reached Whisper, as 16 kHz mono.
        int expected = VideoFixtures.voiceSamples().length;
        assertTrue(Math.abs(whisper.samples - expected) < AudioDecoder.SAMPLE_RATE / 2,
                whisper.samples + " samples, expected about " + expected);
    }

    @Test
    void identicalFramesAreSentOnce() {
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, new FakeWhisper(), "rfq.mp4", VideoFixtures.rfqVideo());

        assertEquals(1, gw.images.size(), () -> "notes: " + r.notes());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("near-duplicate")), r.notes().toString());
    }

    @Test
    void atMostFiveFramesAreSentForALongSlideShow() {
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, new FakeWhisper(), "slides.mp4", VideoFixtures.slideShow(8, 1.0));

        assertEquals(FrameSampler.MAX_FRAMES, gw.images.size(), () -> "notes: " + r.notes());
        // Same text on every frame is written once.
        assertEquals("What is shown:\n" + FRAME_TEXT, r.text());
    }

    @Test
    void twoSlidesGiveTwoFrames() {
        FakeGateway gw = new FakeGateway();

        read(gw, new FakeWhisper(), "slides.mp4", VideoFixtures.slideShow(2, 2.0));

        assertEquals(2, gw.images.size());
    }

    @Test
    void silentVideoReturnsFrameTextOnlyWithANote() {
        FakeGateway gw = new FakeGateway();
        FakeWhisper whisper = new FakeWhisper();

        Reading r = read(gw, whisper, "silent.mp4", VideoFixtures.silentRfqVideo());

        assertEquals(Reading.Status.OK, r.status(), () -> "notes: " + r.notes());
        assertEquals("What is shown:\n" + FRAME_TEXT, r.text());
        assertTrue(r.notes().contains("video has no sound track"), r.notes().toString());
        assertEquals(-1, whisper.samples, "Whisper must not run on a silent video");
    }

    @Test
    void whenTheFramesFailTheSpeechIsKeptAndTheStatusIsPartial() {
        FakeGateway gw = new FakeGateway(Gateway.Answer.failed("fake-vision", "HTTP 500"));

        Reading r = read(gw, new FakeWhisper(), "rfq.mp4", VideoFixtures.rfqVideo());

        assertEquals(Reading.Status.PARTIAL, r.status(), () -> "notes: " + r.notes());
        assertEquals("What is said:\n" + SPEECH, r.text());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("HTTP 500")), r.notes().toString());
    }

    @Test
    void corruptVideoIsUnreadableWithANoteAndNeverThrows() {
        // A valid mp4 'ftyp' box, then junk.
        byte[] corrupt = new byte[5000];
        byte[] head = "\u0000\u0000\u0000\u0018ftypmp42\u0000\u0000\u0000\u0000mp42isom".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(head, 0, corrupt, 0, head.length);
        Arrays.fill(corrupt, head.length, corrupt.length, (byte) 0x5A);
        // A real video cut off after its first quarter.
        byte[] full = VideoFixtures.silentRfqVideo();
        byte[] truncated = Arrays.copyOf(full, full.length / 4);

        for (byte[] bytes : new byte[][] {corrupt, truncated}) {
            FakeGateway gw = new FakeGateway();
            Reading r = read(gw, new FakeWhisper(), "broken.mp4", bytes);
            assertEquals(Reading.Status.UNREADABLE, r.status(), () -> r.detectedType() + " " + r.text() + " " + r.notes());
            assertEquals("", r.text());
            assertFalse(r.notes().isEmpty());
        }
    }

    @Test
    void realLocalWhisperHearsTheRfqInTheVideo() {
        WhisperModelCache cache = WhisperModelCache.fromSettings();
        assumeTrue(cache.isCached(), "Whisper model not cached; skipping the real-Whisper video test");
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, new WhisperTranscriber(cache), "rfq.mp4", VideoFixtures.rfqVideo());

        assertEquals(Reading.Status.OK, r.status(), () -> "notes: " + r.notes());
        String said = r.text().substring(r.text().indexOf("What is said:"));
        assertTrue(said.contains("340"), said);
        assertTrue(said.toLowerCase(Locale.ROOT).contains("munich"), said);
    }

    @Test
    void standardRouterSendsVideoToTheVideoReader() {
        assertTrue(Router.standard().readerFor("video/mp4") instanceof VideoReader);
    }
}
