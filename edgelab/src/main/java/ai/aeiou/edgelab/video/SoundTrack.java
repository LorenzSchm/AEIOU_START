package ai.aeiou.edgelab.video;

import java.io.ByteArrayInputStream;
import java.nio.FloatBuffer;
import java.util.Arrays;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import ai.aeiou.edgelab.voice.AudioDecoder;

/**
 * Pulls a video's sound track out as 16 kHz mono samples with the bundled ffmpeg.
 *
 * <p>Unlike the voice reader's own decoder, the input is opened seekable: an mp4/mov
 * often keeps its index ("moov") at the end, and ffmpeg must jump there before it can
 * read any sound.
 */
final class SoundTrack {

    /** The samples, or none (silent video), or why it failed. */
    record Result(float[] samples, boolean present, boolean truncated, String problem) {
        boolean ok() { return problem == null; }
        double seconds() { return samples == null ? 0 : samples.length / (double) AudioDecoder.SAMPLE_RATE; }
    }

    private SoundTrack() {}

    /** Never throws. */
    static Result extract(byte[] bytes, int maxSeconds) {
        int maxSamples = maxSeconds * AudioDecoder.SAMPLE_RATE;
        float[] out = new float[Math.min(maxSamples, 30 * AudioDecoder.SAMPLE_RATE)];
        int n = 0;
        boolean truncated = false;
        try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(new ByteArrayInputStream(bytes), bytes.length)) {
            g.setSampleRate(AudioDecoder.SAMPLE_RATE);
            g.setAudioChannels(1);
            g.setSampleMode(FrameGrabber.SampleMode.FLOAT);
            g.start();
            if (g.getAudioStream() < 0 || g.getAudioChannels() <= 0) {
                g.stop();
                return new Result(null, false, false, null);
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
            return new Result(null, true, false, "sound could not be decoded: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
        if (n == 0) return new Result(null, true, false, "sound track has no samples");
        return new Result(Arrays.copyOf(out, n), true, truncated, null);
    }
}
