package ai.aeiou.edgelab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class EdgeReaderTest {

    static final Path KIT = Path.of("..", "start-kit");
    final EdgeReader reader = new EdgeReader();

    @Test
    void readsStartKitTxtTariff() throws Exception {
        byte[] bytes = Files.readAllBytes(KIT.resolve("yellowtail-air-freight-tariff-2026-11.txt"));

        Reading r = reader.read("yellowtail-air-freight-tariff-2026-11.txt", bytes);

        assertEquals(Reading.Status.OK, r.status());
        assertEquals("text/plain", Router.baseType(r.detectedType()));
        assertTrue(r.text().contains("Yellowtail Air Freight"), r.text());
        assertEquals(1.0, r.confidence());
        assertTrue(r.parts().isEmpty());
    }

    @Test
    void textWithPdfEndingIsStillText() {
        byte[] bytes = "RFQ: 120 kg MUC -> JFK, 3 pieces\n".getBytes(StandardCharsets.UTF_8);

        Reading r = reader.read("rfq.pdf", bytes);

        assertEquals("text/plain", Router.baseType(r.detectedType()));
        assertEquals(Reading.Status.OK, r.status());
        assertTrue(r.text().contains("MUC -> JFK"));
    }

    @Test
    void readsUtf8Umlauts() {
        Reading r = reader.read("anfrage.txt", "Grüße aus München, 5 Paletten\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(Reading.Status.OK, r.status());
        assertTrue(r.text().contains("Grüße aus München"), r.text());
    }

    @Test
    void emptyInputIsUnreadableWithNote() {
        Reading r = reader.read("empty.txt", new byte[0]);

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertEquals(0.0, r.confidence());
        assertFalse(r.notes().isEmpty());
    }

    @Test
    void nullInputIsUnreadableWithNote() {
        Reading r = reader.read(null, null);

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertFalse(r.notes().isEmpty());
    }

    @Test
    void oversizedInputIsUnreadableWithNote() {
        EdgeReader small = new EdgeReader(Router.standard(), 10);

        Reading r = small.read("big.txt", "more than ten bytes of text".getBytes(StandardCharsets.UTF_8));

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertTrue(r.notes().get(0).contains("too large"), r.notes().toString());
    }

    @Test
    void unknownBinaryIsUnreadableWithItsType() {
        byte[] junk = {0x00, 0x13, (byte) 0xFE, 0x7F, 0x00, 0x01, (byte) 0x9C, 0x00, 0x42, 0x00};

        Reading r = reader.read("mystery.txt", junk);

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertEquals("application/octet-stream", r.detectedType());
        assertFalse(r.notes().isEmpty());
    }

    @Test
    void pdfIsDetectedButNotYetReadable() throws Exception {
        byte[] bytes = Files.readAllBytes(KIT.resolve("cobalt-skylift-tariff-2026-11.pdf"));

        Reading r = reader.read("tariff.bin", bytes);

        assertEquals("application/pdf", r.detectedType());
        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertFalse(r.notes().isEmpty());
    }

    @Test
    void jpgIsDetectedFromBytesEvenWithTxtEnding() throws Exception {
        byte[] bytes = Files.readAllBytes(KIT.resolve("danube-wings-cargo-tariff-2026-11-p1.jpg"));

        Reading r = reader.read("not-really.txt", bytes);

        assertEquals("image/jpeg", r.detectedType());
        assertEquals(Reading.Status.UNREADABLE, r.status());
    }

    @Test
    void aFailingReaderBecomesUnreadableInsteadOfThrowing() {
        Router broken = Router.standard().withPlainText((name, bytes, type) -> {
            throw new IllegalStateException("boom");
        });

        Reading r = new EdgeReader(broken, EdgeReader.DEFAULT_MAX_BYTES)
                .read("a.txt", "hello".getBytes(StandardCharsets.UTF_8));

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertTrue(r.notes().get(0).contains("boom"), r.notes().toString());
    }
}
