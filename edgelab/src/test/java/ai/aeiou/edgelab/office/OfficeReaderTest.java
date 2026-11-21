package ai.aeiou.edgelab.office;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.Reading;

class OfficeReaderTest {

    private static final OfficeReader OFFICE = new OfficeReader();

    /** The production wiring. */
    private static final EdgeReader READER = new EdgeReader();

    private static Reading read(String name, byte[] bytes) {
        return READER.read(name, bytes);
    }

    private static void assertOk(Reading r, String expectedType, String... expectedText) {
        String dump = "\n" + r;
        assertEquals(Reading.Status.OK, r.status(), dump);
        assertEquals(expectedType, baseType(r.detectedType()), dump);
        for (String t : expectedText) assertTrue(r.text().contains(t), "missing '" + t + "'" + dump);
        assertTrue(r.confidence() >= 0.8, "confidence " + r.confidence() + dump);
    }

    private static String baseType(String t) {
        int semi = t.indexOf(';');
        return semi < 0 ? t : t.substring(0, semi);
    }

    private static List<String> lines(Reading r) {
        return r.text().lines().map(String::strip).toList();
    }

    // --- every fixture format: status ok, right detected type, its text ---

    @Test
    void docxRfqIsRead() throws Exception {
        assertOk(read("quotation-request.docx", OfficeFixtures.docxRfq()),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "Quotation request", "Munich (MUC) to New York (JFK)", "Please quote all-in rate per kg.");
    }

