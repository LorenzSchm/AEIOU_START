package ai.aeiou.edgelab.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.FormatReader;
import ai.aeiou.edgelab.ReadSession;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.Router;
import ai.aeiou.edgelab.gateway.Gateway;
import ai.aeiou.edgelab.office.OfficeReader;
import ai.aeiou.edgelab.photo.PhotoReader;

/** read(filename, bytes) on files inside files, with the gateway faked. Never the real gateway. */
class ContainerWalkerTest {

    static final String PHOTO_TEXT = "Request for quotation\nMUC -> JFK\n4 pieces, 730 kg";

    /** Counts images and answers with fixed text. */
    static final class FakeGateway implements Gateway {
        final List<byte[]> images = Collections.synchronizedList(new ArrayList<>());

        @Override public Answer transcribe(byte[] audio, String format) {
            throw new AssertionError("no audio expected");
        }

        @Override public Answer copyText(byte[] image, String mimeType) {
            images.add(image);
            return new Answer(PHOTO_TEXT, 0.9, "fake-vision", null);
        }
    }

    static final FormatReader TEXT = (name, bytes, type) ->
            Reading.ok(new String(bytes, StandardCharsets.UTF_8), 1.0, type);

    final FakeGateway gateway = new FakeGateway();

    Router router() {
        return Router.empty()
                .withPhoto(new PhotoReader(gateway))
                .withPlainText(TEXT)
                .withContainer(new ContainerWalker())
                .withOffice(ContainerWalker.openEmbedded(new OfficeReader()));
    }

    Reading read(String name, byte[] bytes) {
        return new EdgeReader(router(), EdgeReader.DEFAULT_MAX_BYTES).read(name, bytes);
    }

    static String dump(Reading r) {
        return "\n" + r.status() + " " + r.parts() + "\n" + r.notes() + "\n" + r.text();
    }

