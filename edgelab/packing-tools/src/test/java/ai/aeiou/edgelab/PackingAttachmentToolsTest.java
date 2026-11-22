package ai.aeiou.edgelab;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.*;
import org.junit.jupiter.api.Test;

class PackingAttachmentToolsTest {
    @Test void decodesTheWholePackingTextIncludingNonAsciiAndLastLine() {
        String original = "Packing List\nShipper: M\u00fcller\nUN 1950";
        for (Charset charset : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16,
                Charset.forName("windows-1252")}) {
            assertEquals(original, PackingAttachmentTools.recoverPackingText(original.getBytes(charset)).orElseThrow());
        }
    }

    @Test void rejectsBrokenTextAndOrdinaryMessages() {
        for (String text : new String[]{"Packing List\nvisible\0binary", "An email mentioning a Packing List",
                "%PDF-1.7\nPacking List", "PK\u0003\u0004Packing List", "Packing List\n\ufffd"}) {
            assertTrue(PackingAttachmentTools.recoverPackingText(text.getBytes(StandardCharsets.UTF_8)).isEmpty());
        }
    }

    @Test void prefersThePdfSignatureToTheFileEnding() {
        assertTrue(PackingAttachmentTools.hasPdfHeader("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(PackingAttachmentTools.hasPdfHeader("%PD".getBytes(StandardCharsets.US_ASCII)));
    }
}
