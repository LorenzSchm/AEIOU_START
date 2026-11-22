package ai.aeiou.edgelab.voice;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

/**
 * Phone-style m4a voice messages, made on the fly from voice/rfq-en.wav (AAC in m4a) so no
 * binaries are committed. "moov-at-end" is ffmpeg's default layout (index after the sound);
 * "faststart" moves the index to the front.
 */
final class M4aFixtures {

    static final String MOOV_AT_END = "moov-at-end";
    static final String FASTSTART = "faststart";

    private static final Map<String, byte[]> MADE = new ConcurrentHashMap<>();

    private M4aFixtures() {}

    static byte[] get(String layout) {
        return MADE.computeIfAbsent(layout, l -> {
            try {
                return toM4a(FASTSTART.equals(l));
            } catch (Exception e) {
                throw new IllegalStateException("could not make m4a fixture " + l, e);
            }
        });
    }

    /** The mp4 muxer needs a seekable output, hence the temp file (deleted right away). */
    private static byte[] toM4a(boolean faststart) throws Exception {
        byte[] wav;
        try (InputStream in = M4aFixtures.class.getResourceAsStream("/voice/rfq-en.wav")) {
            wav = in.readAllBytes();
        }
        Path tmp = Files.createTempFile("edgelab-voice-", ".m4a");
        try {
            try (FFmpegFrameGrabber in = new FFmpegFrameGrabber(new ByteArrayInputStream(wav), wav.length)) {
                in.setSampleMode(FrameGrabber.SampleMode.SHORT);
                in.start();
                try (FFmpegFrameRecorder out = new FFmpegFrameRecorder(tmp.toFile(), 1)) {
                    out.setFormat("ipod");
                    out.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                    out.setSampleRate(in.getSampleRate());
                    out.setAudioBitrate(64_000);
                    if (faststart) out.setOption("movflags", "faststart");
                    out.start();
                    Frame f;
                    while ((f = in.grabSamples()) != null) {
                        if (f.samples != null) out.recordSamples(in.getSampleRate(), 1, (ShortBuffer) f.samples[0]);
                    }
                    out.stop();
                }
                in.stop();
            }
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
