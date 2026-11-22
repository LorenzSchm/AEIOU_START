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
  private final CarrierTariffs tariffs;
  private final DeskStatus status;
  private final InboxFeed feed;

  DispatchController(
      Ledger ledger, WaveWorker wave, Catalog catalog, FiveU upstream, PortalBookings bookings,
      CarrierTariffs tariffs, DeskStatus status, InboxFeed feed) {
    this.bookings = bookings;
    this.status = status;
    this.feed = feed;
    this.ledger = ledger;
    this.wave = wave;
    this.catalog = catalog;
    this.upstream = upstream;
    this.tariffs = tariffs;
  }

  /** Worker status. A read-only desk adds when its replica last synced from the writer's Postgres. */
  @GetMapping("/status")
  Map<String, Object> status() {
    return status.current();
  }

  /** Live desk data: a snapshot, then only what changes. See {@link InboxFeed}. */
  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(
      jakarta.servlet.http.HttpServletResponse response) {
    // no-transform keeps compressing proxies (Next's rewrite, nginx) from buffering the stream.
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("X-Accel-Buffering", "no");
    return feed.subscribe();
  }

  /**
   * Cursor-paged messages, newest first. Pass the returned {@code next_before} as {@code before} for
   * the next page; it is null on the last page.
   */
  @GetMapping("/messages/page")
  Map<String, Object> messagePage(@RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) Long before) {
    if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500.");
    var page = ledger.inboxPage(before, limit);
    var result = new LinkedHashMap<String, Object>();
    result.put("messages", page);
    result.put("next_before", page.size() < limit ? null : Ledger.id(page.getLast().get("id")));
    return result;
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

  record TariffDecision(String decision) {}

  @GetMapping("/tariff-sheets")
  CarrierTariffs.Queue tariffSheets() {
    return tariffs.queue();
  }

  @PostMapping(path = "/tariff-sheets/{id}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, String> tariffDecision(@PathVariable long id, @RequestBody TariffDecision input) {
    return Map.of("state", tariffs.decide(id, input.decision()));
  }

  /** Corrects the validity and lane prices of a sheet under review; it then awaits approval again. */
  @PostMapping(path = "/tariff-sheets/{id}/revision", consumes = MediaType.APPLICATION_JSON_VALUE)
  CarrierTariffs.Sheet tariffRevision(@PathVariable long id, @RequestBody CarrierTariffs.Revision input) {
    return tariffs.revise(id, input);
  }

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
