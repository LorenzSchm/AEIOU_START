package ai.aeiou.dispatch;

import ai.aeiou.freight.Catalog;
import ai.aeiou.freight.Catalog.Tariff;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
class CarrierTariffs {
  static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final int MAX_BYTES = 2_000_000;
  private final Ledger ledger;
  private final Catalog catalog;
  private final FiveU upstream;
  private final Clock clock;
  private final WriterLease lease;

  CarrierTariffs(Ledger ledger, Catalog catalog, FiveU upstream, Clock clock, WriterLease lease) {
    this.ledger = ledger;
    this.catalog = catalog;
    this.upstream = upstream;
    this.clock = clock;
    this.lease = lease;
  }

  record Sheet(long id, long messageId, long attachmentId, String carrier, String filename,
      String received, String state, String issue, String validFrom, String validTo,
      String decidedAt, List<Tariff> rates) {}
  record Queue(String today, boolean canDecide, List<Sheet> sheets) {}
  record Attachment(long messageId, long attachmentId, String sender, String filename, String received) {}

  @Scheduled(fixedDelay = 10_000)
  void scan() {
    var db = ledger.jdbc();
    for (var attachment : db.query("""
        SELECT m.id, m.sender, m.received, json_extract(a.value,'$.id') AS attachment_id,
          json_extract(a.value,'$.filename') AS filename
        FROM mail m, json_each(m.attachments) a
        WHERE m.kind='carrier' AND json_type(a.value,'$.id')='integer'
          AND json_extract(a.value,'$.id')>0 AND json_type(a.value,'$.filename')='text'
          AND (lower(filename) LIKE '%tariff%' OR lower(filename) LIKE 'rates%'
            OR lower(filename) LIKE '%rate%update%' OR lower(filename) LIKE '%rate%sheet%'
            OR lower(filename) LIKE '%rate%card%' OR lower(m.subject) LIKE '%rate update%'
            OR lower(m.subject) LIKE '%rate sheet%' OR lower(m.subject) LIKE '%tariff%')
          AND NOT EXISTS (SELECT 1 FROM tariff_sheet s
            WHERE s.message_id=m.id AND s.attachment_id=json_extract(a.value,'$.id'))
        """, (row, index) -> new Attachment(row.getLong("id"), row.getLong("attachment_id"),
        row.getString("sender"), row.getString("filename"), row.getString("received")))) {
      catalog.contact(attachment.sender()).filter(carrier -> !carrier.truck()).ifPresent(carrier ->
          db.update("""
              INSERT OR IGNORE INTO tariff_sheet(message_id,attachment_id,carrier,filename,received)
              VALUES(?,?,?,?,?)
              """, attachment.messageId(), attachment.attachmentId(), carrier.name(),
              attachment.filename(), attachment.received()));
    }
    for (var row : db.queryForList("""
        SELECT id,message_id,attachment_id,carrier,filename FROM tariff_sheet
        WHERE state='queued' AND retry_at<=? ORDER BY id LIMIT 4
        """, clock.instant().getEpochSecond())) {
      long id = Ledger.id(row.get("id"));
      String filename = Ledger.str(row, "filename");
      String lower = filename.toLowerCase(Locale.ROOT);
      if (!lower.endsWith(".xlsx") && !lower.endsWith(".pdf") && !lower.endsWith(".txt")) {
        mark(id, "blocked", "This attachment format requires manual review.", null);
        continue;
      }
      byte[] bytes;
      try {
        bytes = upstream.bytes(Ledger.id(row.get("message_id")), Ledger.id(row.get("attachment_id")), MAX_BYTES);
      } catch (RestClientException ex) {
        db.update("""
            UPDATE tariff_sheet SET issue='Download unavailable. Retrying automatically.', retry_at=?
            WHERE id=? AND state='queued'
            """, clock.instant().plusSeconds(60).getEpochSecond(), id);
        return;
      }
      if (bytes.length > MAX_BYTES) {
        mark(id, "blocked", "Attachment exceeds the 2 MB limit.", null);
        continue;
      }
      try {
        var parsed = TariffSheet.read(Ledger.str(row, "carrier"), filename, bytes);
        String issue = parsed.to().isBefore(parsed.from()) ? "Validity dates are reversed."
            : parsed.to().isBefore(today()) ? "The carrier's validity period has expired." : null;
        if (issue == null && parsed.rates().stream().anyMatch(rate ->
            !catalog.zones.containsValue(rate.origin()) || !catalog.destinations.contains(rate.destination()))) {
          issue = "A priced lane contains an airport outside the dispatch directory.";
        }
        mark(id, issue == null ? "pending" : "blocked", issue, parsed);
      } catch (IllegalArgumentException ex) {
        mark(id, "blocked", ex.getMessage(), null);
      } catch (IOException | RuntimeException ex) {
        mark(id, "blocked", "The attachment could not be read as a supported tariff sheet.", null);
      }
    }
  }

