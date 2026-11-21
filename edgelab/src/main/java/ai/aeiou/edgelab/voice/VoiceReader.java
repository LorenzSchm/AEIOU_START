package ai.aeiou.edgelab.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ai.aeiou.edgelab.FormatReader;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.gateway.Gateway;
import ai.aeiou.edgelab.gateway.HttpGateway;

/**
 * The voice slot: audio bytes -> 16 kHz mono (bundled ffmpeg) -> local Whisper -> text.
 * Costs nothing on the gateway. Only when Whisper fails or hears almost nothing does it
 * fall back to the gateway audio model (note "gateway fallback used").
 * Broken audio comes back as UNREADABLE with a note.
 */
public final class VoiceReader implements FormatReader {

    /** Longer recordings are cut here and come back PARTIAL. */
    public static final int MAX_SECONDS = 10 * 60;

    /** Whisper marks for "no speech here"; they are not text. */
    private static final Pattern NON_SPEECH = Pattern.compile(
            "\\[(BLANK_AUDIO|MUSIC|NOISE|SILENCE|INAUDIBLE)[^\\]]*\\]|\\((music|noise|silence|inaudible)[^)]*\\)",
            Pattern.CASE_INSENSITIVE);

    /** At most this much audio goes to the gateway (a 16 kHz wav is ~1.9 MB a minute). */
    public static final int MAX_FALLBACK_SECONDS = 5 * 60;

    private final AudioDecoder decoder;
    private final LocalTranscriber local;
    private final Gateway gateway;

    public VoiceReader(AudioDecoder decoder, LocalTranscriber local, Gateway gateway) {
        this.decoder = decoder;
        this.local = local;
        this.gateway = gateway;
    }

    /** Local Whisper only, no gateway fallback. */
    public VoiceReader(AudioDecoder decoder, LocalTranscriber local) {
        this(decoder, local, Gateway.unavailable("no gateway configured"));
    }

    /** One shared instance: the Whisper model is loaded once per JVM. */
    public static VoiceReader standard() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        static final VoiceReader INSTANCE = new VoiceReader(
                new AudioDecoder(MAX_SECONDS),
                new WhisperTranscriber(WhisperModelCache.fromSettings()),
                HttpGateway.fromSettings());
    }

    @Override
    public Reading read(String filename, byte[] bytes, String detectedType) {
        AudioDecoder.Result audio = decoder.decode(bytes);
        if (!audio.ok()) return Reading.unreadable(detectedType, audio.problem());
        if (audio.seconds() < 0.3) return Reading.unreadable(detectedType, "audio too short to contain speech");

        String text = null;
        String localProblem;
        try {
            text = clean(local.transcribe(audio.samples()));
            localProblem = text.isEmpty() ? "local Whisper recognised no speech"
                    : almostNothing(text, audio.seconds()) ? "local Whisper returned almost nothing" : null;
        } catch (Exception | LinkageError e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            localProblem = "local Whisper failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }

        if (localProblem == null) {
            List<String> notes = new ArrayList<>();
            notes.add(String.format(Locale.ROOT, "local Whisper, %.1f s of audio", audio.seconds()));
            Reading.Status status = Reading.Status.OK;
            if (audio.truncated()) {
                status = Reading.Status.PARTIAL;
                notes.add("audio cut after " + MAX_SECONDS + " s");
            }
            return new Reading(text, confidence(text, audio.seconds()), detectedType, status, List.of(), notes);
        }
        return fallback(audio, text, localProblem, detectedType);
    }

    /** Local Whisper failed or found almost nothing: ask the gateway audio model. */
    private Reading fallback(AudioDecoder.Result audio, String localText, String localProblem, String detectedType) {
        float[] samples = audio.samples();
        boolean cut = audio.truncated();
        int maxSamples = MAX_FALLBACK_SECONDS * AudioDecoder.SAMPLE_RATE;
        if (samples.length > maxSamples) {
            samples = java.util.Arrays.copyOf(samples, maxSamples);
            cut = true;
        }
        Gateway.Answer answer = gateway.transcribe(WavEncoder.encode(samples, AudioDecoder.SAMPLE_RATE), "wav");
        String gatewayText = answer.ok() ? clean(List.of(new WhisperTranscriber.Segment(0, 0, answer.text()))) : "";

        List<String> notes = new ArrayList<>();
        notes.add(localProblem);
        if (!gatewayText.isEmpty()) {
            double seconds = samples.length / (double) AudioDecoder.SAMPLE_RATE;
            notes.add("gateway fallback used" + (answer.model() != null ? " (" + answer.model() + ")" : ""));
            notes.add(String.format(Locale.ROOT, "%.1f s of audio", seconds));
            if (cut) notes.add("audio cut after " + (audio.truncated() ? MAX_SECONDS : MAX_FALLBACK_SECONDS) + " s");
            // A second model's answer is good but unverified: a little below local Whisper's best.
            double confidence = Math.min(0.8, confidence(gatewayText, seconds));
            return new Reading(gatewayText, confidence, detectedType,
                    cut ? Reading.Status.PARTIAL : Reading.Status.OK, List.of(), notes);
        }

        notes.add("gateway fallback failed: " + (answer.ok() ? "no speech recognised" : answer.problem()));
        if (localText != null && !localText.isEmpty()) {
            // Keep the little Whisper did hear, flagged as weak.
            return new Reading(localText, Math.min(0.3, confidence(localText, audio.seconds())), detectedType,
                    Reading.Status.PARTIAL, List.of(), notes);
        }
        return new Reading("", 0.0, detectedType, Reading.Status.UNREADABLE, List.of(), notes);
    }

    private static String clean(List<WhisperTranscriber.Segment> segments) {
        return segments.stream()
                .map(s -> NON_SPEECH.matcher(s.text()).replaceAll("").strip())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** Fewer than 3 words for 3+ s of audio, or under ~1 word per 7 s for longer audio. */
    static boolean almostNothing(String text, double seconds) {
        if (seconds < 3) return false;
        int words = text.split("\\s+").length;
        return words < Math.max(3, seconds * 0.15);
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
