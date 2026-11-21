package ai.aeiou.edgelab;

import java.util.List;

/**
 * What the reader got out of one file.
 *
 * @param text         plain text, never null ("" when nothing could be read)
 * @param confidence   0..1, how much the caller should trust {@code text}
 * @param detectedType MIME type detected from the bytes, not from the file ending
 * @param status       ok, partial or unreadable
 * @param parts        one entry per file found inside this one (empty for plain files)
 * @param notes        short human-readable reasons, e.g. "empty input"
 */
public record Reading(
        String text,
        double confidence,
        String detectedType,
        Status status,
        List<Part> parts,
        List<String> notes) {

    public enum Status { OK, PARTIAL, UNREADABLE }

    /** A file found inside another file (zip entry, email attachment, embedded image). */
    public record Part(String name, String detectedType, Status status, double confidence) {}

    public Reading {
        text = text == null ? "" : text;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        detectedType = detectedType == null ? "application/octet-stream" : detectedType;
        status = status == null ? Status.UNREADABLE : status;
        parts = parts == null ? List.of() : List.copyOf(parts);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static Reading ok(String text, double confidence, String detectedType) {
        return new Reading(text, confidence, detectedType, Status.OK, List.of(), List.of());
    }

    public static Reading unreadable(String detectedType, String note) {
        return new Reading("", 0.0, detectedType, Status.UNREADABLE, List.of(), List.of(note));
    }
}
