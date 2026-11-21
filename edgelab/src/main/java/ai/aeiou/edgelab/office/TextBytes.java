package ai.aeiou.edgelab.office;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Text bytes whose type detection gave up on: is it text, and how to decode it. */
final class TextBytes {

    private TextBytes() {}

    private static final Charset CP1252 = Charset.forName("windows-1252");

    /** No NUL bytes and almost no control characters. */
    static boolean looksLikeText(byte[] b) {
        if (b.length == 0) return false;
        int bad = 0;
        for (byte x : b) {
            int c = x & 0xFF;
            if (c == 0) return false;
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != '\f') bad++;
        }
        return bad * 50 < b.length;
    }

    /** UTF-8 (with or without BOM) when it is valid UTF-8, else Windows-1252 (a superset of Latin-1's letters). */
    static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(b, CP1252);
        }
    }
}