    static Reading.Part part(Reading r, String path) {
        return r.parts().stream().filter(p -> p.name().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no part " + path + dump(r)));
    }

    // ---- each part under a header ------------------------------------------------------

    @Test
    void zipWithPackingListReadsEachPartUnderItsHeader() {
        Reading r = read("shipment.zip", ContainerFixtures.zipWithPackingList());

        assertEquals(Reading.Status.OK, r.status(), dump(r));
        assertEquals("application/zip", r.detectedType());
        String text = r.text();
        int xlsx = text.indexOf("=== part: packing-list.xlsx ===");
        int txt = text.indexOf("=== part: notes.txt ===");
        assertTrue(xlsx >= 0 && txt > xlsx, dump(r));
        assertTrue(text.indexOf("PL-7781") > xlsx && text.indexOf("PL-7781") < txt, dump(r));
        assertTrue(text.indexOf("412.5") > xlsx, dump(r));
        assertTrue(text.indexOf(ContainerFixtures.PACKING_TXT) > txt, dump(r));
        assertEquals(2, r.parts().size(), dump(r));
        assertTrue(r.confidence() > 0.8, dump(r));
    }

    @Test
    void emlWithForwardedRfqReadsBodyThenTheForwardedMail() {
        Reading r = read("fwd.eml", ContainerFixtures.emlWithForwardedRfq());

        assertEquals(Reading.Status.OK, r.status(), dump(r));
        String text = r.text();
        assertTrue(text.startsWith("Hi team") || text.indexOf("Hi team") < text.indexOf("=== part:"), dump(r));
        int header = text.indexOf("=== part: rfq.eml ===");
        assertTrue(header >= 0, dump(r));
        assertTrue(text.indexOf(ContainerFixtures.FORWARDED_RFQ) > header, dump(r));
        assertEquals(1, text.split(java.util.regex.Pattern.quote(ContainerFixtures.FORWARDED_RFQ), -1).length - 1,
                "forwarded text appears once" + dump(r));
        assertEquals("message/rfc822", part(r, "rfq.eml").detectedType().split(";")[0]);
    }

    @Test
    void zipAttachedToAMailIsOpenedWithNestedHeaders() {
        Reading r = read("docs.eml", ContainerFixtures.emlWithZip());

        assertEquals(Reading.Status.OK, r.status(), dump(r));
        assertTrue(r.text().contains("Documents attached."), dump(r));
        assertTrue(r.text().contains("=== part: docs.zip > packing-list.xlsx ==="), dump(r));
        assertTrue(r.text().contains("=== part: docs.zip > notes.txt ==="), dump(r));
        assertEquals(List.of("docs.zip", "docs.zip > packing-list.xlsx", "docs.zip > notes.txt"),
                r.parts().stream().map(Reading.Part::name).toList());
    }

    // ---- embedded pictures ---------------------------------------------------------------

    @Test
    void pictureInsidePptxIsReadByThePhotoReader() {
        Reading r = read("request.pptx", ContainerFixtures.pptxWithPhoto());

        assertEquals(1, gateway.images.size(), dump(r));
        assertEquals(Reading.Status.OK, r.status(), dump(r));
        assertTrue(r.text().contains("Customer request, see photo"), dump(r));
        assertTrue(r.text().contains("=== part: "), dump(r));
        assertTrue(r.text().contains(PHOTO_TEXT), dump(r));
        assertEquals(1, r.parts().size(), dump(r));
        assertTrue(r.parts().get(0).detectedType().startsWith("image/"), dump(r));
        assertFalse(r.notes().stream().anyMatch(n -> n.contains("not opened")), dump(r));
    }

    @Test
    void pictureInsideDocxIsReadByThePhotoReader() {
        Reading r = read("booking.docx", ContainerFixtures.docxWithPhoto());

        assertEquals(1, gateway.images.size(), dump(r));
        assertTrue(r.text().contains("Booking request"), dump(r));
        assertTrue(r.text().contains(PHOTO_TEXT), dump(r));
        assertEquals(Reading.Status.OK, r.parts().get(0).status());
    }

    @Test
    void officeFileWithoutEmbeddedFilesIsUnchanged() {
        byte[] xlsx = ContainerFixtures.packingListXlsx();
        Reading wrapped = read("packing.xlsx", xlsx);
        Reading plain = new OfficeReader().read("packing.xlsx", xlsx, wrapped.detectedType());

        assertEquals(plain, wrapped);
    }

    // ---- limits ----------------------------------------------------------------------------

    @Test
    void nestingStopsAtThreeLevelsAndListsWhatWasSkipped() {
        Reading r = read("nested.zip", ContainerFixtures.nestedZip(5));

        assertEquals(Reading.Status.PARTIAL, r.status(), dump(r));
        for (int level = 1; level <= 3; level++) {
            assertTrue(r.text().contains("text at level " + level), "level " + level + dump(r));
        }
        assertFalse(r.text().contains("text at level 4"), dump(r));
        assertTrue(r.text().contains("=== part: level-2.zip > level-3.zip > level-3.txt ==="), dump(r));
        String note = r.notes().stream().filter(n -> n.contains("deeper than 3 levels")).findFirst()
                .orElseThrow(() -> new AssertionError(dump(r)));
        assertTrue(note.contains("level-2.zip > level-3.zip > level-4.zip > level-4.txt"), note);
        assertTrue(note.contains("level-2.zip > level-3.zip > level-4.zip > level-5.zip"), note);
        assertEquals(Reading.Status.OK, part(r, "level-2.zip > level-3.zip > level-3.txt").status());
    }

    @Test
    void atMostTwentyFilesAreReadAndTheRestListed() {
        Reading r = read("thirty.zip", ContainerFixtures.zipWithFiles(30));

        assertEquals(Reading.Status.PARTIAL, r.status(), dump(r));
        assertEquals(ReadSession.MAX_FILES, r.parts().size(), dump(r));
        assertTrue(r.text().contains("content of file 20"), dump(r));
        assertFalse(r.text().contains("content of file 21"), dump(r));
        String note = r.notes().stream().filter(n -> n.contains("limit of 20 files")).findFirst()
                .orElseThrow(() -> new AssertionError(dump(r)));
        assertTrue(note.startsWith("10 part(s)"), note);
        assertTrue(note.contains("file-21.txt"), note);
    }

    @Test
    void theTwentyFileLimitCountsPartsAtEveryDepth() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("a.zip", ContainerFixtures.zipWithFiles(15));
        m.put("b.zip", ContainerFixtures.zipWithFiles(15));
        Reading r = read("two.zip", ContainerFixtures.zip(m));

        assertEquals(ReadSession.MAX_FILES, r.parts().size(), dump(r));
        assertEquals(Reading.Status.PARTIAL, r.status(), dump(r));
    }

    // ---- parts keep their own status ---------------------------------------------------------

