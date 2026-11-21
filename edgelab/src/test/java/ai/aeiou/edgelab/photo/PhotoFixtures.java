package ai.aeiou.edgelab.photo;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * Photo test files. Most are drawn in memory; the WebP is made once with the bundled
 * ffmpeg ({@link #main}) because no pure-Java WebP writer exists. Real files: the two
 * Danube Wings tariff scans from the start kit and a HEIC sample from libheif.
 */
public final class PhotoFixtures {

    static final String[] RFQ_LINES = {
            "Request for quotation",
            "From: Munich (MUC) To: Chicago (ORD)",
            "3 pallets, 340 kg, 2.1 cbm",
            "Ready: 24 Nov 2026",
    };

    private PhotoFixtures() {}

    /** A clean, upright RFQ page with a red marker block in the top-left corner. */
    static BufferedImage rfqPage(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 8, height / 8);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(12, height / 14)));
        int y = height / 4;
        for (String line : RFQ_LINES) {
            g.drawString(line, width / 10, y);
            y += height / 8;
        }
        g.dispose();
        return img;
    }

    /** A skewed, blurred "phone photo" of the RFQ: tilted 7 degrees on a grey desk, soft focus. */
    public static byte[] phonePhotoJpeg() {
        BufferedImage page = rfqPage(1600, 1100);
        BufferedImage photo = new BufferedImage(2000, 1500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(90, 90, 95));
        g.fillRect(0, 0, photo.getWidth(), photo.getHeight());
        g.rotate(Math.toRadians(7), 1000, 750);
        g.drawImage(page, 200, 200, null);
        g.dispose();
        float[] box = new float[25];
        java.util.Arrays.fill(box, 1f / 25);
        BufferedImage blurred = new ConvolveOp(new Kernel(5, 5, box), ConvolveOp.EDGE_NO_OP, null).filter(photo, null);
        return encode(blurred, "jpeg");
    }

    /**
     * The RFQ stored sideways (pixels turned 90 degrees counter-clockwise, 600 wide x 800 high)
     * with EXIF orientation 6, which tells viewers to turn it clockwise: upright it is 800x600.
     */
    static byte[] sidewaysJpegWithExifRotation() {
        BufferedImage upright = rfqPage(800, 600);
        BufferedImage sideways = ImagePrep.applyOrientation(upright, 8); // 90 deg counter-clockwise
        return withExifOrientation(encode(toRgb(sideways), "jpeg"), 6);
    }

    /** Inserts a minimal EXIF APP1 segment (IFD0 with only the orientation tag) after the JPEG SOI. */
    static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {
                'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, // little-endian TIFF header, IFD0 at 8
                0x01, 0x00,                                    // 1 entry
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, (byte) orientation, 0x00, 0x00, 0x00, // 0x0112 SHORT 1
                0x00, 0x00, 0x00, 0x00};                       // no next IFD
        byte[] exifHeader = {'E', 'x', 'i', 'f', 0, 0};
        int len = 2 + exifHeader.length + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write(len >> 8);
        out.write(len & 0xFF);
        out.writeBytes(exifHeader);
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, format, out)) throw new IOException("no writer for " + format);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BufferedImage toRgb(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    static byte[] resource(String name) {
        try (InputStream in = PhotoFixtures.class.getResourceAsStream("/photo/" + name)) {
            if (in == null) throw new IOException("missing test resource /photo/" + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A PDF whose only page is a picture of the RFQ: no text layer at all. */
    static byte[] scannedOnlyPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addScannedPage(doc);
            return save(doc);
        }
    }

    /** Page 1 has real text, page 2 is a scan. */
    static byte[] textThenScannedPdf() throws IOException {
        return textPdf(true);
    }

    /** Page 1 has real text, page 2 is empty (a text extractor can't tell it from a scan). */
    static byte[] textThenBlankPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDDocument text = Loader.loadPDF(textOnlyPdf());
            doc.importPage(text.getPage(0));
            doc.addPage(new PDPage(PDRectangle.A4));
            byte[] out = save(doc);
            text.close();
            return out;
        }
    }

    /** One page of real text, no scan. */
    static byte[] textOnlyPdf() throws IOException {
        return textPdf(false);
    }

    private static byte[] textPdf(boolean withScan) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Packing list page one: 3 pallets, gross weight 340 kg.");
                cs.endText();
            }
            if (withScan) addScannedPage(doc);
            return save(doc);
        }
    }

    private static void addScannedPage(PDDocument doc) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDImageXObject img = LosslessFactory.createFromImage(doc, rfqPage(1240, 900));
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(img, 36, 300, 523, 380);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    /**
     * Writes {@code rfq.webp} with the bundled ffmpeg (libwebp encoder). Run once by hand:
     *   java -cp <test runtime classpath> ai.aeiou.edgelab.photo.PhotoFixtures src/test/resources/photo
     */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/test/resources/photo");
        Path webp = dir.resolve("rfq.webp");
        BufferedImage img = rfqPage(800, 600);
        try (FFmpegFrameRecorder rec = new FFmpegFrameRecorder(webp.toFile(), img.getWidth(), img.getHeight());
             Java2DFrameConverter conv = new Java2DFrameConverter()) {
            rec.setFormat("webp");
            rec.setVideoCodecName("libwebp");
            rec.setVideoCodec(avcodec.AV_CODEC_ID_WEBP);
            rec.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
            rec.setVideoQuality(20);
            rec.start();
            rec.record(conv.convert(img));
            rec.stop();
        }
        System.out.println("wrote " + webp + " (" + Files.size(webp) + " bytes)");
    }
}
