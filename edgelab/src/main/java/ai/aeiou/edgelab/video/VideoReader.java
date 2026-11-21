package ai.aeiou.edgelab.video;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ai.aeiou.edgelab.FormatReader;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.photo.PhotoReader;
import ai.aeiou.edgelab.voice.AudioDecoder;
import ai.aeiou.edgelab.voice.VoiceReader;
import ai.aeiou.edgelab.voice.WavEncoder;

/**
 * The video slot (mp4, mov, webm, avi, mkv). Bundled ffmpeg (JavaCV) splits the video:
 * <ul>
 *   <li>the sound track, as a 16 kHz wav, goes to the voice reader,</li>
 *   <li>at most {@link FrameSampler#MAX_FRAMES} evenly spread still frames, near-duplicates
 *       and blank frames skipped, go to the photo reader.</li>
 * </ul>
 * The result is one Reading with a "What is shown" and a "What is said" section and one
 * part per source. If one half failed (not just "no sound" or "no text in the picture"),
 * the status is PARTIAL. Never throws.
 */
public final class VideoReader implements FormatReader {

    /** Reads one decoded, upright frame. {@link PhotoReader#readImage} fits. */
    @FunctionalInterface
    public interface FrameReader {
        Reading read(BufferedImage frame, String detectedType);
    }

    public static final String SHOWN = "What is shown";
    public static final String SAID = "What is said";

    private final FrameSampler sampler;
    private final FrameReader frames;
    private final FormatReader voice;

    public VideoReader(FrameSampler sampler, FrameReader frames, FormatReader voice) {
        this.sampler = sampler;
        this.frames = frames;
        this.voice = voice;
    }

    /** Production wiring: shared voice reader (Whisper loaded once), gateway photo reader. */
    public static VideoReader standard() {
        PhotoReader photo = PhotoReader.standard();
        return new VideoReader(new FrameSampler(), photo::readImage, VoiceReader.standard());
    }

    /** One half of the video: its text, how it went, and whether that counts as a failure. */
    private record Half(String text, double confidence, Reading.Status status, boolean failed, List<String> notes) {
        boolean hasText() { return !text.isBlank(); }
    }

    @Override
    public Reading read(String filename, byte[] bytes, String detectedType) {
        Half shown;
        try {
            shown = readFrames(bytes);
        } catch (Exception | LinkageError e) {
            shown = new Half("", 0, Reading.Status.UNREADABLE, true, List.of("frames not read: " + describe(e)));
        }
        Half said;
        try {
            said = readSound(filename, bytes);
        } catch (Exception | LinkageError e) {
            said = new Half("", 0, Reading.Status.UNREADABLE, true, List.of("sound not read: " + describe(e)));
        }

        List<String> notes = new ArrayList<>();
        notes.addAll(shown.notes());
        notes.addAll(said.notes());
        List<Reading.Part> parts = List.of(
                new Reading.Part("video frames", "image/jpeg", shown.status(), shown.confidence()),
                new Reading.Part("sound track", "audio/wav", said.status(), said.confidence()));

        if (!shown.hasText() && !said.hasText()) {
            notes.add("nothing readable in the video");
            return new Reading("", 0.0, detectedType, Reading.Status.UNREADABLE, parts, notes);
        }

        StringBuilder text = new StringBuilder();
        if (shown.hasText()) text.append(SHOWN).append(":\n").append(shown.text().strip());
        if (said.hasText()) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append(SAID).append(":\n").append(said.text().strip());
        }

        double confidence;
        if (shown.hasText() && said.hasText()) confidence = (shown.confidence() + said.confidence()) / 2;
        else confidence = shown.hasText() ? shown.confidence() : said.confidence();

        boolean partial = shown.failed() || said.failed()
                || shown.status() == Reading.Status.PARTIAL || said.status() == Reading.Status.PARTIAL;
        return new Reading(text.toString(), confidence, detectedType,
                partial ? Reading.Status.PARTIAL : Reading.Status.OK, parts, notes);
    }

    private Half readFrames(byte[] bytes) {
        FrameSampler.Result sampled = sampler.sample(bytes);
        List<String> notes = new ArrayList<>();
        if (!sampled.ok()) {
            notes.add("frames not read: " + sampled.problem());
            return new Half("", 0, Reading.Status.UNREADABLE, true, notes);
        }
        if (sampled.frames().isEmpty()) {
            notes.add("no picture in the video");
            return new Half("", 0, Reading.Status.UNREADABLE, false, notes);
        }
        notes.add(sampled.frames().size() + " frame(s) read" + (sampled.skipped() > 0
                ? ", " + sampled.skipped() + " near-duplicate or blank skipped" : ""));

        // Same text on several frames (a slide held for a while) is kept once.
        Set<String> texts = new LinkedHashSet<>();
        Set<String> readerNotes = new LinkedHashSet<>();
        double confidenceSum = 0;
        int withText = 0;
        int failed = 0;
        for (FrameSampler.Frame f : sampled.frames()) {
            Reading r = frames.read(f.image(), "image/jpeg");
            if (r.status() != Reading.Status.UNREADABLE && !r.text().isBlank()) {
                texts.add(r.text().strip());
                confidenceSum += r.confidence();
                withText++;
                r.notes().stream().filter(n -> n.startsWith("vision model")).forEach(readerNotes::add);
            } else {
                boolean noText = r.notes().stream().anyMatch(n -> n.startsWith("no text"));
                if (!noText) {
                    failed++;
                    String reason = r.notes().isEmpty() ? "no reason given" : r.notes().get(r.notes().size() - 1);
                    readerNotes.add("frame at " + clock(f.seconds()) + " not read: " + reason);
                }
            }
        }
        notes.addAll(readerNotes);
        if (withText == 0) {
            if (failed == 0) notes.add("no text in the frames");
            return new Half("", 0, Reading.Status.UNREADABLE, failed > 0, notes);
        }
        Reading.Status status = failed > 0 ? Reading.Status.PARTIAL : Reading.Status.OK;
        return new Half(String.join("\n\n", texts), confidenceSum / withText, status, false, notes);
    }

    private Half readSound(String filename, byte[] bytes) throws Exception {
        SoundTrack.Result sound = SoundTrack.extract(bytes, VoiceReader.MAX_SECONDS);
        if (!sound.ok()) {
            return new Half("", 0, Reading.Status.UNREADABLE, true, List.of("sound not read: " + sound.problem()));
        }
        if (!sound.present()) {
            return new Half("", 0, Reading.Status.UNREADABLE, false, List.of("video has no sound track"));
        }
        // The voice reader gets the sound track alone, as a plain 16 kHz wav.
        byte[] wav = WavEncoder.encode(sound.samples(), AudioDecoder.SAMPLE_RATE);
        Reading r = voice.read(filename + ".wav", wav, "audio/vnd.wave");
        List<String> notes = new ArrayList<>();
        for (String n : r.notes()) notes.add("sound: " + n);
        if (sound.truncated()) notes.add("sound cut after " + VoiceReader.MAX_SECONDS + " s");
        if (r.status() == Reading.Status.UNREADABLE || r.text().isBlank()) {
            return new Half("", 0, Reading.Status.UNREADABLE, true, notes);
        }
        return new Half(r.text(), r.confidence(), sound.truncated() ? Reading.Status.PARTIAL : r.status(), false, notes);
    }

    static String clock(double seconds) {
        int s = (int) Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }
}
