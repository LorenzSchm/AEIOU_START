package ai.aeiou.edgelab.photo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

/**
 * Gets any image ready for the vision model: decode (ImageIO with TwelveMonkeys for
 * WebP/TIFF/odd JPEGs, the bundled ffmpeg for HEIC/HEIF), turn upright from EXIF,
 * downscale, flatten onto white and encode as JPEG. Every step leaves a short note.
 */
final class ImagePrep {

    /**
     * Longest edge sent. Low-detail mode bills a flat ~85 tokens and the model looks at a
     * 512 px version anyway, so anything larger only costs upload time; 1024 leaves the
     * gateway a sharper source to shrink from than 512 would.
     */
    static final int MAX_EDGE = 1024;

    private static final float JPEG_QUALITY = 0.85f;

    /** The JPEG to send plus what was done to get it. */
    record Prepared(byte[] jpeg, int width, int height, List<String> notes) {}

    private ImagePrep() {}

    /** Throws IOException with a readable message when the bytes are no picture we can decode. */
    static Prepared fromBytes(byte[] bytes, String detectedType) throws IOException {
        List<String> notes = new ArrayList<>();
        String type = detectedType == null ? "" : detectedType.toLowerCase(Locale.ROOT);
        BufferedImage img = null;
        if (!isHeif(type)) img = readWithImageIO(bytes);
        if (img == null) {
            try {
                img = HeicDecoder.decode(bytes);
            } catch (IOException | RuntimeException | LinkageError e) {
                throw new IOException(isHeif(type)
                        ? "HEIC image could not be decoded (" + e.getMessage() + ")"
                        : "not a picture that can be decoded", e);
            }
        }
        if (!type.startsWith("image/jpeg")) notes.add("converted " + shortType(type) + " to JPEG");
        int orientation = exifOrientation(bytes);
        if (orientation > 1 && orientation <= 8) {
            img = applyOrientation(img, orientation);
            notes.add("turned upright (EXIF orientation " + orientation + ")");
        }
        return fromImage(img, notes);
    }

    /** Downscale and encode an image that is already upright (rendered PDF page, video frame). */
    static Prepared fromImage(BufferedImage img, List<String> notesSoFar) throws IOException {
        List<String> notes = new ArrayList<>(notesSoFar);
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage scaled = downscale(img, MAX_EDGE);
        if (scaled.getWidth() != w || scaled.getHeight() != h) {
            notes.add("downscaled " + w + "x" + h + " to " + scaled.getWidth() + "x" + scaled.getHeight());
        }
        return new Prepared(jpeg(flatten(scaled)), scaled.getWidth(), scaled.getHeight(), notes);
    }

    private static boolean isHeif(String type) {
        return type.startsWith("image/heic") || type.startsWith("image/heif") || type.startsWith("image/avif");
    }

    private static String shortType(String type) {
        if (type.isBlank()) return "image";
        String t = type.contains(";") ? type.substring(0, type.indexOf(';')) : type;
        return t.startsWith("image/") ? t.substring(6).toUpperCase(Locale.ROOT) : t;
    }

    /** First frame (GIF, multi-page TIFF), or null if no ImageIO reader takes the bytes. */
    private static BufferedImage readWithImageIO(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            while (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    in.seek(0);
                    reader.setInput(in, false, true);
                    BufferedImage img = reader.read(0);
                    if (img != null) return img;
                } catch (IOException | RuntimeException e) {
                    // try the next reader
                } finally {
                    reader.dispose();
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** EXIF orientation tag (1 = upright), or 1 when there is none. */
    static int exifOrientation(byte[] bytes) {
        try {
            Metadata md = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes), bytes.length);
            for (ExifIFD0Directory dir : md.getDirectoriesOfType(ExifIFD0Directory.class)) {
                if (dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                    return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                }
            }
        } catch (Exception | LinkageError e) {
            // no or broken metadata: treat as upright
        }
        return 1;
    }

    /** The eight EXIF orientations: rotate and/or mirror so the picture is upright. */
    static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        if (orientation < 2 || orientation > 8) return img;
        int w = img.getWidth();
        int h = img.getHeight();
        boolean swap = orientation >= 5;
        int ow = swap ? h : w;
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx;
                int ny;
                switch (orientation) {
                    case 2 -> { nx = w - 1 - x; ny = y; }
                    case 3 -> { nx = w - 1 - x; ny = h - 1 - y; }
                    case 4 -> { nx = x; ny = h - 1 - y; }
                    case 5 -> { nx = y; ny = x; }
                    case 6 -> { nx = h - 1 - y; ny = x; }
                    case 7 -> { nx = h - 1 - y; ny = w - 1 - x; }
                    default -> { nx = y; ny = w - 1 - x; } // 8
                }
                dst[ny * ow + nx] = src[y * w + x];
            }
        }
        BufferedImage out = new BufferedImage(ow, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, ow, swap ? w : h, dst, 0, ow);
        return out;
    }

    /** Halving steps with bilinear filtering: sharp text without the aliasing of one big jump. */
    static BufferedImage downscale(BufferedImage img, int maxEdge) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (Math.max(w, h) <= maxEdge) return img;
        double scale = (double) maxEdge / Math.max(w, h);
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage cur = img;
        int cw = w;
        int ch = h;
        do {
            cw = Math.max(targetW, cw / 2);
            ch = Math.max(targetH, ch / 2);
            BufferedImage next = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, cw, ch);
            g.drawImage(cur, 0, 0, cw, ch, null);
            g.dispose();
            cur = next;
        } while (cw != targetW || ch != targetH);
        return cur;
    }

    /** Transparent parts become white; result is plain RGB that every JPEG writer takes. */
    private static BufferedImage flatten(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) return img;
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    private static byte[] jpeg(BufferedImage img) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("no JPEG writer available");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
