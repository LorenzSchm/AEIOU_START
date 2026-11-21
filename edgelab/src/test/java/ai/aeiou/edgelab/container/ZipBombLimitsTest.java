package ai.aeiou.edgelab.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.Reading;

/** The collector caps what it keeps in memory: entry count, total bytes. Small sizes so it runs fast. */
class ZipBombLimitsTest {

    /** {@code count} text entries of {@code size} times 'a' each: tiny compressed, big inflated. */
    static byte[] bomb(int count, int size) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] zeros = new byte[size];
            java.util.Arrays.fill(zeros, (byte) 'a');
            for (int i = 1; i <= count; i++) {
                zip.putNextEntry(new ZipEntry("a-" + i + ".txt"));
                zip.write(zeros);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void entryCountIsCappedAndTheRestNoted() throws Exception {
        Embedded.Collected c = Embedded.collect("many.zip", bomb(300, 10), "application/zip",
                EdgeReader.DEFAULT_MAX_BYTES, new Embedded.Limits(25, 1L << 30));

        assertEquals(25, c.children().size());
        assertTrue(c.incomplete());
        assertTrue(c.notes().get(0).startsWith("275 embedded file(s) not read, limit of 25"), c.notes().toString());
    }

    @Test
    void totalBytesAreCappedAndTheRestNoted() throws Exception {
        int size = 1 << 20; // 1 MB of 'a' per entry, ~1 KB compressed
        Embedded.Collected c = Embedded.collect("bomb.zip", bomb(20, size), "application/zip",
                EdgeReader.DEFAULT_MAX_BYTES, new Embedded.Limits(1000, 5L * size));

        assertEquals(5, c.children().size());
        long kept = c.children().stream().mapToLong(Embedded.Child::size).sum();
        assertTrue(kept <= 5L * size, "kept " + kept);
        assertTrue(c.notes().stream().anyMatch(n -> n.startsWith("15 embedded file(s) not read, container content over")),
                c.notes().toString());
    }

    @Test
    void defaultsAreSane() {
        assertTrue(Embedded.Limits.DEFAULT.maxEntries() >= ai.aeiou.edgelab.ReadSession.MAX_FILES);
        assertEquals(100L * 1024 * 1024, Embedded.Limits.DEFAULT.maxTotalBytes());
    }

    @Test
    void skippedEntriesMakeTheReadPartialWithANote() throws Exception {
        // 120 tiny text files: over the default entry cap of 100.
        Reading r = new ContainerWalkerTest().read("lots.zip", bomb(120, 10));

        assertEquals(Reading.Status.PARTIAL, r.status(), ContainerWalkerTest.dump(r));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("limit of 100 files per container")),
                ContainerWalkerTest.dump(r));
    }
}
