package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.aeiou.freight.Shipment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CarrierTariffsTest {
  private static final Path KIT = Path.of("../../start-kit");
  @TempDir Path directory;

  @Test
  void parsesThePublishedSpreadsheetPdfAndTextLayouts() throws Exception {
    for (var example : Map.of(
        "Wayzata Air Cargo", "wayzata-air-cargo-tariff-2026-11.xlsx",
        "Vega Skyfreight", "vega-skyfreight-tariff-2026-11.xlsx",
        "Kestrel Airways", "kestrel-airways-tariff-2026-11.xlsx",
        "Elbflug Air Freight", "elbflug-air-freight-tariff-2026-11.xlsx",
        "Cobalt Skylift", "cobalt-skylift-tariff-2026-11.pdf",
        "Helix Air Freight", "helix-air-freight-tariff-2026-11.pdf",
        "Orion Cargo Air", "orion-cargo-air-tariff-2026-11.pdf",
        "Yellowtail Air Freight", "yellowtail-air-freight-tariff-2026-11.txt").entrySet()) {
      var filename = example.getValue();
      var parsed = assertDoesNotThrow(() ->
          TariffSheet.read(example.getKey(), filename, Files.readAllBytes(KIT.resolve(filename))), filename);
      assertEquals(LocalDate.of(2026, 11, 1), parsed.from());
      assertEquals(LocalDate.of(2026, 11, 30), parsed.to());
      assertFalse(parsed.rates().isEmpty());
      if (filename.startsWith("wayzata")) assertEquals(26, parsed.rates().size());
    }
  }

  @Test
  void onlyAnExplicitApprovalActivatesTheRatesWithinTheCarrierDates() throws Exception {
    var catalog = new DispatchApplication().catalog(null);
    var ledger = new Ledger(directory.resolve("dispatch.sqlite3").toString(), new ObjectMapper());
    var upstream = mock(FiveU.class);
    var lease = mock(WriterLease.class);
    when(lease.mayWrite()).thenReturn(true);
    var now = Instant.parse("2026-11-21T22:00:00Z");
    var service = new CarrierTariffs(ledger, catalog, upstream, Clock.fixed(now, ZoneOffset.UTC), lease);
    var original = Files.readString(KIT.resolve("yellowtail-air-freight-tariff-2026-11.txt"));
    var future = original.replace("01.11.2026", "01.12.2026").replace("30.11.2026", "31.12.2026");
    when(upstream.bytes(eq(10L), eq(3L), anyInt())).thenReturn(future.getBytes(StandardCharsets.UTF_8));
    when(upstream.bytes(eq(11L), eq(4L), anyInt())).thenReturn(
        original.replace("2026", "2025").getBytes(StandardCharsets.UTF_8));
    when(upstream.bytes(eq(12L), eq(5L), anyInt())).thenReturn(original.getBytes(StandardCharsets.UTF_8));

    ledger.ingest(Map.of("messages", List.of(mail(10, 3, now), mail(11, 4, now), mail(12, 5, now)),
        "next_since_id", 12), catalog, now);
    service.scan();
    service.scan();
    var sheets = service.queue().sheets();
    assertEquals(3, sheets.size());
    verify(upstream, times(3)).bytes(anyLong(), anyLong(), anyInt());
    var pending = sheets.stream().filter(s -> s.messageId() == 10).findFirst().orElseThrow();
    var expired = sheets.stream().filter(s -> s.messageId() == 11).findFirst().orElseThrow();
    var reject = sheets.stream().filter(s -> s.messageId() == 12).findFirst().orElseThrow();
    assertEquals("pending", pending.state(), pending.issue());
    assertEquals("blocked", expired.state());
    assertThrows(WaveWorker.Conflict.class, () -> service.decide(expired.id(), "approve"));
    assertEquals("rejected", service.decide(reject.id(), "reject"));

    var shipment = new Shipment("CGN", "SYD", BigDecimal.valueOf(100), BigDecimal.ZERO,
        "cheapest", null, null, 0);
    var start = LocalDate.of(2026, 12, 1);
    var end = start.plusMonths(1).minusDays(1);
    assertTrue(ledger.approvedTariffs(shipment, start, start).isEmpty());
    assertEquals("approved", service.decide(pending.id(), "approve"));
    assertThrows(WaveWorker.Conflict.class, () -> service.decide(pending.id(), "reject"));
    assertTrue(ledger.approvedTariffs(shipment, start, start.minusDays(1)).isEmpty());
    assertTrue(ledger.approvedTariffs(shipment, start.minusDays(1), start).isEmpty());
    var offers = catalog.price(shipment, start, ledger.approvedTariffs(shipment, start, start));
    assertEquals(1, offers.size());
    assertEquals("Yellowtail Air Freight", offers.getFirst().airline());
    assertEquals(new BigDecimal("484.00"), offers.getFirst().totalEur());
    assertEquals(1, ledger.approvedTariffs(shipment, end, end).size());
    assertTrue(ledger.approvedTariffs(shipment, end, end.plusDays(1)).isEmpty());
    assertTrue(ledger.approvedTariffs(shipment, end.plusDays(1), end.plusDays(1)).isEmpty());
  }

  @Test
  void validApprovedSheetsCanBeSwitchedAndPausedImmediately() throws Exception {
    var catalog = new DispatchApplication().catalog(null);
    var ledger = new Ledger(directory.resolve("switch.sqlite3").toString(), new ObjectMapper());
    var now = Instant.parse("2026-11-21T22:00:00Z");
    var manager = new CarrierTariffs(ledger, catalog, mock(FiveU.class),
        Clock.fixed(now, ZoneOffset.UTC), mock(WriterLease.class));
    var carrier = "Yellowtail Air Freight";
    var original = new ai.aeiou.freight.Catalog.Tariff(carrier, "CGN", "SYD", 3, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, new TreeMap<>(Map.of(0, BigDecimal.valueOf(3))));
    var replacement = new ai.aeiou.freight.Catalog.Tariff(carrier, "CGN", "SYD", 3, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, new TreeMap<>(Map.of(0, BigDecimal.valueOf(5))));
    ledger.jdbc().update("""
        INSERT INTO tariff_sheet(id,message_id,attachment_id,carrier,filename,received,state,valid_from,valid_to,rates)
        VALUES(1,11,11,?,'Original','2026-11-20T09:00:00Z','pending','2026-11-01','2026-11-30',?),
              (2,12,12,?,'Replacement','2026-11-21T09:00:00Z','pending','2026-11-01','2026-11-30',?)
        """, carrier, ledger.write(List.of(original)), carrier, ledger.write(List.of(replacement)));
    var shipment = new Shipment("CGN", "SYD", BigDecimal.valueOf(100), BigDecimal.ZERO,
        "cheapest", null, null, 0);
    var today = LocalDate.of(2026, 11, 21);
    assertThrows(WaveWorker.Conflict.class, () -> manager.decide(1, "activate"));
    manager.decide(1, "approve");
    manager.decide(2, "approve");
    assertEquals(List.of(replacement), ledger.approvedTariffs(shipment, today, today));
    assertEquals("active", manager.decide(1, "activate"));
    assertEquals(List.of(original), ledger.approvedTariffs(shipment, today, today));
    assertEquals("paused", manager.decide(1, "pause"));
    assertEquals(List.of(replacement), ledger.approvedTariffs(shipment, today, today));
    manager.decide(2, "activate");
    assertEquals("active", manager.decide(1, "activate"));
    assertEquals(List.of(original), ledger.approvedTariffs(shipment, today, today));
    assertEquals("approved", manager.queue().sheets().stream()
        .filter(sheet -> sheet.id() == 2).findFirst().orElseThrow().state());
  }

  @Test
  void reviewersCanCorrectASheetBeforeApprovingItButNeverAfter() throws Exception {
    var catalog = new DispatchApplication().catalog(null);
    var ledger = new Ledger(directory.resolve("revise.sqlite3").toString(), new ObjectMapper());
    var now = Instant.parse("2026-11-21T22:00:00Z");
    var manager = new CarrierTariffs(ledger, catalog, mock(FiveU.class),
        Clock.fixed(now, ZoneOffset.UTC), mock(WriterLease.class));
    var carrier = "Yellowtail Air Freight";
    var misread = new ai.aeiou.freight.Catalog.Tariff(carrier, "CGN", "SYD", 3, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, new TreeMap<>(Map.of(0, BigDecimal.valueOf(30))));
    ledger.jdbc().update("""
        INSERT INTO tariff_sheet(id,message_id,attachment_id,carrier,filename,received,state,issue,valid_from,valid_to,rates)
        VALUES(1,11,11,?,'misread.pdf','2026-11-20T09:00:00Z','blocked','The carrier''s validity period has expired.',
          '2025-11-01','2025-11-30',?)
        """, carrier, ledger.write(List.of(misread)));
    var lane = new CarrierTariffs.Lane("cgn", "syd", 3, BigDecimal.ZERO, new BigDecimal("0.10"),
        BigDecimal.ZERO, Map.of(0, new BigDecimal("3.00"), 100, new BigDecimal("2.50")));

    // Invalid corrections are refused with a reason and change nothing.
    for (var bad : List.of(
        new CarrierTariffs.Revision("2026-11-30", "2026-11-01", List.of(lane)),
        new CarrierTariffs.Revision("2026-11-01", "2026-11-30", List.of()),
        new CarrierTariffs.Revision("2026-11-01", "2026-11-30", List.of(lane, lane)),
        new CarrierTariffs.Revision("2026-11-01", "2026-11-30", List.of(new CarrierTariffs.Lane(
            "CGN", "XXX", 3, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(0, BigDecimal.ONE)))),
        new CarrierTariffs.Revision("2026-11-01", "2026-11-30", List.of(new CarrierTariffs.Lane(
            "CGN", "SYD", 3, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(45, BigDecimal.ONE)))),
        new CarrierTariffs.Revision("2026-11-01", "2026-11-30", List.of(new CarrierTariffs.Lane(
            "CGN", "SYD", 3, new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO, Map.of(0, BigDecimal.ONE))))))
      assertThrows(IllegalArgumentException.class, () -> manager.revise(1, bad));
    assertEquals("2025-11-30", manager.queue().sheets().getFirst().validTo());

    // Fixing the dates and the misread price returns the sheet to review, keeping the carrier's values.
    var revised = manager.revise(1, new CarrierTariffs.Revision("2026-11-01", "2026-11-30", List.of(lane)));
    assertEquals("pending", revised.state());
    assertNull(revised.issue());
    assertEquals(now.toString(), revised.editedAt());
    assertEquals("2025-11-30", revised.original().validTo());
    assertEquals(List.of(misread), revised.original().rates());
    var stored = revised.rates().getFirst();
    assertEquals(carrier, stored.airline());
    assertEquals("CGN", stored.origin());
    assertEquals(new BigDecimal("2.50"), stored.breaks().get(100));

    // A second correction keeps the first original, and nothing prices quotes until it is approved.
    manager.revise(1, new CarrierTariffs.Revision("2026-11-01", "2026-11-30", List.of(lane)));
    assertEquals("2025-11-30", manager.queue().sheets().getFirst().original().validTo());
    var shipment = new Shipment("CGN", "SYD", BigDecimal.valueOf(100), BigDecimal.ZERO,
        "cheapest", null, null, 0);
    var today = LocalDate.of(2026, 11, 21);
    assertTrue(ledger.approvedTariffs(shipment, today, today).isEmpty());
    assertEquals("approved", manager.decide(1, "approve"));
    assertEquals(List.of(stored), ledger.approvedTariffs(shipment, today, today));

    // Once approved, prices are never edited in place.
    assertThrows(WaveWorker.Conflict.class,
        () -> manager.revise(1, new CarrierTariffs.Revision("2026-11-01", "2026-11-30", List.of(lane))));
  }

  private static Map<String, Object> mail(long id, long attachmentId, Instant received) {
    return Map.of("id", id, "from", "yellowtail-air-freight@hackloop.mail",
        "subject", "Monthly rate update", "body", "Updated air export tariffs attached.",
        "sent_at", received.toString(), "attachments",
        List.of(Map.of("id", attachmentId, "filename", "rate-update.txt")));
  }
}
