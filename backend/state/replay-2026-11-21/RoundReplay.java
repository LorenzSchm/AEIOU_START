package ai.aeiou.dispatch;

import ai.aeiou.freight.Catalog;
import ai.aeiou.freight.MailParser;
import ai.aeiou.freight.Offer;
import ai.aeiou.freight.Shipment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.client.ResourceAccessException;

public final class RoundReplay {
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static final Pattern FREIGHT = Pattern.compile("(\\d+(?:\\.\\d+)?) kg gross.*?(\\d+(?:\\.\\d+)?) cbm", Pattern.DOTALL);
  static final Pattern POSTCODE_LANE = Pattern.compile("pickup from (?:postcode )?(\\d{5})(?: [^0-9]*?)? to ([A-Z]{3})");

  static final class ReplayClock extends Clock {
    Instant value;
    ReplayClock(Instant value) { this.value = value; }
    public Instant instant() { return value; }
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
  }

  record InboxEvent(Instant at, JsonNode mail) {}
  record PortalObservation(Instant at, Offer offer) {}

  static String kind(String action) {
    if (action.endsWith(":transit") && action.startsWith("bid:")) return "truck-transit";
    return action.split(":", 2)[0];
  }

  static String lane(String subject) {
    String text = subject.substring(subject.indexOf('|') + 1).strip();
    var pickup = POSTCODE_LANE.matcher(text);
    if (pickup.matches()) return "pickup from " + pickup.group(1) + " to " + pickup.group(2);
    return text;
  }

  static List<BigDecimal> shipmentTerms(String body) {
    var matcher = FREIGHT.matcher(body);
    if (!matcher.find()) return List.of();
    return List.of(new BigDecimal(matcher.group(1)).stripTrailingZeros(),
        new BigDecimal(matcher.group(2)).stripTrailingZeros());
  }

  static String str(JsonNode node, String field) { return node.path(field).asText(""); }

  static final class RecordedFiveU extends FiveU {
    final JsonNode round;
    final Ledger ledger;
    final ReplayClock clock;
    final Instant arrival;
    final Path attachments;
    final Shipment historicalShipment;
    final boolean portalAtFirstLookup;
    final List<PortalObservation> portal = new ArrayList<>();
    final Queue<InboxEvent> inbox = new PriorityQueue<>(Comparator.comparing(InboxEvent::at)
        .thenComparingLong(event -> event.mail().path("id").asLong()));
    final Set<Long> used = new HashSet<>();
    final List<Map<String, Object>> sends = new ArrayList<>();
    final List<String> gatewayCalls = new ArrayList<>();
    final List<Map<String, Object>> portalReads = new ArrayList<>();
    final Set<String> missingAttachments = new LinkedHashSet<>();
    int syntheticId = 1000000;

    RecordedFiveU(JsonNode round, Ledger ledger, ReplayClock clock, Path attachments,
        boolean portalAtFirstLookup) throws IOException {
      super("http://127.0.0.1:1", "offline-replay");
      this.round = round;
      this.ledger = ledger;
      this.clock = clock;
      this.arrival = clock.instant();
      this.attachments = attachments;
      this.portalAtFirstLookup = portalAtFirstLookup;
      this.historicalShipment = JSON.treeToValue(round.get("historical_shipment"), Shipment.class);
      for (var item : round.path("historical_portal"))
        portal.add(new PortalObservation(Instant.parse(str(item, "available_at")),
            JSON.treeToValue(item.get("offer"), Offer.class)));
    }

    double seconds(Instant instant) {
      return Duration.between(arrival, instant).toMillis() / 1000.0;
    }

    @Override Map<String, Object> get(String path, Object... values) {
      throw new IllegalStateException("No GET allowed during replay: " + path);
    }

    @Override byte[] bytes(long mail, long artifact) {
      var name = mail + "-" + artifact;
      try {
        return Files.readAllBytes(attachments.resolve(name));
      } catch (IOException ex) {
        missingAttachments.add(name);
        throw new ResourceAccessException("Missing recorded attachment");
      }
    }

