package ai.aeiou.dispatch;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.aeiou.freight.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs one customer RFQ through the live-like standard-extraction path with a recorded model answer. */
final class VariantHarness {
  static final Instant START = Instant.parse("2026-11-22T09:00:00Z");

  static final class TestClock extends Clock {
    Instant current = START;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return current;
    }
  }

  final TestClock clock = new TestClock();
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  final Catalog catalog;
  final FiveU upstream = mock(FiveU.class);
  final List<Map<String, Object>> outbound = new ArrayList<>();
  final AtomicInteger next = new AtomicInteger(1000);
  final Ledger ledger;
  final WaveWorker worker;

  /** The recorded separate-shipments answer, when a variant needs one. */
  String separate = "{\"needs_review\":true,\"shipments\":[]}";

  VariantHarness(Path dir, Catalog catalog, String extraction) throws Exception {
    this.catalog = catalog;
    ledger = new Ledger(dir.resolve("variant.sqlite").toString(), mapper);
    worker = new WaveWorker(ledger, catalog, upstream, clock,
        new RfqGateway(upstream, ledger, catalog, mapper, clock, "gpt-4o-mini", true),
        new MailboxWebhook(ledger, catalog, clock, mapper, ""), true, START, 75, false);
    when(upstream.parseRfq(anyMap())).thenAnswer(call -> {
      Map<String, Object> payload = call.getArgument(0);
      var schema = mapper.valueToTree(payload.get("response_format")).path("json_schema").path("name").asText();
      String content = switch (schema) {
        case "quote_check" -> "{\"contradiction\":false}";
        case "standard_freight_rfq", "freight_rfq" -> extraction;
        case "separate_air_freight_rfq" -> separate;
        default -> throw new IllegalStateException("unexpected gateway call " + schema);
      };
      return mapper.valueToTree(Map.of("choices", List.of(
          Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
    });
    when(upstream.rates(any(), any())).thenReturn(new FiveU.Rates(List.of(), true));
    when(upstream.post(eq("/v1/mailbox/messages"), anyMap())).thenAnswer(call -> {
      Map<String, Object> msg = call.getArgument(1);
      var receipt = new HashMap<>(msg);
      receipt.put("id", next.incrementAndGet());
      outbound.add(receipt);
      return receipt;
    });
  }

  void receive(long id, String from, String subject, String body) {
    var mail = new HashMap<String, Object>(Map.of("id", id, "from", from, "subject", subject, "body", body,
        "sent_at", START.toString()));
    ledger.ingest(Map.of("messages", List.of(mail), "next_since_id", id), catalog, clock.instant());
  }

  Shipment fields(long id) {
    return ledger.read(ledger.mail(id).get("fields"), Shipment.class);
  }

  String reason(long id) {
    var reasons = ledger.jdbc().queryForList("SELECT reason FROM wave_case WHERE id=?", String.class, id);
    return reasons.isEmpty() ? null : reasons.getFirst();
  }

  List<String> deliveries() {
    return ledger.jdbc().queryForList("SELECT action FROM deliveries ORDER BY action", String.class);
  }

  /** Short description of what happened to the request. */
  String outcome(long id) {
    var s = fields(id);
    String customerMail = outbound.stream().filter(m -> !m.get("to").toString().contains("logistik")
            && !m.get("to").toString().contains("express") && !m.get("to").toString().contains("trucking")
            && !m.get("to").toString().contains("kurier") && !m.get("to").toString().contains("route")
            && !m.get("to").toString().contains("fracht") && !m.get("to").toString().contains("transport"))
        .map(m -> m.get("subject") + " | " + m.get("body").toString().replaceAll("\\s+", " "))
        .reduce((a, b) -> a + " || " + b).orElse("-");
    return "stage=" + ledger.mail(id).get("stage") + " reason=" + reason(id)
        + " fields=" + (s == null ? null : s.origin() + "/" + s.destination() + "/" + s.weightKg() + "/"
            + s.volumeCbm() + "/" + s.preference() + "/" + s.pickupZone())
        + " deliveries=" + deliveries() + " customer=" + customerMail;
  }
}
