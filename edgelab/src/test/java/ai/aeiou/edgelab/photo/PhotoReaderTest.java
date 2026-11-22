package ai.aeiou.edgelab.photo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.sun.net.httpserver.HttpServer;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.Router;
import ai.aeiou.edgelab.gateway.Gateway;
import ai.aeiou.edgelab.gateway.GatewaySettings;
import ai.aeiou.edgelab.gateway.HttpGateway;
import ai.aeiou.edgelab.gateway.Json;
import ai.aeiou.edgelab.office.OfficeReader;

/** read(filename, bytes) on images and scanned PDFs, with the gateway faked. Never the real gateway. */
class PhotoReaderTest {

    static final String CANNED = "Request for quotation\nFrom: Munich (MUC) To: Chicago (ORD)\n3 pallets, 340 kg, 2.1 cbm";

    /** Records every image it is sent; answers with fixed text. */
    static final class FakeGateway implements Gateway {
        final List<byte[]> images = new ArrayList<>();
        final List<String> mimeTypes = new ArrayList<>();
        private final Answer answer;

        FakeGateway(Answer answer) { this.answer = answer; }

        FakeGateway() { this(new Answer(CANNED, 0.9, "fake-vision", null)); }

        @Override public Answer transcribe(byte[] audio, String format) {
            throw new AssertionError("photo reader must not send audio");
        }

        @Override public Answer copyText(byte[] image, String mimeType) {
            images.add(image);
            mimeTypes.add(mimeType);
            return answer;
        }

        BufferedImage sentImage(int i) throws IOException {
            return ImageIO.read(new ByteArrayInputStream(images.get(i)));
        }
    }

    private static Reading read(Gateway gw, String name, byte[] bytes) {
        Router router = Router.empty()
                .withPhoto(new PhotoReader(gw))
                .withOffice(new ScanAwareOfficeReader(new OfficeReader(), new PhotoReader(gw)));
        return new EdgeReader(router, EdgeReader.DEFAULT_MAX_BYTES).read(name, bytes);
    }

