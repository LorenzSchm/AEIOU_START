package ai.aeiou.edgelab.voice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;

/**
 * Local speech-to-text with whisper.cpp (via whisper-jni, which bundles the native
 * engine for Windows x64, macOS arm64/x64 and Linux). The model is loaded once per JVM.
 * Language is detected automatically, so German and English both work.
 */
public final class WhisperTranscriber implements LocalTranscriber {

    /** One recognised stretch of speech, times in milliseconds. */
    public record Segment(long startMs, long endMs, String text) {}

    private final WhisperModelCache models;
    private WhisperJNI whisper;
    private WhisperContext context;

    public WhisperTranscriber(WhisperModelCache models) {
        this.models = models;
    }

    /** Downloads (first time) and loads the model. Safe to call more than once. */
    public synchronized void load() throws IOException, InterruptedException {
        if (context != null) return;
        WhisperJNI.loadLibrary(msg -> { });
        WhisperJNI.setLibraryLogger(null); // silence whisper.cpp's model-loading chatter
        WhisperJNI w = new WhisperJNI();
        WhisperContext ctx = w.init(models.ensure());
        if (ctx == null) throw new IOException("Whisper could not load model " + models.file());
        whisper = w;
        context = ctx;
    }

    /** whisper.cpp keeps per-run state in the context, so runs are serialised. */
    @Override
    public synchronized List<Segment> transcribe(float[] samples) throws IOException, InterruptedException {
        load();
        WhisperFullParams p = new WhisperFullParams(WhisperSamplingStrategy.BEAN_SEARCH);
        p.language = "auto";
        p.translate = false;
        p.nThreads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        p.printProgress = false;
        p.printRealtime = false;
        p.printTimestamps = false;
        p.printSpecial = false;
        p.suppressBlank = true;
        p.suppressNonSpeechTokens = true;
        int rc = whisper.full(context, p, samples, samples.length);
        if (rc != 0) throw new IOException("Whisper failed with code " + rc);
        int n = whisper.fullNSegments(context);
        List<Segment> segments = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // whisper.cpp timestamps are in 10 ms units.
            segments.add(new Segment(
                    whisper.fullGetSegmentTimestamp0(context, i) * 10,
                    whisper.fullGetSegmentTimestamp1(context, i) * 10,
                    whisper.fullGetSegmentText(context, i)));
        }
        return segments;
    }
}
