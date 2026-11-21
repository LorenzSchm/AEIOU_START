package ai.aeiou.edgelab.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ai.aeiou.edgelab.FormatReader;
import ai.aeiou.edgelab.Reading;

/**
 * The voice slot: audio bytes -> 16 kHz mono (bundled ffmpeg) -> local Whisper -> text.
 * Costs nothing on the gateway. Broken audio comes back as UNREADABLE with a note.
 */
public final class VoiceReader implements FormatReader {

    /** Longer recordings are cut here and come back PARTIAL. */
    public static final int MAX_SECONDS = 10 * 60;

    /** Whisper marks for "no speech here"; they are not text. */
    private static final Pattern NON_SPEECH = Pattern.compile(
            "\\[(BLANK_AUDIO|MUSIC|NOISE|SILENCE|INAUDIBLE)[^\\]]*\\]|\\((music|noise|silence|inaudible)[^)]*\\)",
            Pattern.CASE_INSENSITIVE);

    private final AudioDecoder decoder;
    private final WhisperTranscriber whisper;

    public VoiceReader(AudioDecoder decoder, WhisperTranscriber whisper) {
        this.decoder = decoder;
        this.whisper = whisper;
    }

    /** One shared instance: the Whisper model is loaded once per JVM. */
    public static VoiceReader standard() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        static final VoiceReader INSTANCE = new VoiceReader(
                new AudioDecoder(MAX_SECONDS), new WhisperTranscriber(WhisperModelCache.fromSettings()));
    }

    @Override
    public Reading read(String filename, byte[] bytes, String detectedType) {
        AudioDecoder.Result audio = decoder.decode(bytes);
        if (!audio.ok()) return Reading.unreadable(detectedType, audio.problem());
        if (audio.seconds() < 0.3) return Reading.unreadable(detectedType, "audio too short to contain speech");

        List<WhisperTranscriber.Segment> segments;
        try {
            segments = whisper.transcribe(audio.samples());
        } catch (Exception | LinkageError e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Reading.unreadable(detectedType, "local Whisper failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }

        String text = segments.stream()
                .map(s -> NON_SPEECH.matcher(s.text()).replaceAll("").strip())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .strip();
        if (text.isEmpty()) return Reading.unreadable(detectedType, "no speech recognised");

        List<String> notes = new ArrayList<>();
        notes.add(String.format(Locale.ROOT, "local Whisper, %.1f s of audio", audio.seconds()));
        Reading.Status status = Reading.Status.OK;
        if (audio.truncated()) {
            status = Reading.Status.PARTIAL;
            notes.add("audio cut after " + MAX_SECONDS + " s");
        }
        return new Reading(text, confidence(text, audio.seconds()), detectedType, status, List.of(), notes);
    }

    /**
     * whisper-jni 1.7.1 does not expose token probabilities, so confidence is judged from
     * what Whisper produced: a normal speaking rate (about 1.5-4 words a second) reads as
     * trustworthy; very little text for long audio, or a runaway repetition loop (a known
     * Whisper failure), lowers it.
     */
    static double confidence(String text, double seconds) {
        String[] words = text.split("\\s+");
        double wordsPerSecond = words.length / Math.max(seconds, 1.0);
        double c = 0.85;
        if (wordsPerSecond < 0.5) c -= 0.35;
        else if (wordsPerSecond < 1.0) c -= 0.15;
        else if (wordsPerSecond > 6.0) c -= 0.25;
        long distinct = java.util.Arrays.stream(words).map(w -> w.toLowerCase(Locale.ROOT)).distinct().count();
        if (words.length >= 12 && distinct < words.length * 0.35) c -= 0.35;
        return Math.max(0.05, c);
    }
}