    @Override synchronized Rates rates(Shipment request, Instant now) {
      boolean same = Objects.equals(request.origin(), historicalShipment.origin())
          && Objects.equals(request.destination(), historicalShipment.destination())
          && historicalShipment.weightKg() != null && historicalShipment.volumeCbm() != null
          && request.weightKg().compareTo(historicalShipment.weightKg()) == 0
          && request.volumeCbm().compareTo(historicalShipment.volumeCbm()) == 0;
      // Allow a small recorded lookup duration instead of introducing an artificial 35-second retry
      // for portal rates issued a few seconds after the initial call.
      Instant observedThrough = same && !portalAtFirstLookup ? portal.stream()
          .map(PortalObservation::at).filter(at -> !at.isAfter(now.plusSeconds(5)))
          .max(Instant::compareTo).filter(at -> at.isAfter(now)).orElse(now) : now;
      if (observedThrough.isAfter(clock.instant())) clock.value = observedThrough;
      var observed = same ? portal.stream()
          .filter(rate -> (portalAtFirstLookup || !rate.at().isAfter(observedThrough))
              && rate.offer().validUntil().isAfter(observedThrough))
          .map(PortalObservation::offer).toList() : List.<Offer>of();
      boolean complete = same && round.path("portal_complete").asBoolean()
          && (portalAtFirstLookup || portal.stream().noneMatch(rate -> rate.at().isAfter(observedThrough)));
      portalReads.add(Map.of("after_seconds", seconds(now), "visible_offers", observed.size(),
          "complete", complete, "recorded_shipment_matches", same, "results_at_seconds", seconds(observedThrough)));
      return new Rates(observed, complete);
    }

    @Override JsonNode parseRfq(Map<String, Object> request) {
      String schema = JSON.valueToTree(request.get("response_format")).path("json_schema").path("name").asText();
      JsonNode archived = round.path("historical_gateway");
      JsonNode result;
      if ("freight_rfq".equals(schema)) {
        if (!archived.has("pickup_required")) {
          gatewayCalls.add("missing extraction");
          throw new ResourceAccessException("No recorded extraction");
        }
        gatewayCalls.add("recorded extraction");
        result = archived;
      } else if ("quote_check".equals(schema)) {
        if (archived.has("contradiction")) {
          gatewayCalls.add("recorded quote check");
          result = archived;
        } else {
          gatewayCalls.add("assumed successful advisory quote check");
          result = JSON.createObjectNode().put("contradiction", false);
        }
      } else throw new IllegalStateException("Unexpected gateway schema " + schema);
      return JSON.valueToTree(Map.of("choices", List.of(Map.of("finish_reason", "stop",
          "message", Map.of("content", result.toString())))));
    }

    JsonNode equivalentSend(String action, Map<String, Object> payload) {
      String kind = kind(action);
      String recipient = Ledger.str(payload, "to");
      String subject = Ledger.str(payload, "subject");
      String body = Ledger.str(payload, "body");
      for (var historical : round.path("recorded_sends")) {
        long id = historical.path("id").asLong();
        if (used.contains(id) || !recipient.equals(str(historical, "to"))
            || !kind.equals(kind(str(historical, "action")))) continue;
        if ("bid".equals(kind)) {
          String historicalLane = historical.has("lane_override")
              ? str(historical, "lane_override") : lane(str(historical, "subject"));
          if (!lane(subject).equals(historicalLane)) continue;
          var match = shipmentTerms(body);
          if (match.isEmpty() || !match.equals(shipmentTerms(str(historical, "body")))) continue;
        }
        if ("pickup-postcode".equals(kind) || "rfq-info".equals(kind)) {
          if (!body.toLowerCase(Locale.ROOT).contains("postcode")) {
            if (!body.equals(str(historical, "body"))) continue;
          } else if (!str(historical, "body").toLowerCase(Locale.ROOT).contains("postcode")) continue;
        }
        if ("airport-delivery".equals(kind) && !body.equals(str(historical, "body"))) continue;
        if ("truck-transit".equals(kind) && !str(historical, "body").contains("transit from actual pickup"))
          continue;
        return historical;
      }
      return null;
    }

