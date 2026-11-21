package ai.aeiou.edgelab.office;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** Small office files built in memory, shaped like the real RFQs and trucker offers. */
final class OfficeFixtures {

    private OfficeFixtures() {}

    static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    static final Charset CP1252 = Charset.forName("windows-1252");

    /** Like round 68's quotation-request.docx: shipment details plus a pieces table. */
    static byte[] docxRfq() throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Quotation request");
            doc.createParagraph().createRun().setText("Air export from Munich (MUC) to New York (JFK), ready 24.11.2026.");
            doc.createParagraph().createRun().setText("Commodity: machine parts, not dangerous goods.");
            XWPFTable table = doc.createTable(3, 3);
            fill(table, 0, "Pieces", "Weight kg", "Dimensions cm");
            fill(table, 1, "3", "412.5", "120x80x100");
            fill(table, 2, "1", "88", "60x40x50");
            doc.createParagraph().createRun().setText("Please quote all-in rate per kg.");
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void fill(XWPFTable table, int row, String... cells) {
        for (int i = 0; i < cells.length; i++) table.getRow(row).getCell(i).setText(cells[i]);
    }

    /** Like the real trucker offers: "price offer ... TRK-6286 ... price EUR ...". */
    static byte[] docxTruckerOffer() throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Price offer");
            doc.createParagraph().createRun().setText("Offer number: TRK-6286");
            doc.createParagraph().createRun().setText("Pickup Garching to MUC airport, 2 pallets");
            doc.createParagraph().createRun().setText("price EUR 245.00");
            doc.write(out);
            return out.toByteArray();
        }
    }

    static byte[] pptx() throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new java.awt.Rectangle(20, 20, 600, 50));
            box.setText("Air export RFQ MUC to JFK");
            XSLFTable table = slide.createTable();
            table.setAnchor(new java.awt.Rectangle(20, 100, 600, 200));
            String[][] rows = {{"Pieces", "Weight kg"}, {"4", "730"}};
            for (String[] r : rows) {
                XSLFTableRow row = table.addRow();
                for (String c : r) row.addCell().setText(c);
            }
            ppt.write(out);
            return out.toByteArray();
        }
    }

    static byte[] xls() throws IOException {
        return workbook(new HSSFWorkbook());
    }

    static byte[] xlsx() throws IOException {
        return workbook(new XSSFWorkbook());
    }

    private static byte[] workbook(Workbook wb) throws IOException {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Rates");
            Object[][] rows = {{"Origin", "Destination", "Rate per kg"}, {"MUC", "JFK", 2.85}, {"MUC", "ORD", 3.1}};
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) {
                    Object v = rows[i][j];
                    if (v instanceof Double d) row.createCell(j).setCellValue(d);
                    else row.createCell(j).setCellValue((String) v);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** A minimal OpenDocument text file. */
    static byte[] odt() throws IOException {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
                  <office:body><office:text>
                    <text:p>Transport order ODT-77 from Augsburg</text:p>
                    <text:p>Gross weight 512 kg</text:p>
                  </office:text></office:body>
                </office:document-content>
                """;
        return openDocument("application/vnd.oasis.opendocument.text", content);
    }

    /** A minimal OpenDocument spreadsheet with two rows. */
    static byte[] ods() throws IOException {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
                  <office:body><office:spreadsheet>
                    <table:table table:name="Sheet1">
                      <table:table-row>
                        <table:table-cell><text:p>Carrier</text:p></table:table-cell>
                        <table:table-cell><text:p>Price EUR</text:p></table:table-cell>
                      </table:table-row>
                      <table:table-row>
                        <table:table-cell><text:p>Yellowtail</text:p></table:table-cell>
                        <table:table-cell><text:p>1180</text:p></table:table-cell>
                      </table:table-row>
                    </table:table>
                  </office:spreadsheet></office:body>
                </office:document-content>
                """;
        return openDocument("application/vnd.oasis.opendocument.spreadsheet", content);
    }

    private static byte[] openDocument(String mime, String contentXml) throws IOException {
        String manifest = """
                <?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
                  <manifest:file-entry manifest:full-path="/" manifest:media-type="%s"/>
                  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                </manifest:manifest>
                """.formatted(mime);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            // The mimetype entry must come first and be stored uncompressed.
            byte[] m = mime.getBytes(StandardCharsets.US_ASCII);
            ZipEntry e = new ZipEntry("mimetype");
            e.setMethod(ZipEntry.STORED);
            e.setSize(m.length);
            CRC32 crc = new CRC32();
            crc.update(m);
            e.setCrc(crc.getValue());
            zip.putNextEntry(e);
            zip.write(m);
            zip.closeEntry();
            put(zip, "content.xml", contentXml.strip());
            put(zip, "META-INF/manifest.xml", manifest.strip());
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** RTF with escaped Latin-1 umlauts. */
    static byte[] rtf() {
        return "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0 Arial;}}\\f0 Gr\\'fc\\'dfe aus M\\'fcnchen\\par Freight price EUR 310\\par}"
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** HTML saved as Latin-1, with a table. */
    static byte[] htmlLatin1() {
        return """
                <html><head><meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">
                <title>Offer</title></head><body>
                <p>Angebot für Großteile nach Zürich</p>
                <table><tr><th>Route</th><th>Price</th></tr><tr><td>MUC-ZRH</td><td>EUR 199</td></tr></table>
                </body></html>
                """.getBytes(LATIN1);
    }

    static byte[] csvLatin1() {
        return "Origin;Destination;Weight kg\nMünchen;Montréal;412\nNürnberg;Québec;95\n"
                .getBytes(LATIN1);
    }

    static byte[] markdown() {
        return "# Quotation request\n\nShip **3 pallets** from MUC to JFK, total 412 kg.\n"
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Windows-1252 text with German umlauts and French accents. */
    static byte[] txtCp1252() {
        return "Grüße aus München. Prêt à partir du 24 novembre, très urgent, coût 12,50 €."
                .getBytes(CP1252);
    }

    static byte[] eml() {
        return """
                From: Dispatch <dispatch@example.com>
                To: quotes@example.com
                Subject: RFQ air export MUC-JFK
                Date: Sat, 21 Nov 2026 10:00:00 +0100
                MIME-Version: 1.0
                Content-Type: text/plain; charset=UTF-8
                Content-Transfer-Encoding: 8bit

                Hello, please quote 3 pieces, 412 kg, Munich to New York, ready Monday.
                Grüße
                """.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A PDF with a text layer on every page. */
    static byte[] textPdf() throws IOException {
        return pdf(new String[][] {{"Packing list PL-2231", "3 pallets, gross 412 kg"}});
    }

    /** Page 1 has text, page 2 is blank (as a scanned page would be to a text extractor). */
    static byte[] pdfWithBlankSecondPage() throws IOException {
        return pdf(new String[][] {{"Packing list PL-2231", "3 pallets, gross 412 kg"}, {}});
    }

    private static byte[] pdf(String[][] pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String[] lines : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = 700;
                    for (String line : lines) {
                        cs.beginText();
                        cs.setFont(font, 12);
                        cs.newLineAtOffset(72, y);
                        cs.showText(line);
                        cs.endText();
                        y -= 20;
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** The first half of a real docx: the zip is cut off. */
    static byte[] truncatedDocx() throws IOException {
        byte[] docx = docxRfq();
        return Arrays.copyOf(docx, docx.length / 2);
    }

    /** A zip that claims to be Word but whose document.xml is broken. */
    static byte[] brokenXmlDocx() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>""".strip());
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>""".strip());
            put(zip, "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>");
        }
        return bytes.toByteArray();
    }
}
