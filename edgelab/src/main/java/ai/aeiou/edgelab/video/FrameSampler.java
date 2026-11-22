package ai.aeiou.edgelab.video;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * Picks at most {@link #MAX_FRAMES} evenly spread still frames from a video with the
 * bundled ffmpeg (JavaCV). Frames that look like one already kept (a slide held still)
 * and blank frames (black, one flat colour) are skipped, so they never cost a gateway call.
 */
public final class FrameSampler {

    public static final int MAX_FRAMES = 5;
    /** Mean grey difference (0..255) on a small thumbnail below which two frames count as the same. */
    static final double DUPLICATE_DIFF = 6.0;
    /** Grey standard deviation below which a frame is blank. */
    static final double BLANK_SPREAD = 4.0;
    private static final int THUMB_W = 32;
    private static final int THUMB_H = 24;

    static {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR);
    }

    /** Kept frames (maybe none: an audio-only file), how many were skipped, or why nothing worked. */
    public record Result(List<Frame> frames, int skipped, String problem) {
        public boolean ok() { return problem == null; }
    }

    /** One kept frame, upright, with where it was in the video (seconds). */
    public record Frame(BufferedImage image, double seconds) {}

    private final int maxFrames;

    public FrameSampler() {
        this(MAX_FRAMES);
    }

    public FrameSampler(int maxFrames) {
        this.maxFrames = Math.max(1, Math.min(MAX_FRAMES, maxFrames));
    }

    /** Never throws. */
    public Result sample(byte[] bytes) {
        try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(new ByteArrayInputStream(bytes), bytes.length);
             Java2DFrameConverter conv = new Java2DFrameConverter()) {
            g.setOption("protocol_whitelist", "pipe");
            g.start();
            if (g.getVideoStream() < 0 || g.getImageWidth() <= 0) {
                g.stop();
                return new Result(List.of(), 0, null);
            }
            double rotation = safeRotation(g);
            long length = g.getLengthInTime(); // microseconds, 0 or less when unknown

            List<BufferedImage> candidates = new ArrayList<>();
            List<Double> times = new ArrayList<>();
            if (length > 0) {
                for (int i = 0; i < maxFrames; i++) {
                    long target = (long) ((i + 0.5) * length / maxFrames);
                    g.setTimestamp(target);
                    org.bytedeco.javacv.Frame f = g.grabImage();
                    if (f == null || f.image == null) continue;
                    candidates.add(copy(conv.getBufferedImage(f)));
                    times.add(f.timestamp / 1_000_000.0);
                }
            }
            if (candidates.isEmpty()) {
                // Unknown length (or seeking failed): take the first frame of each second.
                if (length > 0) g.setTimestamp(0);
                long nextUs = 0;
                org.bytedeco.javacv.Frame f;
                int decoded = 0;
                while (candidates.size() < maxFrames && decoded < 30 * 60 && (f = g.grabImage()) != null) {
                    decoded++;
                    if (f.image == null || f.timestamp < nextUs) continue;
                    candidates.add(copy(conv.getBufferedImage(f)));
                    times.add(f.timestamp / 1_000_000.0);
                    nextUs = f.timestamp + 1_000_000;
                }
            }
            g.stop();

            List<Frame> kept = new ArrayList<>();
            List<int[]> thumbs = new ArrayList<>();
            int skipped = 0;
            for (int i = 0; i < candidates.size(); i++) {
                int[] thumb = thumbnail(candidates.get(i));
                if (spread(thumb) < BLANK_SPREAD || thumbs.stream().anyMatch(t -> diff(t, thumb) < DUPLICATE_DIFF)) {
                    skipped++;
                    continue;
                }
                thumbs.add(thumb);
                kept.add(new Frame(rotate(candidates.get(i), rotation), times.get(i)));
            }
            if (candidates.isEmpty()) return new Result(List.of(), 0, "no frame could be decoded");
            return new Result(kept, skipped, null);
        } catch (Exception | LinkageError e) {
            return new Result(List.of(), 0, "video could not be decoded: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    private static double safeRotation(FFmpegFrameGrabber g) {
        try {
            return g.getDisplayRotation();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /** Phone videos store the picture sideways plus a rotation flag; turn it upright. */
    static BufferedImage rotate(BufferedImage img, double displayRotation) {
        int quarter = (int) Math.round(-displayRotation / 90.0);
        quarter = ((quarter % 4) + 4) % 4; // clockwise quarter turns
        if (quarter == 0) return img;
        int w = img.getWidth();
        int h = img.getHeight();
        boolean swap = quarter % 2 == 1;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        AffineTransform t = new AffineTransform();
        switch (quarter) {
            case 1 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 2 -> { t.translate(w, h); t.rotate(Math.PI); }
            default -> { t.translate(0, w); t.rotate(-Math.PI / 2); }
        }
        g.drawImage(img, t, null);
        g.dispose();
        return out;
    }

    /** The converter reuses its buffer; keep our own RGB copy. */
    private static BufferedImage copy(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    static int[] thumbnail(BufferedImage img) {
        BufferedImage small = new BufferedImage(THUMB_W, THUMB_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, THUMB_W, THUMB_H, null);
        g.dispose();
        int[] grey = new int[THUMB_W * THUMB_H];
        for (int y = 0; y < THUMB_H; y++) {
            for (int x = 0; x < THUMB_W; x++) {
                int rgb = small.getRGB(x, y);
                grey[y * THUMB_W + x] = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
            }
        }
        return grey;
    }

    static double diff(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return sum / (double) a.length;
    }

    static double spread(int[] grey) {
        double mean = 0;
        for (int v : grey) mean += v;
        mean /= grey.length;
        double var = 0;
        for (int v : grey) var += (v - mean) * (v - mean);
        return Math.sqrt(var / grey.length);
    }
}
