package ai.aeiou.edgelab;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

class ContainerReaderTest {
    private final EdgeReader reader = new EdgeReader();

    private byte[] zip(String name, byte[] first, int extra) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(first);
            for (int i = 0; i < extra; i++) {
                zip.putNextEntry(new ZipEntry(i + ".txt"));
                zip.write(("line " + i).getBytes(UTF_8));
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void dispatchesNestedFilesAndKeepsTheirNames() throws Exception {
        byte[] inner = zip("price.txt", "Freight EUR 704".getBytes(UTF_8), 0);
        Reading result = reader.read("bundle.zip", zip("inner.zip", inner, 1));
        assertEquals(Reading.Status.OK, result.status(), result.notes().toString());
        assertEquals(2, result.parts().size());
        assertTrue(result.text().contains("[price.txt]\nFreight EUR 704"), result.text());
        assertTrue(result.text().contains("line 0"));
    }

    @Test
    void readsAGzippedTarArchive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes))) {
            byte[] data = "Pickup EUR 145".getBytes(UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry("pickup.txt");
            entry.setSize(data.length);
            tar.putArchiveEntry(entry);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        Reading result = reader.read("bundle.tar.gz", bytes.toByteArray());
        assertEquals(Reading.Status.OK, result.status(), result.notes().toString());
        assertTrue(result.text().contains("Pickup EUR 145"), result.text());
    }

    @Test
    void reportsTruncationWithoutDroppingEarlierMembers() throws Exception {
        Reading result = reader.read("too-many.zip", zip("first.txt", "first offer".getBytes(UTF_8), 35));
        assertEquals(Reading.Status.PARTIAL, result.status(), result.notes().toString());
        assertEquals(32, result.parts().size());
        assertTrue(result.text().contains("first offer"));
    }

    @Test
    void readsMailBodyAndAttachedPackingList() {
        String eml = """
                From: forwarder@example.com
                To: dispatch@example.com
                Subject: pickup
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary=parts

                --parts
                Content-Type: text/plain; charset=UTF-8

                Please collect on Monday.
                --parts
                Content-Type: text/plain; name=packing.txt
                Content-Disposition: attachment; filename=packing.txt

                Pallet weight 240 kg
                --parts--
                """;
        Reading result = reader.read("message.eml", eml.replace("\n", "\r\n").getBytes(UTF_8));
        assertEquals(Reading.Status.OK, result.status(), result.notes().toString());
        assertTrue(result.text().contains("collect on Monday"), result.text());
        assertTrue(result.text().contains("240 kg"), result.text());
        assertTrue(result.parts().stream().anyMatch(p -> p.name().equals("packing.txt")));
    }
}
