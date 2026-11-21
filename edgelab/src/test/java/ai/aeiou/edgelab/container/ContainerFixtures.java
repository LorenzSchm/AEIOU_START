package ai.aeiou.edgelab.container;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/** Files that hold other files, built in code. {@link #main} writes the demo copies. */
final class ContainerFixtures {

    static final String PACKING_TXT = "Shipping note: handle with care, ready Monday 08:00.";
    static final String FORWARDED_RFQ = "Please quote air freight MUC to ORD: 3 pallets, 412 kg, 2.1 cbm.";

    private ContainerFixtures() {}

    static byte[] zip(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] packingListXlsx() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Packing list");
            String[][] rows = {{"Packing list", "PL-7781"}, {"Pieces", "Gross kg"}, {"3", "412.5"}};
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A zip with an xlsx packing list and a txt. */
    static byte[] zipWithPackingList() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("packing-list.xlsx", packingListXlsx());
        m.put("notes.txt", text(PACKING_TXT));
        return zip(m);
    }

    static byte[] forwardedRfqEml() {
        return crlf("""
                From: Shipper <shipper@example.com>
                To: dispatch@example.com
                Subject: RFQ MUC-ORD
                Date: Sat, 21 Nov 2026 09:00:00 +0100
                MIME-Version: 1.0
                Content-Type: text/plain; charset=UTF-8

                %s
                """.formatted(FORWARDED_RFQ));
    }

    /** An eml whose attachment is the forwarded RFQ mail. */
    static byte[] emlWithForwardedRfq() {
        return crlf("""
                From: Dispatch <dispatch@example.com>
                To: quotes@example.com
                Subject: Fwd: RFQ MUC-ORD
                Date: Sat, 21 Nov 2026 10:00:00 +0100
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="BOUNDARY"

                --BOUNDARY
                Content-Type: text/plain; charset=UTF-8

                Hi team, see the forwarded request below.

                --BOUNDARY
                Content-Type: message/rfc822
                Content-Disposition: attachment; filename="rfq.eml"

                %s
                --BOUNDARY--
                """.formatted(new String(forwardedRfqEml(), StandardCharsets.UTF_8)));
    }

    /** An eml with a zipped packing list attached (base64). */
    static byte[] emlWithZip() {
        String b64 = Base64.getMimeEncoder().encodeToString(zipWithPackingList());
        return crlf("""
                From: Shipper <shipper@example.com>
                To: quotes@example.com
                Subject: Documents
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="B2"

                --B2
                Content-Type: text/plain; charset=UTF-8

                Documents attached.

                --B2
                Content-Type: application/zip; name="docs.zip"
                Content-Disposition: attachment; filename="docs.zip"
                Content-Transfer-Encoding: base64

                %s
                --B2--
                """.formatted(b64));
    }

    private static byte[] crlf(String s) {
        return s.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] rfqPhotoPng() {
        BufferedImage img = new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 500);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 28));
        String[] lines = {"Request for quotation", "MUC -> JFK", "4 pieces, 730 kg", "Ready: Monday"};
        for (int i = 0; i < lines.length; i++) g.drawString(lines[i], 40, 80 + i * 60);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A pptx with one slide: a title and the RFQ photo. */
    static byte[] pptxWithPhoto() {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new java.awt.Rectangle(20, 20, 600, 50));
            box.setText("Customer request, see photo");
            XSLFPictureData data = ppt.addPicture(rfqPhotoPng(), PictureData.PictureType.PNG);
            XSLFPictureShape pic = slide.createPicture(data);
            pic.setAnchor(new java.awt.Rectangle(20, 90, 480, 300));
            ppt.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A docx with a line of text and the RFQ photo. */
    static byte[] docxWithPhoto() {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Booking request, photo of the label below");
            XWPFRun run = doc.createParagraph().createRun();
            run.addPicture(new java.io.ByteArrayInputStream(rfqPhotoPng()), Document.PICTURE_TYPE_PNG,
                    "label.png", 400 * 9525, 250 * 9525);
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** level-1.zip > level-2.zip > ... > level-5.zip, each with a txt saying its level. */
    static byte[] nestedZip(int levels) {
        byte[] inner = null;
        for (int level = levels; level >= 1; level--) {
            Map<String, byte[]> m = new LinkedHashMap<>();
            m.put("level-" + level + ".txt", text("text at level " + level));
            if (inner != null) m.put("level-" + (level + 1) + ".zip", inner);
            inner = zip(m);
        }
        return inner;
    }

    static byte[] zipWithFiles(int count) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 1; i <= count; i++) m.put(String.format("file-%02d.txt", i), text("content of file " + i));
        return zip(m);
    }

    /** A zip whose entries are marked encrypted (general purpose flag bit 0). */
    static byte[] encryptedZip() {
        byte[] z = zipWithPackingList().clone();
        for (int i = 0; i + 4 <= z.length; i++) {
            if (z[i] == 'P' && z[i + 1] == 'K' && z[i + 2] == 3 && z[i + 3] == 4) z[i + 6] |= 1;       // local header
            if (z[i] == 'P' && z[i + 1] == 'K' && z[i + 2] == 1 && z[i + 3] == 2) z[i + 8] |= 1;       // central directory
        }
        return z;
    }

    /** The first 60 bytes of a zip: the signature is there, the rest is gone. */
    static byte[] brokenZip() {
        byte[] z = zipWithPackingList();
        return java.util.Arrays.copyOf(z, 60);
    }

    /** Writes the demo files to src/test/resources/container/. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/test/resources/container");
        Files.createDirectories(dir);
        Files.write(dir.resolve("shipment-docs.zip"), zipWithPackingList());
        Files.write(dir.resolve("fwd-rfq.eml"), emlWithForwardedRfq());
        Files.write(dir.resolve("rfq-photo.pptx"), pptxWithPhoto());
        Files.write(dir.resolve("nested-5-levels.zip"), nestedZip(5));
        Files.write(dir.resolve("thirty-files.zip"), zipWithFiles(30));
    }
}
