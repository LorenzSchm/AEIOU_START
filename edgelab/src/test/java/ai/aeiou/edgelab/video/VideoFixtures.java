package ai.aeiou.edgelab.video;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;

import ai.aeiou.edgelab.photo.PhotoFixtures;
import ai.aeiou.edgelab.voice.AudioDecoder;

/**
 * Video test files, made in memory with the bundled ffmpeg (mpeg4 video + aac sound in mp4):
 * the skewed RFQ phone photo held still, with the Windows-voice RFQ recording as sound.
 */
public final class VideoFixtures {

    static final int WIDTH = 640;
    static final int HEIGHT = 480;
    static final int FPS = 5;

    private static byte[] withSound;
    private static byte[] silent;
    private static float[] voice;

    static {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR); // the encoder is chatty on stderr
    }

    private VideoFixtures() {}

    /** The skewed RFQ photo for the whole length, plus the spoken RFQ. */
    static synchronized byte[] rfqVideo() {
        if (withSound == null) {
            float[] audio = voiceSamples();
            double seconds = audio.length / (double) AudioDecoder.SAMPLE_RATE;
            withSound = mp4(List.of(rfqPhoto()), seconds, audio);
        }
        return withSound;
    }

    /** Same picture, no sound track at all. */
    static synchronized byte[] silentRfqVideo() {
        if (silent == null) silent = mp4(List.of(rfqPhoto()), 4.0, null);
        return silent;
    }

    /** {@code scenes} clearly different slides shown one after another, silent. */
    static byte[] slideShow(int scenes, double secondsPerScene) {
        List<BufferedImage> slides = new java.util.ArrayList<>();
        for (int i = 0; i < scenes; i++) slides.add(slide(i));
        return mp4(slides, scenes * secondsPerScene, null);
    }

    static float[] voiceSamples() {
        if (voice == null) {
            AudioDecoder.Result r = new AudioDecoder(60).decode(resource("/voice/rfq-en.wav"));
            if (!r.ok()) throw new IllegalStateException(r.problem());
            voice = r.samples();
        }
        return voice;
    }

    static BufferedImage rfqPhoto() {
        try {
            BufferedImage photo = ImageIO.read(new ByteArrayInputStream(PhotoFixtures.phonePhotoJpeg()));
            return scaled(photo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Slide i: its own background colour, a big number and a moving block, so no two look alike. */
    static BufferedImage slide(int i) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.getHSBColor(i / 10f, 0.5f, 0.9f));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(Color.BLACK);
        g.fillRect((i * 97) % (WIDTH - 200), (i * 61) % (HEIGHT - 200), 200, 200);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
        g.drawString("Slide " + (i + 1), 240, 420);
        g.dispose();
        return img;
    }

    /** Shows the pictures one after another, equal time each, over {@code seconds}. */
    static byte[] mp4(List<BufferedImage> pictures, double seconds, float[] audio16k) {
        try {
            Path tmp = Files.createTempFile("edgelab-video", ".mp4");
            try {
                try (FFmpegFrameRecorder rec = new FFmpegFrameRecorder(tmp.toFile(), WIDTH, HEIGHT,
                        audio16k != null ? 1 : 0);
                     Java2DFrameConverter conv = new Java2DFrameConverter()) {
                    rec.setFormat("mp4");
                    rec.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
                    rec.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
                    rec.setFrameRate(FPS);
                    rec.setVideoQuality(4);
                    if (audio16k != null) {
                        rec.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                        rec.setSampleRate(AudioDecoder.SAMPLE_RATE);
                        rec.setAudioBitrate(64_000);
                    }
                    rec.start();
                    int frames = (int) Math.ceil(seconds * FPS);
                    for (int f = 0; f < frames; f++) {
                        int scene = Math.min(pictures.size() - 1, f * pictures.size() / frames);
                        rec.record(conv.convert(pictures.get(scene)), avutil.AV_PIX_FMT_BGR24);
                    }
                    if (audio16k != null) {
                        int chunk = AudioDecoder.SAMPLE_RATE / 10;
                        for (int i = 0; i < audio16k.length; i += chunk) {
                            int n = Math.min(chunk, audio16k.length - i);
                            rec.recordSamples(AudioDecoder.SAMPLE_RATE, 1, FloatBuffer.wrap(java.util.Arrays.copyOfRange(audio16k, i, i + n)));
                        }
                    }
                    rec.stop();
                }
                return Files.readAllBytes(tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not make test video", e);
        }
    }

    private static BufferedImage scaled(BufferedImage src) {
        BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, WIDTH, HEIGHT, null);
        g.dispose();
        return out;
    }

    static byte[] resource(String name) {
        try (InputStream in = VideoFixtures.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing test resource " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the fixtures to a folder for the demo: ./gradlew videoFixtures */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(dir);
        Files.write(dir.resolve("rfq-video.mp4"), rfqVideo());
        Files.write(dir.resolve("rfq-video-silent.mp4"), silentRfqVideo());
        System.out.println("wrote rfq-video.mp4 and rfq-video-silent.mp4 to " + dir.toAbsolutePath());
    }
}
