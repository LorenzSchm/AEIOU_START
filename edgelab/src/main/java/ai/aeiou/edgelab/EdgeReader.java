package ai.aeiou.edgelab;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.apache.tika.Tika;

/**
 * The one public entry point: {@code read(filename, bytes) -> Reading}.
 * Never throws for bad input; problems come back as status UNREADABLE with a note.
 */
public final class EdgeReader {

    public static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;

    private static final String OCTET_STREAM = "application/octet-stream";

    private final Router router;
    private final long maxBytes;
    private final Tika tika = new Tika();

    public EdgeReader() {
        this(Router.standard(), DEFAULT_MAX_BYTES);
    }

    public EdgeReader(Router router, long maxBytes) {
        this.router = router;
        this.maxBytes = maxBytes;
    }

    public Reading read(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Reading.unreadable(OCTET_STREAM, "empty input");
        }
        String type = detect(filename, bytes);
        if (bytes.length > maxBytes) {
            return Reading.unreadable(type,
                    "input too large: " + bytes.length + " bytes, limit is " + maxBytes);
        }
        try {
            Reading reading = router.readerFor(type).read(filename, bytes, type);
            return reading != null ? reading : Reading.unreadable(type, "reader returned nothing");
        } catch (Exception | LinkageError e) {
            return Reading.unreadable(type, "reader failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    /**
     * MIME type from the content. The file name is only a hint: Tika lets it refine
     * a content match (zip -> docx) but never overrule it (text stays text even as .pdf).
     */
    String detect(String filename, byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            String byContent = tika.detect(in);
            if (filename == null || filename.isBlank()) return byContent;
            String withHint = tika.detect(bytes, filename);
            // Only accept the name when it refines the content type, not when it replaces it.
            return isRefinement(withHint, byContent) ? withHint : byContent;
        } catch (Exception e) {
            return OCTET_STREAM;
        }
    }

    private static boolean isRefinement(String withHint, String byContent) {
        if (withHint.equals(byContent)) return true;
        if (byContent.equals(OCTET_STREAM)) return false; // unknown bytes: don't trust the ending
        var registry = org.apache.tika.mime.MimeTypes.getDefaultMimeTypes().getMediaTypeRegistry();
        return registry.isSpecializationOf(
                org.apache.tika.mime.MediaType.parse(withHint),
                org.apache.tika.mime.MediaType.parse(byContent));
    }
}
