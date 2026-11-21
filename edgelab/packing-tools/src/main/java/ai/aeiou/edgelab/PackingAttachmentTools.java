package ai.aeiou.edgelab;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** Content checks for two common packing-file problems. No network access or file writes. */
public final class PackingAttachmentTools {
    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern PACKING_HEADER = Pattern.compile(
            "(?iu)^(?:packing (?:list|specification)|packliste|shipper.s letter of instruction)\\b");

    private PackingAttachmentTools() {}

    public static boolean hasPdfHeader(byte[] bytes) {
        return bytes.length >= PDF.length && Arrays.equals(bytes, 0, PDF.length, PDF, 0, PDF.length);
    }

    /** Accept the complete decoded text only when it starts like a packing document. */
    public static Optional<String> recoverPackingText(byte[] bytes) {
        String text = TextDecoder.decode(bytes);
        if (!PACKING_HEADER.matcher(text.stripLeading()).find()) return Optional.empty();
        boolean damaged = text.chars().anyMatch(c -> c == 0xfffd ||
                (Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t'));
        return damaged ? Optional.empty() : Optional.of(text);
    }
}
