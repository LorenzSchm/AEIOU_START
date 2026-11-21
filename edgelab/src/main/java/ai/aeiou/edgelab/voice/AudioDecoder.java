package ai.aeiou.edgelab.voice;

import java.io.ByteArrayInputStream;
import java.nio.FloatBuffer;
import java.util.Arrays;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

/**
 * Turns any audio file ffmpeg understands (mp3, wav, m4a, ogg, opus, flac, aac,
 * audio-only webm, ...) into 16 kHz mono float samples, which is what Whisper wants.
 * Uses the ffmpeg bundled with JavaCV; nothing is installed on the machine.
 */
public final class AudioDecoder {

    public static final int SAMPLE_RATE = 16_000;

    static {
        // ffmpeg prints a lot to stderr by default; keep only real errors.
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR);
    }

    /** The decoded audio, or why it could not be decoded. */
    public record Result(float[] samples, boolean truncated, String problem) {
        public double seconds() { return samples == null ? 0 : samples.length / (double) SAMPLE_RATE; }
        public boolean ok() { return problem == null; }
    }

    private final int maxSeconds;

    public AudioDecoder(int maxSeconds) {
        this.maxSeconds = maxSeconds;
    }

    /** Never throws: a broken file comes back as a Result with a problem. */
    public Result decode(byte[] bytes) {
        int maxSamples = maxSeconds * SAMPLE_RATE;
        float[] out = new float[Math.min(maxSamples, 30 * SAMPLE_RATE)];
        int n = 0;
        boolean truncated = false;
        // MP4/M4A may place the track index at the end. The in-memory stream supports seeking.
        try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(new ByteArrayInputStream(bytes), bytes.length)) {
            g.setOption("protocol_whitelist", "pipe");
            g.setSampleRate(SAMPLE_RATE);
            g.setAudioChannels(1);
            g.setSampleMode(FrameGrabber.SampleMode.FLOAT);
            g.start();
            if (g.getAudioChannels() <= 0) {
                return new Result(null, false, "no audio track found");
            }
            Frame f;
            while ((f = g.grabSamples()) != null) {
                if (f.samples == null || f.samples.length == 0) continue;
                FloatBuffer buf = (FloatBuffer) f.samples[0];
                int available = buf.remaining();
                if (n + available > maxSamples) {
                    available = maxSamples - n;
                    truncated = true;
                }
                if (n + available > out.length) {
                    out = Arrays.copyOf(out, Math.min(maxSamples, Math.max(out.length * 2, n + available)));
                }
                buf.get(out, n, available);
                n += available;
                if (truncated) break;
            }
            g.stop();
        } catch (Exception | LinkageError e) {
            return new Result(null, false, "could not decode audio: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
        if (n == 0) return new Result(null, false, "audio has no samples");
        return new Result(Arrays.copyOf(out, n), truncated, null);
    }
}