    static Stream<Arguments> everyImageType() {
        BufferedImage page = PhotoFixtures.rfqPage(800, 600);
        return Stream.of(
                Arguments.of("rfq-phone-photo.jpg", PhotoFixtures.phonePhotoJpeg()),
                Arguments.of("rfq.png", PhotoFixtures.encode(page, "png")),
                Arguments.of("rfq.gif", PhotoFixtures.encode(page, "gif")),
                Arguments.of("rfq.bmp", PhotoFixtures.encode(page, "bmp")),
                Arguments.of("rfq.tiff", PhotoFixtures.encode(page, "tiff")),
                Arguments.of("rfq.webp", PhotoFixtures.resource("rfq.webp")),
                Arguments.of("example.heic", PhotoFixtures.resource("example.heic")),
                Arguments.of("danube-p1.jpg", PhotoFixtures.resource("danube-wings-cargo-tariff-2026-11-p1.jpg")),
                Arguments.of("danube-p2.jpg", PhotoFixtures.resource("danube-wings-cargo-tariff-2026-11-p2.jpg")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyImageType")
    void everyImageTypeSendsOneSmallJpegAndReturnsItsText(String name, byte[] bytes) throws Exception {
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, name, bytes);

        assertEquals(Reading.Status.OK, r.status(), r.notes().toString());
        assertEquals(CANNED, r.text());
        assertTrue(r.detectedType().startsWith("image/"), r.detectedType());
        assertEquals(1, gw.images.size(), "exactly one image per photo");
        assertEquals("image/jpeg", gw.mimeTypes.get(0));
        BufferedImage sent = gw.sentImage(0);
        assertTrue(Math.max(sent.getWidth(), sent.getHeight()) <= ImagePrep.MAX_EDGE,
                "sent " + sent.getWidth() + "x" + sent.getHeight());
        assertTrue(r.confidence() > 0.8, "confidence " + r.confidence());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("low detail")), r.notes().toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("convertedTypes")
    void heicTiffAndWebpAreConvertedToJpegBeforeSending(String name, byte[] bytes) {
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, name, bytes);

        assertEquals(Reading.Status.OK, r.status(), r.notes().toString());
        byte[] sent = gw.images.get(0);
        assertTrue((sent[0] & 0xFF) == 0xFF && (sent[1] & 0xFF) == 0xD8, "sent bytes are a JPEG");
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("converted ")), r.notes().toString());
    }

    static Stream<Arguments> convertedTypes() {
        return Stream.of(
                Arguments.of("rfq.tiff", PhotoFixtures.encode(PhotoFixtures.rfqPage(800, 600), "tiff")),
                Arguments.of("rfq.webp", PhotoFixtures.resource("rfq.webp")),
                Arguments.of("example.heic", PhotoFixtures.resource("example.heic")));
    }

    @Test
    void heicUsesTheFullPictureNotTheThumbnail() throws Exception {
        FakeGateway gw = new FakeGateway();

        read(gw, "example.heic", PhotoFixtures.resource("example.heic"));

        // The sample holds a 1280x854 picture and a 320x212 thumbnail.
        BufferedImage sent = gw.sentImage(0);
        assertEquals(1024, sent.getWidth());
    }

    @Test
    void largeScansAreDownscaledBeforeSending() throws Exception {
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, "danube.jpg", PhotoFixtures.resource("danube-wings-cargo-tariff-2026-11-p1.jpg"));

        assertEquals(ImagePrep.MAX_EDGE, gw.sentImage(0).getWidth());
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("downscaled ")), r.notes().toString());
    }

    @Test
    void exifRotationIsAppliedBeforeSending() throws Exception {
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, "sideways.jpg", PhotoFixtures.sidewaysJpegWithExifRotation());

        BufferedImage sent = gw.sentImage(0);
        assertEquals(800, sent.getWidth(), "upright is landscape");
        assertEquals(600, sent.getHeight());
        Color topLeft = new Color(sent.getRGB(20, 20));
        assertTrue(topLeft.getRed() > 200 && topLeft.getGreen() < 80, "red marker back in the top-left: " + topLeft);
        assertTrue(r.notes().contains("turned upright (EXIF orientation 6)"), r.notes().toString());
    }

    @Test
    void theInstructionThatIsSentForbidsInterpreting() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> bodies = new ArrayList<>();
        server.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":"
                    + Json.quote(CANNED + "\nCONFIDENCE: 90") + "}}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(out); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/gateway";
            Gateway local = new HttpGateway(new GatewaySettings(url, "test-key", "audio-x",
                    GatewaySettings.AudioMethod.CHAT, "vision-y", Duration.ofSeconds(5)));

            Reading r = read(local, "rfq.png", PhotoFixtures.encode(PhotoFixtures.rfqPage(800, 600), "png"));

            assertEquals(Reading.Status.OK, r.status(), r.notes().toString());
            assertEquals(CANNED, r.text());
            assertEquals(0.9, r.confidence(), 1e-9);
            assertEquals(1, bodies.size());
            String body = bodies.get(0);
            assertTrue(body.contains(Json.quote(Gateway.COPY_TEXT_PROMPT)), "fixed prompt was sent");
            assertTrue(Gateway.COPY_TEXT_PROMPT.contains("Do not interpret"));
            assertTrue(Gateway.COPY_TEXT_PROMPT.contains("Keep each table row on one line"));
            assertTrue(body.contains("\"detail\":\"low\""), "low detail");
            assertTrue(body.contains("data:image/jpeg;base64,"), "sent as JPEG");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void withoutAKeyImagesAreUnreadableWithAClearNote() {
        Reading r = read(Gateway.unavailable(GatewaySettings.KEY_NAME + " not set"), "rfq.png",
                PhotoFixtures.encode(PhotoFixtures.rfqPage(800, 600), "png"));

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertEquals("", r.text());
        assertTrue(r.notes().contains("photo not read: TEAM_API_KEY not set"), r.notes().toString());
    }

    @Test
    void theStandardRouterUsesThePhotoReader() {
        // Tests run with the gateway disabled, so this proves the wiring without a call.
        Reading r = new EdgeReader().read("rfq.png", PhotoFixtures.encode(PhotoFixtures.rfqPage(800, 600), "png"));

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("photo not read: gateway disabled")),
                r.notes().toString());
    }

    @Test
    void brokenImageIsUnreadableAndNothingIsSent() {
        FakeGateway gw = new FakeGateway();
        byte[] png = PhotoFixtures.encode(PhotoFixtures.rfqPage(800, 600), "png");
        byte[] broken = java.util.Arrays.copyOf(png, 60);

        Reading r = read(gw, "broken.png", broken);

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertTrue(r.notes().get(0).startsWith("image could not be decoded"), r.notes().toString());
        assertEquals(0, gw.images.size());
    }

    @Test
    void emptyAnswerMeansNoTextFound() {
        FakeGateway gw = new FakeGateway(new Gateway.Answer("", 0.9, "fake-vision", null));

        Reading r = read(gw, "blank.png", PhotoFixtures.encode(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "png"));

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertTrue(r.notes().contains("no text found in the image"), r.notes().toString());
    }

    @Test
    void confidenceFollowsSelfRatingAndDropsForVeryShortText() {
        byte[] png = PhotoFixtures.encode(PhotoFixtures.rfqPage(800, 600), "png");
        Reading sure = read(new FakeGateway(new Gateway.Answer(CANNED, 0.9, "m", null)), "a.png", png);
        Reading unsure = read(new FakeGateway(new Gateway.Answer(CANNED, 0.3, "m", null)), "a.png", png);
        Reading shortText = read(new FakeGateway(new Gateway.Answer("MUC", 0.9, "m", null)), "a.png", png);
        Reading unrated = read(new FakeGateway(new Gateway.Answer(CANNED, null, "m", null)), "a.png", png);

        assertEquals(0.9, sure.confidence(), 1e-9);
        assertEquals(0.3, unsure.confidence(), 1e-9);
        assertTrue(shortText.confidence() < 0.5, "short text: " + shortText.confidence());
        assertEquals(PhotoReader.DEFAULT_RATING, unrated.confidence(), 1e-9);
    }

    // --- scanned PDFs -------------------------------------------------------------

    @Test
    void scannedOnlyPdfReturnsItsPageTextThroughThePhotoReader() throws Exception {
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, "packing-list-scan.pdf", PhotoFixtures.scannedOnlyPdf());

        assertEquals("application/pdf", r.detectedType());
        assertEquals(Reading.Status.OK, r.status(), r.notes().toString());
        assertEquals(CANNED, r.text());
        assertEquals(1, gw.images.size());
        assertTrue(r.notes().contains("page 1 was a scan, read by the photo reader"), r.notes().toString());
        assertTrue(r.notes().stream().noneMatch(n -> n.endsWith("looks scanned")), r.notes().toString());
        BufferedImage sent = gw.sentImage(0);
        assertTrue(sent.getHeight() > sent.getWidth(), "rendered A4 page is portrait");
    }

    @Test
    void mixedPdfMergesTextPagesAndScannedPagesInOrder() throws Exception {
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, "mixed.pdf", PhotoFixtures.textThenScannedPdf());

        assertEquals(Reading.Status.OK, r.status(), r.notes().toString());
        int textPage = r.text().indexOf("Packing list page one");
        int scanPage = r.text().indexOf("Request for quotation");
        assertTrue(textPage >= 0 && scanPage > textPage, r.text());
        assertEquals(1, gw.images.size(), "only the scanned page is sent");
    }

    @Test
    void pdfWithATextLayerSendsNothing() throws Exception {
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, "text.pdf", PhotoFixtures.textOnlyPdf());

        assertEquals(Reading.Status.OK, r.status());
        assertTrue(r.text().contains("Packing list page one"), r.text());
        assertEquals(0, gw.images.size());
    }

    @Test
    void scannedPdfWithoutAKeyStaysUnreadableWithTheReason() throws Exception {
        Reading r = read(Gateway.unavailable("TEAM_API_KEY not set"), "scan.pdf", PhotoFixtures.scannedOnlyPdf());

        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertTrue(r.notes().contains("page 1 looks scanned"), r.notes().toString());
        assertTrue(r.notes().contains("page 1 not read by the photo reader: photo not read: TEAM_API_KEY not set"),
                r.notes().toString());
    }

    @Test
    void blankScannedPagesAreNotSentToTheGateway() throws Exception {
        FakeGateway gw = new FakeGateway();

        Reading r = read(gw, "packing-list.pdf", PhotoFixtures.textThenBlankPdf());

        assertEquals(0, gw.images.size());
        assertEquals(Reading.Status.PARTIAL, r.status());
        assertTrue(r.notes().contains("page 2 not read by the photo reader: page is blank, not sent"), r.notes().toString());
    }
}
