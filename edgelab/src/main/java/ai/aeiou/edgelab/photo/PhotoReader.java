package ai.aeiou.edgelab.photo;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ai.aeiou.edgelab.FormatReader;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.gateway.Gateway;
import ai.aeiou.edgelab.gateway.HttpGateway;

/**
 * The photo slot: any image (jpg, png, heic, webp, tiff, gif, bmp) -> one small, upright
 * JPEG -> gateway vision model (low detail, fixed "copy, don't interpret" prompt,
 * {@link Gateway#COPY_TEXT_PROMPT}) -> text.
 *
 * <p>Confidence is the model's own 0..1 self-rating, lowered when only a few characters
 * came back. No key, a gateway error or no text at all come back UNREADABLE with a note.
 */
public final class PhotoReader implements FormatReader {

    /** Self-rating assumed when the model gives none. */
    static final double DEFAULT_RATING = 0.6;
    /** Letters/digits at which text length stops lowering confidence. */
    static final int FULL_LENGTH_CHARS = 40;
    /** Never fully trust a single model reading of a picture. */
    static final double MAX_CONFIDENCE = 0.95;

    private final Gateway gateway;

    public PhotoReader(Gateway gateway) {
        this.gateway = gateway;
    }

    /** Production reader on the gateway from settings (unavailable without a key). */
    public static PhotoReader standard() {
        return new PhotoReader(HttpGateway.fromSettings());
    }

    @Override
    public Reading read(String filename, byte[] bytes, String detectedType) {
        ImagePrep.Prepared prepared;
        try {
            prepared = ImagePrep.fromBytes(bytes, detectedType);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return Reading.unreadable(detectedType, "image could not be decoded: " + message(e));
        }
        return send(prepared, detectedType);
    }

    /**
     * Reads a picture that is already decoded and upright: a rendered PDF page, a video frame.
     * {@code detectedType} is what the Reading reports (e.g. the PDF's type).
     */
    public Reading readImage(BufferedImage image, String detectedType) {
        try {
            return send(ImagePrep.fromImage(image, List.of()), detectedType);
        } catch (IOException | RuntimeException e) {
            return Reading.unreadable(detectedType, "image could not be prepared: " + message(e));
        }
    }

    private Reading send(ImagePrep.Prepared prepared, String detectedType) {
        List<String> notes = new ArrayList<>(prepared.notes());
        Gateway.Answer answer = gateway.copyText(prepared.jpeg(), "image/jpeg");
        if (!answer.ok()) {
            notes.add("photo not read: " + answer.problem());
            return new Reading("", 0.0, detectedType, Reading.Status.UNREADABLE, List.of(), notes);
        }
        notes.add("vision model" + (answer.model() != null ? " " + answer.model() : "") + ", low detail");
        String text = answer.text().strip();
        if (visibleChars(text) == 0) {
            notes.add("no text found in the image");
            return new Reading("", 0.0, detectedType, Reading.Status.UNREADABLE, List.of(), notes);
        }
        if (answer.selfRating() == null) notes.add("model gave no self-rating");
        return new Reading(text, confidence(answer.selfRating(), text), detectedType,
                Reading.Status.OK, List.of(), notes);
    }

    /** Self-rating (or {@link #DEFAULT_RATING}) scaled down for very short text. */
    static double confidence(Double selfRating, String text) {
        double rating = selfRating != null ? selfRating : DEFAULT_RATING;
        int visible = visibleChars(text);
        double lengthFactor = Math.min(1.0, 0.4 + 0.6 * visible / FULL_LENGTH_CHARS);
        return Math.min(MAX_CONFIDENCE, Math.max(0.0, rating * lengthFactor));
    }

    static int visibleChars(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) n++;
        }
        return n;
    }

    private static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