    @Test
    void docxTruckerOfferKeepsOfferNumberAndPrice() throws Exception {
        Reading r = read("price-offer.docx", OfficeFixtures.docxTruckerOffer());
        assertOk(r, "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "Price offer", "TRK-6286", "price EUR 245.00");
    }

    @Test
    void pptxIsRead() throws Exception {
        assertOk(read("rfq.pptx", OfficeFixtures.pptx()),
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "Air export RFQ MUC to JFK", "730");
    }

    @Test
    void oldXlsIsRead() throws Exception {
        assertOk(read("rates.xls", OfficeFixtures.xls()), "application/vnd.ms-excel", "Rates", "MUC", "2.85");
    }

    @Test
    void xlsxIsRead() throws Exception {
        assertOk(read("rates.xlsx", OfficeFixtures.xlsx()),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "MUC", "2.85");
    }

    @Test
    void odtIsRead() throws Exception {
        assertOk(read("order.odt", OfficeFixtures.odt()), "application/vnd.oasis.opendocument.text",
                "Transport order ODT-77 from Augsburg", "Gross weight 512 kg");
    }

    @Test
    void odsIsRead() throws Exception {
        Reading r = read("prices.ods", OfficeFixtures.ods());
        assertOk(r, "application/vnd.oasis.opendocument.spreadsheet", "Yellowtail");
        assertTrue(lines(r).contains("Yellowtail | 1180"), r.text());
    }

    @Test
    void rtfIsReadWithUmlauts() {
        assertOk(read("offer.rtf", OfficeFixtures.rtf()), "application/rtf",
                "Grüße aus München", "Freight price EUR 310");
    }

    @Test
    void latin1HtmlIsReadWithUmlautsAndTableRows() {
        Reading r = read("offer.html", OfficeFixtures.htmlLatin1());
        assertOk(r, "text/html", "Angebot für Großteile nach Zürich");
        assertTrue(lines(r).contains("MUC-ZRH | EUR 199"), r.text());
    }

    @Test
    void latin1CsvKeepsRowsAndAccents() {
        Reading r = read("routes.csv", OfficeFixtures.csvLatin1());
        assertEquals(Reading.Status.OK, r.status(), r.toString());
        assertTrue(r.text().contains("München"), r.text());
        assertTrue(r.text().contains("Montréal"), r.text());
        assertTrue(lines(r).stream().anyMatch(l -> l.contains("Nürnberg") && l.contains("Québec") && l.contains("95")),
                r.text());
    }

    @Test
    void markdownIsRead() {
        Reading r = read("rfq.md", OfficeFixtures.markdown());
        assertEquals(Reading.Status.OK, r.status(), r.toString());
        assertTrue(r.text().contains("3 pallets"), r.text());
    }

    @Test
    void cp1252TextDecodesUmlautsAndFrenchAccents() {
        Reading r = read("note.txt", OfficeFixtures.txtCp1252());
        assertEquals(Reading.Status.OK, r.status(), r.toString());
        assertEquals("text/plain", baseType(r.detectedType()), r.toString());
        assertTrue(r.text().contains("Grüße aus München"), r.text());
        assertTrue(r.text().contains("Prêt à partir"), r.text());
        assertTrue(r.text().contains("très urgent, coût 12,50 €"), r.text());
    }

    @Test
    void cp1252TextAlsoDecodesWhenGivenToTheOfficeReaderDirectly() {
        Reading r = OFFICE.read("note.txt", OfficeFixtures.txtCp1252(), "text/plain");
        assertEquals(Reading.Status.OK, r.status(), r.toString());
        assertTrue(r.text().contains("Grüße aus München"), r.text());
        assertTrue(r.text().contains("très urgent"), r.text());
    }

    @Test
    void latin1TextWithoutEuroSignDecodes() {
        byte[] bytes = "Abholung in Nürnberg, Lieferung nach Orléans, Gewicht 95 kg".getBytes(OfficeFixtures.LATIN1);
        Reading r = read("note.txt", bytes);
        assertEquals(Reading.Status.OK, r.status(), r.toString());
        assertTrue(r.text().contains("Nürnberg") && r.text().contains("Orléans"), r.text());
    }

    @Test
    void textPdfIsRead() throws Exception {
        Reading r = read("packing-list.pdf", OfficeFixtures.textPdf());
        assertOk(r, "application/pdf", "Packing list PL-2231", "3 pallets, gross 412 kg");
        assertTrue(r.notes().isEmpty(), r.notes().toString());
    }

    @Test
    void emlBodyIsReadThroughTheContainerSlot() {
        Reading r = READER.read("rfq.eml", OfficeFixtures.eml());
        assertOk(r, "message/rfc822", "please quote 3 pieces, 412 kg", "Grüße");
    }

    @Test
    void emlIsReadWhenCalledDirectly() {
        Reading r = OFFICE.read("rfq.eml", OfficeFixtures.eml(), "message/rfc822");
        assertEquals(Reading.Status.OK, r.status(), r.toString());
        assertTrue(r.text().contains("Munich to New York"), r.text());
    }

    // --- table rows one per line ---

    @Test
    void docxTableRowsComeOutOnePerLine() throws Exception {
        List<String> lines = lines(read("quotation-request.docx", OfficeFixtures.docxRfq()));
        assertTrue(lines.contains("Pieces | Weight kg | Dimensions cm"), String.join("\n", lines));
        assertTrue(lines.contains("3 | 412.5 | 120x80x100"), String.join("\n", lines));
        assertTrue(lines.contains("1 | 88 | 60x40x50"), String.join("\n", lines));
    }

    @Test
    void xlsxTableRowsComeOutOnePerLine() throws Exception {
        List<String> lines = lines(read("rates.xlsx", OfficeFixtures.xlsx()));
        assertTrue(lines.contains("Origin | Destination | Rate per kg"), String.join("\n", lines));
        assertTrue(lines.contains("MUC | JFK | 2.85"), String.join("\n", lines));
        assertTrue(lines.contains("MUC | ORD | 3.1"), String.join("\n", lines));
    }

    @Test
    void xlsTableRowsComeOutOnePerLine() throws Exception {
        List<String> lines = lines(read("rates.xls", OfficeFixtures.xls()));
        assertTrue(lines.contains("MUC | JFK | 2.85"), String.join("\n", lines));
    }

    @Test
    void pptxTableRowsComeOutOnePerLine() throws Exception {
        List<String> lines = lines(read("rfq.pptx", OfficeFixtures.pptx()));
        assertTrue(lines.contains("Pieces | Weight kg"), String.join("\n", lines));
        assertTrue(lines.contains("4 | 730"), String.join("\n", lines));
    }

    // --- real type decides ---

    @Test
    void docxNamedTxtIsReadAsWord() throws Exception {
        Reading r = read("offer.txt", OfficeFixtures.docxTruckerOffer());
        assertOk(r, "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "TRK-6286", "price EUR 245.00");
    }

    @Test
    void pdfNamedDocxIsReadAsPdf() throws Exception {
        assertOk(read("scan.docx", OfficeFixtures.textPdf()), "application/pdf", "Packing list PL-2231");
    }

    // --- corrupt files ---

    @Test
    void truncatedDocxIsUnreadableWithANote() throws Exception {
        Reading r = read("broken.docx", OfficeFixtures.truncatedDocx());
        assertEquals(Reading.Status.UNREADABLE, r.status(), r.toString());
        assertFalse(r.notes().isEmpty());
        assertEquals(0.0, r.confidence());
    }

    @Test
    void docxWithBrokenXmlIsUnreadableWithANote() throws Exception {
        Reading r = read("broken.docx", OfficeFixtures.brokenXmlDocx());
        assertEquals(Reading.Status.UNREADABLE, r.status(), r.toString());
        assertFalse(r.notes().isEmpty());
    }

    @Test
    void corruptDocxGivenStraightToTheOfficeReaderDoesNotThrow() throws Exception {
        String docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        Reading r = OFFICE.read("broken.docx", OfficeFixtures.truncatedDocx(), docx);
        assertEquals(Reading.Status.UNREADABLE, r.status(), r.toString());
        assertTrue(r.notes().get(0).startsWith("could not read " + docx), r.notes().toString());
    }

    @Test
    void randomBytesNamedDocxAreUnreadable() {
        byte[] junk = new byte[2048];
        new java.util.Random(7).nextBytes(junk);
        Reading r = read("offer.docx", junk);
        assertEquals(Reading.Status.UNREADABLE, r.status(), r.toString());
        assertFalse(r.notes().isEmpty());
    }

    // --- likely scans and confidence ---

    @Test
    void nearlyEmptyPdfPageIsFlaggedAsLikelyScan() throws Exception {
        Reading r = read("packing-list.pdf", OfficeFixtures.pdfWithBlankSecondPage());
        assertEquals(Reading.Status.PARTIAL, r.status(), r.toString());
        assertTrue(r.notes().contains("page 2 looks scanned"), r.notes().toString());
        assertFalse(r.notes().contains("page 1 looks scanned"), r.notes().toString());
        assertTrue(r.text().contains("Packing list PL-2231"));
        assertTrue(r.confidence() < 0.6, "confidence " + r.confidence());
    }

    @Test
    void extractExposesPdfPagesForThePhotoReader() throws Exception {
        OfficeReader.Extraction ex = OFFICE.extract("p.pdf", OfficeFixtures.pdfWithBlankSecondPage(), "application/pdf");
        assertEquals(2, ex.pages().size());
        assertEquals(List.of(2), ex.likelyScannedPages());
        assertTrue(ex.pages().get(0).text().contains("Packing list PL-2231"));
    }

    @Test
    void pdfWithOnlyBlankPagesIsUnreadableAndListsEveryPage() {
        OfficeReader.Extraction ex = new OfficeReader.Extraction("", "application/pdf",
                List.of(new OfficeReader.Page(1, "", true), new OfficeReader.Page(2, "", true)));
        Reading r = OfficeReader.toReading(ex, "application/pdf");
        assertEquals(Reading.Status.UNREADABLE, r.status());
        assertEquals(List.of("page 1 looks scanned", "page 2 looks scanned", "no text layer"), r.notes());
    }

    @Test
    void confidenceIsHighForRealTextAndLowForAlmostNothing() {
        assertEquals(0.0, OfficeReader.confidenceFor(0));
        assertTrue(OfficeReader.confidenceFor(5) < 0.5);
        assertTrue(OfficeReader.confidenceFor(500) >= 0.9);
    }

    @Test
    void htmlWithNoVisibleTextIsUnreadable() {
        Reading r = read("empty.html", "<html><body><p>   </p></body></html>".getBytes(StandardCharsets.UTF_8));
        assertEquals(Reading.Status.UNREADABLE, r.status(), r.toString());
        assertEquals(List.of("no text found"), r.notes());
    }
}
