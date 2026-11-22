package ai.aeiou.edgelab;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerReadTest {
    private final Reading.Part packing = new Reading.Part("packing.txt", "text/plain", Reading.Status.OK, 1);
    private final String text = ReadSession.header("packing.txt") + "\nPacking List";

    @Test
    void onlyPassesOneCompleteDocumentWithoutItsFilename() {
        assertEquals("Packing List", WorkerRead.acceptedText(archive(Reading.Status.OK, List.of(packing))));
        assertNull(WorkerRead.acceptedText(archive(Reading.Status.PARTIAL, List.of(packing))));
        var missing = new Reading.Part("missing", "application/pdf", Reading.Status.UNREADABLE, 0);
        assertNull(WorkerRead.acceptedText(archive(Reading.Status.OK, List.of(packing, missing))));
        assertNull(WorkerRead.acceptedText(archive(Reading.Status.OK, List.of(packing, packing))));
    }

    private Reading archive(Reading.Status status, List<Reading.Part> parts) {
        return new Reading(text, 1, "application/zip", status, parts, List.of());
    }
}
