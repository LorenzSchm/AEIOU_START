package ai.aeiou.edgelab;

import java.util.List;

/** Reads text/plain: UTF-8 (with or without BOM), UTF-16 with BOM, else Windows-1252. */
final class PlainTextReader implements FormatReader {

    @Override
    public Reading read(String filename, byte[] bytes, String detectedType) {
        String text = TextDecoder.decode(bytes);
        if (text.isBlank()) {
            return Reading.unreadable(detectedType, "text file has no visible text");
        }
        return new Reading(text, 1.0, detectedType, Reading.Status.OK, List.of(), List.of());
    }
}