  private void mark(long id, String state, String issue, TariffSheet.Parsed parsed) {
    ledger.jdbc().update("""
        UPDATE tariff_sheet SET state=?, issue=?, valid_from=?, valid_to=?, rates=?
        WHERE id=? AND state='queued'
        """, state, issue, parsed == null ? null : parsed.from().toString(),
        parsed == null ? null : parsed.to().toString(), parsed == null ? null : ledger.write(parsed.rates()), id);
  }

  Queue queue() {
    String today = today().toString();
    var rows = ledger.jdbc().query("""
        SELECT * FROM tariff_sheet ORDER BY
          CASE state WHEN 'pending' THEN 0 WHEN 'active' THEN 1 WHEN 'approved' THEN 2
            WHEN 'paused' THEN 3 WHEN 'blocked' THEN 4 WHEN 'queued' THEN 5 ELSE 6 END,
          julianday(received) DESC, id DESC
        """, (row, index) -> {
      String state = row.getString("state");
      String issue = row.getString("issue");
      String until = row.getString("valid_to");
      if ("pending".equals(state) && until.compareTo(today) < 0) {
        state = "blocked";
        issue = "The carrier's validity period has expired.";
      }
      var rates = ledger.read(row.getString("rates"), Tariff[].class);
      return new Sheet(row.getLong("id"), row.getLong("message_id"), row.getLong("attachment_id"),
          row.getString("carrier"), row.getString("filename"), row.getString("received"),
          state, issue, row.getString("valid_from"), until, row.getString("decided_at"),
          rates == null ? List.of() : List.of(rates));
    });
    return new Queue(today, lease.mayWrite(), rows);
  }

  String decide(long id, String decision) {
    if (decision == null) throw new IllegalArgumentException("Choose a rate sheet action.");
    var db = ledger.jdbc();
    String decidedAt = clock.instant().toString();
    String today = today().toString();
    return switch (decision) {
      case "approve" -> requireChange(db.update("""
            UPDATE tariff_sheet SET state='approved',decided_at=?
            WHERE id=? AND state='pending' AND valid_from<=valid_to AND valid_to>=? AND rates IS NOT NULL
            """, decidedAt, id, today), "approved");
      case "reject" -> requireChange(db.update("""
            UPDATE tariff_sheet SET state='rejected',decided_at=?
            WHERE id=? AND state IN ('pending','blocked','queued')
            """, decidedAt, id), "rejected");
      case "pause" -> requireChange(db.update("""
            UPDATE tariff_sheet SET state='paused',decided_at=?
            WHERE id=? AND state IN ('approved','active') AND valid_to>=?
            """, decidedAt, id, today), "paused");
      case "resume" -> requireChange(db.update("""
            UPDATE tariff_sheet SET state='approved',decided_at=?
            WHERE id=? AND state='paused' AND valid_from<=valid_to AND valid_to>=? AND rates IS NOT NULL
            """, decidedAt, id, today), "approved");
      case "activate" -> ledger.transactions().execute(tx -> {
        requireChange(db.update("""
            UPDATE tariff_sheet SET state='active',decided_at=?
            WHERE id=? AND state IN ('approved','paused') AND valid_from<=? AND valid_to>=?
              AND rates IS NOT NULL
            """, decidedAt, id, today, today), "active");
        db.update("""
            UPDATE tariff_sheet SET state='approved'
            WHERE id<>? AND state='active'
              AND carrier=(SELECT carrier FROM tariff_sheet WHERE id=?)
            """, id, id);
        return "active";
      });
      default -> throw new IllegalArgumentException("Unknown rate sheet action.");
    };
  }

  private static String requireChange(int changed, String state) {
    if (changed != 1)
      throw new WaveWorker.Conflict("This sheet is not eligible for that action. Refresh and check its state and validity.");
    return state;
  }

  private LocalDate today() {
    return clock.instant().atZone(BERLIN).toLocalDate();
  }
}