    @Override Map<String, Object> post(String path, Map<String, Object> payload) {
      if (!"/v1/mailbox/messages".equals(path)) throw new IllegalStateException("No external effects: " + path);
      String action = ledger.jdbc().queryForObject(
          "SELECT action FROM deliveries WHERE state='pending'", String.class);
      String kind = kind(action);
      var historical = equivalentSend(action, payload);
      long id = historical == null ? ++syntheticId : historical.path("id").asLong();
      var event = new LinkedHashMap<String, Object>();
      event.put("action", action);
      event.put("to", payload.get("to"));
      event.put("subject", payload.get("subject"));
      event.put("after_seconds", seconds(clock.instant()));
      event.put("historical_send_matched", historical != null);
      var replyIds = new ArrayList<Long>();
      if (historical != null) {
        used.add(id);
        for (var reply : historical.path("replies")) {
          if (Set.of("quote", "reply", "counter").contains(kind)) continue;
          Instant historicalResponse = Instant.parse(str(reply, "received_at"));
          Instant historicalRequest = Instant.parse(str(historical, "attempted_at"));
          Duration delay = Duration.between(historicalRequest, historicalResponse);
          if (delay.isNegative()) delay = Duration.ZERO;
          Instant replayResponse = clock.instant().plus(delay);
          var message = (ObjectNode) reply.deepCopy();
          message.put("received_at", replayResponse.toString());
          inbox.add(new InboxEvent(replayResponse, message));
          replyIds.add(message.path("id").asLong());
        }
      }
      event.put("archived_replies", replyIds);
      sends.add(event);
      return Map.of("id", id);
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(JsonNode node) {
    return JSON.convertValue(node, Map.class);
  }

  static Map<String, Object> run(Path directory, Catalog catalog, JsonNode round,
      Map<Long, JsonNode> allHistory, boolean portalAtFirstLookup) throws Exception {
    int number = round.path("official").path("round_no").asInt();
    Path temp = Files.createTempDirectory(directory, "round-" + number + "-");
    var rfq = round.path("rfq");
    long id = rfq.path("id").asLong();
    Instant arrival = Instant.parse(str(rfq, "received_at"));
    var clock = new ReplayClock(arrival);
    var ledger = new Ledger(temp.resolve("replay.sqlite").toString(), JSON);
    var fiveU = new RecordedFiveU(round, ledger, clock, directory.resolve("attachments"), portalAtFirstLookup);
    var worker = new WaveWorker(ledger, catalog, fiveU, clock,
        new RfqGateway(fiveU, ledger, catalog, JSON, clock, "offline-replay"), true,
        arrival, 40, false);
    List<Map<String, Object>> history = new ArrayList<>();
    for (var mid : round.path("as_of_route_history")) {
      assert mid.asLong() < id;
      history.add(map(allHistory.get(mid.asLong())));
    }
    history.add(map(rfq));
    ledger.ingest(Map.of("messages", history, "next_since_id", id), catalog, arrival);
    String previousReason = "";
    List<String> stageTrace = new ArrayList<>();
    for (int second = 0; second <= 360; second++) {
      if (arrival.plusSeconds(second).isAfter(clock.instant())) clock.value = arrival.plusSeconds(second);
      var incoming = new ArrayList<Map<String, Object>>();
      while (!fiveU.inbox.isEmpty() && !fiveU.inbox.peek().at().isAfter(clock.instant()))
        incoming.add(map(fiveU.inbox.remove().mail()));
      if (!incoming.isEmpty())
        ledger.ingest(Map.of("messages", incoming, "next_since_id", id), catalog, clock.instant());
      worker.work();
      var mail = ledger.mail(id);
      String stage = Ledger.str(mail, "stage");
      if (stageTrace.isEmpty() || !stage.equals(stageTrace.getLast().split(" ", 2)[1]))
        stageTrace.add(second + " " + stage);
      if (Set.of("sent", "expired", "declined").contains(stage)) break;
      String reason = ledger.jdbc().queryForObject("SELECT reason FROM wave_case WHERE id=?", String.class, id);
      if (reason != null && !reason.isBlank()) previousReason = reason;
    }
    var mail = ledger.mail(id);
    var delivery = ledger.delivery("quote:" + id);
    var draft = ledger.read(mail.get("proposal"), WaveWorker.Draft.class);
    var shipment = ledger.read(mail.get("fields"), Shipment.class);
    var result = new LinkedHashMap<String, Object>();
    result.put("round", number);
    result.put("request_id", id);
    result.put("official", round.path("official"));
    result.put("historical_offer", round.get("historical_offer"));
    result.put("historical_operator", round.path("historical_operator").asBoolean());
    result.put("historical_stage", round.path("historical_stage").asText());
    result.put("historical_portal_offer_count", fiveU.portal.size());
    result.put("shipment", shipment);
    result.put("replay_stage", mail.get("stage"));
    result.put("replay_offer", "sent".equals(delivery) && draft != null ? draft.offer() : null);
    result.put("replay_trucker", "sent".equals(delivery) && draft != null ? draft.trucker() : null);
    result.put("replay_pickup_eur", "sent".equals(delivery) && draft != null ? draft.pickupEur() : null);
    result.put("replay_quote_seconds", fiveU.sends.stream().filter(send -> "quote".equals(kind(send.get("action").toString())))
        .map(send -> send.get("after_seconds")).findFirst().orElse(null));
    result.put("reason_before_terminal_stage", previousReason);
    result.put("recorded_replies_injected", fiveU.sends.stream()
        .mapToInt(send -> ((List<?>) send.get("archived_replies")).size()).sum() - fiveU.inbox.size());
    result.put("stage_trace", stageTrace);
    result.put("gateway_calls", fiveU.gatewayCalls);
    result.put("unavailable_attachments", fiveU.missingAttachments);
    result.put("outbound_trace", fiveU.sends);
    result.put("portal_trace", fiveU.portalReads);
    result.put("tenders", ledger.tenders(id).stream().map(row -> {
      var values = new LinkedHashMap<String, Object>();
      for (var key : List.of("email", "leg", "reply_id", "euros", "days", "issue", "bid_action"))
        values.put(key, row.get(key));
      return values;
    }).toList());
    try (var files = Files.list(temp)) {
      for (Path file : files.toList()) Files.delete(file);
    }
    Files.delete(temp);
    return result;
  }

  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    var input = JSON.readTree(directory.resolve("input.json").toFile());
    boolean portalAtFirstLookup = args.length > 1 && "all-recorded-portal".equals(args[1]);
    var history = new HashMap<Long, JsonNode>();
    for (var m : input.path("history")) history.put(m.path("id").asLong(), m);
    var catalog = new DispatchApplication().catalog();
    var output = new ArrayList<Map<String, Object>>();
    for (var round : input.path("rounds")) {
      var result = run(directory, catalog, round, history, portalAtFirstLookup);
      output.add(result);
      var offer = (Offer) result.get("replay_offer");
      System.out.printf("Round %2s %-25s quote %-9s at %-7s reason %s%n", result.get("round"),
          result.get("replay_stage"), offer == null ? "—" : offer.totalEur(),
          result.get("replay_quote_seconds"), result.get("reason_before_terminal_stage"));
    }
    if (output.size() != 30) throw new IllegalStateException("Expected 30 rounds");
    JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(portalAtFirstLookup ? "trace-portal-sensitivity.json" : "trace.json").toFile(), output);
  }
}
