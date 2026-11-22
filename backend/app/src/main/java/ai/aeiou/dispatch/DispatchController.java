package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/v1")
class DispatchController {
  private final Ledger ledger;
  private final WaveWorker wave;
  private final Catalog catalog;
  private final FiveU upstream;
  private final PortalBookings bookings;
  private final WriterLease lease;
  private final PostgresReplica replica;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  DispatchController(
      Ledger ledger, WaveWorker wave, Catalog catalog, FiveU upstream, PortalBookings bookings,
      WriterLease lease, PostgresReplica replica, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.bookings = bookings;
    this.lease = lease;
    this.replica = replica;
    this.json = json;
    this.ledger = ledger;
    this.wave = wave;
    this.catalog = catalog;
    this.upstream = upstream;
  }

  /** Worker status. A read-only desk adds when its replica last synced from the writer's Postgres. */
  @GetMapping("/status")
  Map<String, Object> status() {
    Map<String, Object> status = json.convertValue(wave.status(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    status.put("read_only", lease.readOnly());
    if (lease.readOnly()) {
      status.put("replica_synced", replica.lastSync() == null ? null : replica.lastSync().toString());
      status.put("replica_error", replica.error());
    }
    return status;
  }

  @GetMapping("/messages")
  List<Map<String, Object>> messages() {
    return ledger.inbox();
  }

  @GetMapping("/directory")
  Map<String, Object> directory() {
    return Map.of("carriers", catalog.carriers, "zones", catalog.zones);
  }

  record Price(Shipment shipment, WaveWorker.ManualBid carrierBid) {}

  record Send(String revision) {}

  record Invite(Shipment shipment, String email) {}

  record Reply(String body) {}

  @PostMapping("/messages/{id}/price")
  WaveWorker.Draft price(@PathVariable long id, @RequestBody Price input) {
    return wave.price(id, input.shipment(), input.carrierBid());
  }

  @PostMapping("/messages/{id}/resume")
  Map<String, String> resume(@PathVariable long id, @RequestBody Shipment shipment) {
    wave.resume(id, shipment);
    return Map.of("state", "new");
  }

  @PostMapping("/messages/{id}/quote")
  Object quote(@PathVariable long id, @RequestBody Send input) {
    return wave.sendQuote(id, input.revision());
  }

  @PostMapping("/messages/{id}/bids")
  Object bid(@PathVariable long id, @RequestBody Invite input) {
    return wave.invite(
        id,
        input.shipment(),
        catalog
            .contact(input.email())
            .orElseThrow(() -> new IllegalArgumentException("Unknown carrier.")));
  }

  @PostMapping("/messages/{id}/reply")
  Object reply(@PathVariable long id, @RequestBody Reply input) {
    return wave.reply(id, input.body());
  }

  @PostMapping("/messages/{id}/book")
  Object book(@PathVariable long id) {
    return bookings.book(id);
  }

  @GetMapping("/messages/{id}/attachments/{aid}")
  ResponseEntity<byte[]> attachment(@PathVariable long id, @PathVariable long aid) {
    List<?> attachments = ledger.read(ledger.mail(id).get("attachments"), List.class);
    if (attachments.stream()
        .noneMatch(a -> String.valueOf(aid).equals(String.valueOf(((Map<?, ?>) a).get("id")))))
      return ResponseEntity.notFound().build();
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(upstream.bytes(id, aid));
  }

  @ExceptionHandler(WaveWorker.Conflict.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  Map<String, String> conflict(RuntimeException e) {
    return Map.of("detail", e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
  Map<String, String> invalid(RuntimeException e) {
    return Map.of("detail", e.getMessage());
  }

  @ExceptionHandler(RestClientException.class)
  @ResponseStatus(HttpStatus.BAD_GATEWAY)
  Map<String, String> unavailable() {
    return Map.of("detail", "The 5U service could not confirm the operation.");
  }
}
