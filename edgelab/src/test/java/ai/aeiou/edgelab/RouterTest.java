package ai.aeiou.edgelab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class RouterTest {

    @Test
    void routesEachFamilyToItsNamedSlot() {
        assertEquals(Router.Slot.PHOTO, Router.slotFor("image/jpeg"));
        assertEquals(Router.Slot.PHOTO, Router.slotFor("image/heic"));
        assertEquals(Router.Slot.VOICE, Router.slotFor("audio/mpeg"));
        assertEquals(Router.Slot.VIDEO, Router.slotFor("video/mp4"));
        assertEquals(Router.Slot.CONTAINER, Router.slotFor("application/zip"));
        assertEquals(Router.Slot.CONTAINER, Router.slotFor("message/rfc822"));
        assertEquals(Router.Slot.PLAIN_TEXT, Router.slotFor("text/plain; charset=UTF-8"));
        assertEquals(Router.Slot.OFFICE, Router.slotFor("application/pdf"));
        assertEquals(Router.Slot.OFFICE, Router.slotFor(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals(Router.Slot.OFFICE, Router.slotFor("application/octet-stream"));
    }

    @Test
    void aReaderPluggedIntoASlotIsUsedThroughTheEntryPoint() {
        Router router = Router.standard().withPhoto((name, bytes, type) -> Reading.ok("photo text", 0.8, type));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

        Reading r = new EdgeReader(router, EdgeReader.DEFAULT_MAX_BYTES).read("x.png", png);

        assertEquals("image/png", r.detectedType());
        assertEquals("photo text", r.text());
        assertEquals(Reading.Status.OK, r.status());
    }

    @Test
    void emptySlotsReportNotYetSupported() {
        Reading r = new EdgeReader(Router.empty(), 100).read("a.txt", "hi there".getBytes(StandardCharsets.UTF_8));

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertEquals(1, r.notes().size());
    }
}
