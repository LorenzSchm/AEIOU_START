package ai.aeiou.edgelab;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

/** Samples up to three stills for OCR and transcribes a clip's audio track once. */
final class VideoReader implements FormatReader {
    private final FormatReader photo;
    private final FormatReader voice;

    VideoReader(FormatReader photo, FormatReader voice) {
        this.photo = photo;
        this.voice = voice;
    }

    @Override
    public Reading read(String filename, byte[] bytes, String type) {
        List<String> text = new ArrayList<>();
        List<Reading.Part> parts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean hasAudio = false;
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(bytes), bytes.length);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.setOption("protocol_whitelist", "pipe");
            grabber.start();
            hasAudio = grabber.hasAudio();
            if (grabber.hasVideo()) {
                int width = grabber.getImageWidth();
                int height = grabber.getImageHeight();
                if (width <= 0 || height <= 0 || (long) width * height > 32_000_000)
                    throw new IllegalArgumentException("unsupported frame dimensions");
                double scale = Math.min(1.0, 1600.0 / Math.max(width, height));
                grabber.setImageWidth(Math.max(1, (int) (width * scale)));
                grabber.setImageHeight(Math.max(1, (int) (height * scale)));
                long duration = grabber.getLengthInTime();
                int samples = duration >= 1_000_000 ? 3 : 1;
                long previous = -1;
                for (int i = 0; i < samples; i++) {
                    if (i > 0) grabber.setVideoTimestamp((duration / samples) * i);
                    Frame frame = grabber.grabImage();
                    if (frame == null || frame.timestamp == previous) continue;
                    previous = frame.timestamp;
                    String name = "frame-" + frame.timestamp / 1000 + "ms.png";
                    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                    ImageIO.write(converter.convert(frame), "png", encoded);
                    add(name, photo.read(name, encoded.toByteArray(), "image/png"), text, parts, notes);
                }
                notes.add("visual content sampled from individual frames");
            }
        } catch (Exception | LinkageError e) {
            notes.add("video decoding incomplete: " + e.getClass().getSimpleName());
        }
        if (hasAudio) {
            try {
                add("audio", voice.read(filename, bytes, type), text, parts, notes);
            } catch (Exception | LinkageError e) {
                notes.add("audio transcription failed: " + e.getClass().getSimpleName());
            }
        }
        if (text.isEmpty()) notes.add("no readable frame or transcript");
        double confidence = parts.stream().filter(p -> p.status() != Reading.Status.UNREADABLE)
                .mapToDouble(Reading.Part::confidence).min().orElse(0);
        Reading.Status status = text.isEmpty() ? Reading.Status.UNREADABLE
                : notes.isEmpty() ? Reading.Status.OK : Reading.Status.PARTIAL;
        return new Reading(String.join("\n", text), confidence, type, status, parts, notes);
    }

    private static void add(String name, Reading result, List<String> text, List<Reading.Part> parts,
                            List<String> notes) {
        parts.add(new Reading.Part(name, result.detectedType(), result.status(), result.confidence()));
        if (!result.text().isBlank()) text.add("[" + name + "]\n" + result.text());
        if (result.status() != Reading.Status.OK) notes.add(name + ": " + String.join(", ", result.notes()));
    }
}
