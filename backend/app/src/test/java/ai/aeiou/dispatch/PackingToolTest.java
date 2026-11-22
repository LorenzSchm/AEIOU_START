package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

class PackingToolTest {
  @TempDir Path temp;
  final FiveU upstream = mock(FiveU.class);
  final Clock clock = mock(Clock.class);

  @org.junit.jupiter.api.BeforeEach void setTime() {
    when(clock.instant()).thenReturn(java.time.Instant.ofEpochSecond(1000));
  }
  final String packing = """
      Packing List
      Destination airport: LAX
      Total gross weight (kg): 100
      Total volume (cbm): 0.2
      Dangerous goods: None
      Shipper: Müller
      """;

  ShipmentDocuments reader() throws Exception {
    var ledger = new Ledger(temp.resolve("packing.sqlite").toString(), new ObjectMapper());
    return new ShipmentDocuments(ledger, upstream, new DispatchApplication().catalog(),
        clock, Runnable::run, true);
  }

  Map<String, Object> message(long id, String filename) throws Exception {
    return Map.of("id", id, "subject", "RFQ FRA to LAX",
        "body", "Cheapest air freight from FRA to LAX. Please use the packing list.",
        "attachments", new ObjectMapper().writeValueAsString(List.of(Map.of("id", 1, "filename", filename))));
  }

  ShipmentDocuments.Decision inspect(ShipmentDocuments reader, Map<String,Object> message) {
    return reader.inspect(message, List.of(), null, null);
  }

  @Test void reusesRecoveredAndUnreadableAttachmentsAcrossRestarts() throws Exception {
    when(upstream.bytes(7, 1)).thenReturn(packing.getBytes(Charset.forName("windows-1252")));
    when(upstream.bytes(8, 1)).thenReturn(new byte[]{0, 1, 2});
    for (var r : List.of(reader(), reader())) {
      assertFalse(inspect(r, message(7, "packing.txt")).holdsQuote());
      assertEquals("packing-copy", inspect(r, message(8, "scan.jpg")).action());
    }
    verify(upstream, times(1)).bytes(7, 1);
    verify(upstream, times(1)).bytes(8, 1);
  }

  @Test void retriesFailedDownloadAndPreservesDangerousGoodsEvidence() throws Exception {
    when(upstream.bytes(7, 1)).thenThrow(new ResourceAccessException("offline"))
        .thenThrow(new ResourceAccessException("still offline"))
        .thenReturn((packing + "UN 1950").getBytes(StandardCharsets.UTF_16));
    var r = reader();
    assertThrows(WaveWorker.RetryLater.class, () -> inspect(r, message(7, "packing.pdf")));
    assertThrows(WaveWorker.RetryLater.class, () -> inspect(r, message(7, "packing.pdf")));
    verify(upstream).bytes(7, 1);
    when(clock.instant()).thenReturn(java.time.Instant.ofEpochSecond(1016));
    assertThrows(WaveWorker.RetryLater.class, () -> inspect(r, message(7, "packing.pdf")));
    when(clock.instant()).thenReturn(java.time.Instant.ofEpochSecond(1032));
    assertEquals("dg-docs", inspect(r, message(7, "packing.pdf")).action());
    verify(upstream, times(3)).bytes(7, 1);
  }

  @Test void transientReaderFailureYieldsToOtherRequestsAndRetriesAfterRestart() throws Exception {
    var jobs = new ArrayDeque<Runnable>();
    var ledger = new Ledger(temp.resolve("worker.sqlite").toString(), new ObjectMapper());
    var catalog = new DispatchApplication().catalog();
    var docs = new ShipmentDocuments(ledger, upstream, catalog, clock,
        unused -> fail("edge work reached voice pool"), false, jobs::add,
        (name, bytes) -> { throw new java.net.SocketTimeoutException(); }, false);
    when(upstream.bytes(7, 1)).thenReturn(new byte[]{0, 1, 2});
    when(upstream.bytes(8, 1)).thenReturn(packing.getBytes(StandardCharsets.UTF_8));
    var broken = message(7, "unknown.zip");
    assertThrows(WaveWorker.RetryLater.class, () -> inspect(docs, broken));
    assertThrows(WaveWorker.RetryLater.class, () -> inspect(docs, broken));
    assertFalse(inspect(docs, message(8, "packing.txt")).holdsQuote());
    assertEquals(1, jobs.size());
    jobs.remove().run();
    assertThrows(WaveWorker.RetryLater.class, () -> inspect(docs, broken));
    verify(upstream, times(1)).bytes(7, 1);
    when(clock.instant()).thenReturn(java.time.Instant.ofEpochSecond(1016));
    var restarted = new ShipmentDocuments(ledger, upstream, catalog, clock, Runnable::run, false,
        jobs::add, (name, bytes) -> packing, false);
    assertThrows(WaveWorker.RetryLater.class, () -> inspect(restarted, broken));
    jobs.remove().run();
    assertFalse(inspect(restarted, broken).holdsQuote());
  }

  @Test void aCorrectedAttachmentDoesNotInheritThePreviousResult() throws Exception {
    when(upstream.bytes(7, 1)).thenReturn(packing.getBytes(StandardCharsets.UTF_8));
    when(upstream.bytes(8, 1)).thenReturn((packing + "UN 1950").getBytes(StandardCharsets.UTF_8));
    var r = reader();
    var original = message(7, "packing.pdf");
    assertFalse(inspect(r, original).holdsQuote());
    var correction = message(8, "packing.pdf");
    var decision = r.inspect(original, List.of(correction), null, null);
    assertEquals("dg-incomplete", decision.action());
    assertTrue(decision.gatewayReview().classificationEvidence().contains("UN 1950"));
  }

  @Test void readsPdfWithWrongExtensionAndRejectsAPartiallyScannedPdf() throws Exception {
    try (var pdf = new PDDocument()) {
      var page = new PDPage();
      pdf.addPage(page);
      try (var content = new PDPageContentStream(pdf, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        content.setLeading(14);
        content.newLineAtOffset(40, 700);
        for (String line : packing.lines().toList()) {
          content.showText(line);
          content.newLine();
        }
        content.endText();
      }
      var data = new ByteArrayOutputStream();
      pdf.save(data);
      when(upstream.bytes(7, 1)).thenReturn(data.toByteArray());
      var r = reader();
      assertFalse(inspect(r, message(7, "packing.dat")).holdsQuote());
      pdf.addPage(new PDPage());
      data.reset();
      pdf.save(data);
      when(upstream.bytes(8, 1)).thenReturn(data.toByteArray());
      assertEquals("packing-copy", inspect(r, message(8, "packing.dat")).action());
    }
  }
}