    @Test
    void eachPartKeepsItsOwnStatusAndConfidence() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("good.txt", ContainerFixtures.text("a readable line"));
        m.put("empty.bin", new byte[0]);
        Reading r = read("mixed.zip", ContainerFixtures.zip(m));

        assertEquals(Reading.Status.PARTIAL, r.status(), dump(r));
        Reading.Part good = part(r, "good.txt");
        assertEquals(Reading.Status.OK, good.status());
        assertEquals(1.0, good.confidence());
        Reading.Part empty = part(r, "empty.bin");
        assertEquals(Reading.Status.UNREADABLE, empty.status());
        assertEquals(0.0, empty.confidence());
        assertTrue(r.notes().contains("empty.bin: empty input"), dump(r));
    }

    // ---- broken and encrypted archives ------------------------------------------------------

    @Test
    void encryptedArchiveIsUnreadableWithANote() {
        Reading r = read("secret.zip", ContainerFixtures.encryptedZip());

        assertEquals(Reading.Status.UNREADABLE, r.status(), dump(r));
        assertFalse(r.notes().isEmpty());
        assertTrue(r.notes().get(0).contains("password") || r.notes().get(0).contains("could not open"), dump(r));
    }

    @Test
    void brokenArchiveIsUnreadableWithANote() {
        Reading r = read("broken.zip", ContainerFixtures.brokenZip());

        assertEquals(Reading.Status.UNREADABLE, r.status(), dump(r));
        assertFalse(r.notes().isEmpty(), dump(r));
    }

    @Test
    void brokenArchiveInsideAZipIsAnUnreadablePart() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("ok.txt", ContainerFixtures.text("still readable"));
        m.put("bad.zip", ContainerFixtures.brokenZip());
        Reading r = read("outer.zip", ContainerFixtures.zip(m));

        assertEquals(Reading.Status.PARTIAL, r.status(), dump(r));
        assertTrue(r.text().contains("still readable"), dump(r));
        assertEquals(Reading.Status.UNREADABLE, part(r, "bad.zip").status());
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("bad.zip: ")), dump(r));
    }

    // ---- 20-second budget ----------------------------------------------------------------------

    @Test
    void readThatRunsOutOfTimeReturnsPartialWithTextSoFar() {
        FormatReader slow = (name, bytes, type) -> {
            Thread.sleep(300);
            return Reading.ok(new String(bytes, StandardCharsets.UTF_8), 1.0, type);
        };
        Router router = router().withPlainText(slow);
        EdgeReader reader = new EdgeReader(router, EdgeReader.DEFAULT_MAX_BYTES, Duration.ofMillis(1000));

        long start = System.nanoTime();
        Reading r = reader.read("slow.zip", ContainerFixtures.zipWithFiles(10));
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertTrue(ms < 1800, "took " + ms + " ms");
        assertEquals(Reading.Status.PARTIAL, r.status(), dump(r));
        assertTrue(r.notes().contains("timed out after 1 s"), dump(r));
        assertTrue(r.text().contains("=== part: file-01.txt ===\ncontent of file 1"), dump(r));
        assertFalse(r.text().contains("content of file 10"), dump(r));
    }

    @Test
    void singleSlowFileAlsoStopsAtTheBudget() {
        FormatReader stuck = (name, bytes, type) -> {
            Thread.sleep(10_000);
            return Reading.ok("never", 1.0, type);
        };
        EdgeReader reader = new EdgeReader(router().withPlainText(stuck), EdgeReader.DEFAULT_MAX_BYTES,
                Duration.ofMillis(300));

        Reading r = reader.read("a.txt", ContainerFixtures.text("hello there"));

        assertEquals(Reading.Status.PARTIAL, r.status(), dump(r));
        assertEquals(List.of("timed out after 0.3 s"), r.notes());
        assertEquals("", r.text());
    }

    @Test
    void defaultBudgetIsTwentySeconds() {
        assertEquals(Duration.ofSeconds(20), EdgeReader.DEFAULT_BUDGET);
    }

    @Test
    void standardWiringOpensZips() {
        Reading r = new EdgeReader().read("shipment.zip", ContainerFixtures.zipWithPackingList());

        assertEquals(Reading.Status.OK, r.status(), dump(r));
        assertTrue(r.text().contains("=== part: notes.txt ==="), dump(r));
    }
}
