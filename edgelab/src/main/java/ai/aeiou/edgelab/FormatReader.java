package ai.aeiou.edgelab;

/**
 * Reads one kind of file. Each slot in {@link Router} holds one of these.
 * Implementations may throw; {@link EdgeReader} catches everything.
 */
@FunctionalInterface
public interface FormatReader {

    Reading read(String filename, byte[] bytes, String detectedType) throws Exception;

    /** Placeholder for a slot whose reader is not built yet. */
    static FormatReader notYetSupported(String slotName) {
        return (filename, bytes, detectedType) ->
                Reading.unreadable(detectedType, "no " + slotName + " reader yet for " + detectedType);
    }
}
