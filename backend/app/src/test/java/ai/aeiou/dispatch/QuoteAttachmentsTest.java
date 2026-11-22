package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.Offer;
import ai.aeiou.freight.Shipment;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class QuoteAttachmentsTest {
  @Test
  void distinguishesRequestedFormatsFromIncomingDocuments() {
    assertEquals(EnumSet.of(QuoteAttachments.Format.PDF), QuoteAttachments.requested(
        "Air freight quote to DFW – fastest option",
        "Please attach your offer as a PDF to your reply, as our purchasing department needs it for filing."));
    assertTrue(QuoteAttachments.requested("Request for quotation", """
        Please find our request for an air freight quotation attached, as a PDF file (quotation-request.pdf).
        Please reply by mail with your all-in price in EUR.
        """).isEmpty());
    assertEquals(EnumSet.of(QuoteAttachments.Format.XLS), QuoteAttachments.requested("RFQ",
        "Please send your offer as an Excel (.xls) file."));
    // Live #56801 was rejected with no_pdf_offer: the customer asked for the price, not the offer.
    assertEquals(EnumSet.of(QuoteAttachments.Format.PDF), QuoteAttachments.requested("Air freight quote to LIM",
        "Please send your all-in price in EUR as a PDF attached to your reply so our purchasing department can"
            + " file it."));
    assertEquals(EnumSet.of(QuoteAttachments.Format.PDF, QuoteAttachments.Format.DOCX),
        QuoteAttachments.requested("RFQ", "Please attach your offer as a PDF and Word document."));
    assertEquals(EnumSet.of(QuoteAttachments.Format.XLSX), QuoteAttachments.requested("RFQ",
        "Merci d’envoyer votre devis au format Excel."));
    assertEquals(EnumSet.of(QuoteAttachments.Format.PDF), QuoteAttachments.requested("询价",
        "请以PDF格式提交您的报价。"));
  }

  @ParameterizedTest
  @EnumSource(QuoteAttachments.Format.class)
  void generatesAReadableOfferInEachMailboxFormat(QuoteAttachments.Format format) throws Exception {
    var shipment = new Shipment("CGN", "DFW", new BigDecimal("65"), new BigDecimal("0.37"),
        "fastest", null, null, 0);
    var offer = new Offer("Lumen Air Cargo", 1, new BigDecimal("347.05"), new BigDecimal("65"),
        "carrier bid", null, null);
    var draft = new WaveWorker.Draft("test", offer, List.of(offer), List.of(),
        "Please find our offer of EUR 347.05 for your shipment.", null, null, null, null);
    var attachments = QuoteAttachments.generate(21448,
        Map.of("body", "Please attach your offer as a " + format.extension + " file."), shipment, draft);
    assertEquals(1, attachments.size());
    var attachment = attachments.getFirst();
    assertEquals("AEIOU-21448-offer." + format.extension, attachment.get("filename"));
    assertEquals(format.contentType, attachment.get("content_type"));
    byte[] bytes = Base64.getDecoder().decode(attachment.get("content_base64"));
    try (var input = new ByteArrayInputStream(bytes)) {
      switch (format) {
        case PDF -> {
          try (var document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("CGN to DFW"));
            assertTrue(text.contains("347.05"));
          }
        }
        case DOCX -> {
          try (var document = new XWPFDocument(input)) {
            assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().contains("347.05")));
          }
        }
        case XLSX, XLS -> {
          try (var workbook = WorkbookFactory.create(input)) {
            assertEquals("347.05", workbook.getSheetAt(0).getRow(4).getCell(1).getStringCellValue());
          }
        }
        case CSV, TXT -> assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("347.05"));
        case PNG, JPG -> assertNotNull(ImageIO.read(input));
      }
    }
  }
}
