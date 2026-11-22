package ai.aeiou.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Desk summaries, computed on the server from the inbox (plain counting and arithmetic, no LLM).
 *
 * <p>This is a faithful port of the desk's former client-side models (frontend lib/process.js
 * {@code waveSummary}, lib/crm.js {@code crmModel}, lib/score.js {@code scoreModel}), so the views can
 * stop downloading every message just to count them. It reads the same JSON the desk receives, and
 * mirrors JavaScript's number parsing ({@code Number(null)} is 0, a missing field is NaN), so both
 * produce identical figures.
 */
final class DeskSummary {
  private DeskSummary() {}

  static final long WINDOW_MS = 360_000;
  private static final Set<String> ATTENTION_STAGES =
      Set.of("review", "operator review", "waiting for pickup", "waiting for air price");
  private static final Set<String> ATTENTION_DELIVERY = Set.of("uncertain", "pending", "rejected");

  /** All three summaries for one inbox snapshot at one instant. */
  static Map<String, Object> of(JsonNode messages, Instant now) {
    var out = new LinkedHashMap<String, Object>();
    out.put("computed_at", now.toString());
    out.put("overview", overview(messages, now.toEpochMilli()));
    out.put("shipments", shipments(messages, now.toEpochMilli()));
    out.put("score", score(messages));
    out.put("desk", desk(messages, now.toEpochMilli()));
    return out;
  }

  /* ---------------- Desk chrome (page.jsx, Overview.jsx) ---------------- */

  /** process.js globeState: the globe arc's state for a request. */
  static String globeState(JsonNode request, long now) {
    return switch (outcomeOf(request, now)) {
      case "booked" -> "booked";
      case "quoted" -> "quoted";
      case "needs attention" -> "attention";
      case "in progress" -> "active";
      default -> "closed";
    };
  }

  /** A JSON value as the desk reads it: missing and null both become null. */
  private static JsonNode value(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() ? null : node;
  }

  /**
   * What the desk's frame needs besides the three summaries, so it no longer needs every message: the
   * inbox size and attention badge (sidebar), the first attention items and the globe's routes
   * (Overview). Attention items carry only the fields the list renders and statusOf reads.
   */
  static Map<String, Object> desk(JsonNode messages, long now) {
    int count = 0, attention = 0;
    var items = new ArrayList<Map<String, Object>>();
    var routes = new ArrayList<Map<String, Object>>();
    for (var m : each(messages)) {
      count++;
      if (DeskPages.needsAttention(m, now)) {
        attention++;
        if (items.size() < 4) {
          var item = new LinkedHashMap<String, Object>();
          item.put("id", m.path("id").asLong());
          item.put("kind", value(m.path("kind")));
          item.put("stage", value(m.path("stage")));
          item.put("delivery", value(m.path("delivery")));
          item.put("booking", m.path("booking").isObject() ? true : null);
          item.put("received", value(m.path("received")));
          item.put("review_reason", value(m.path("review_reason")));
          var fields = new LinkedHashMap<String, Object>();
          fields.put("origin", value(m.path("fields").path("origin")));
          fields.put("destination", value(m.path("fields").path("destination")));
          item.put("fields", fields);
          items.add(item);
        }
      }
      var fields = m.path("fields");
      if ("request".equals(text(m, "kind")) && truthy(fields, "origin") && truthy(fields, "destination")) {
        var offer = m.path("proposal").path("offer");
        var route = new LinkedHashMap<String, Object>();
        route.put("id", m.path("id").asLong());
        route.put("origin", value(fields.path("origin")));
        route.put("destination", value(fields.path("destination")));
        route.put("state", globeState(m, now));
        route.put("airline", value(offer.path("airline")));
        route.put("totalEur", value(offer.path("total_eur")));
        route.put("days", value(offer.path("days")));
        route.put("label", value(m.path("subject")));
        routes.add(route);
      }
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("messages", count);
    out.put("attention", attention);
    out.put("attention_items", items);
    out.put("routes", routes);
    return out;
  }

  /**
   * The instants (epoch ms) at which a request's outcome can turn "expired" by the clock alone: the
   * first millisecond after its six-minute window. Summaries change with time only at these instants,
   * so a summary computed at {@code t1} is still current at {@code t2} unless one lies in (t1, t2].
   */
  static long[] expiries(JsonNode messages) {
    var out = new ArrayList<Long>();
    for (var m : each(messages)) {
      if (!"request".equals(text(m, "kind"))) continue;
      Long received = ms(m.path("received"));
      if (received != null) out.add(received + WINDOW_MS + 1);
    }
    return out.stream().mapToLong(Long::longValue).sorted().toArray();
  }

  /** Whether any expiry lies in {@code (from, to]}. */
  static boolean expiresBetween(long[] expiries, long from, long to) {
    int i = java.util.Arrays.binarySearch(expiries, from + 1);
    int at = i >= 0 ? i : -i - 1;
    // binarySearch may land on any of several equal entries; step back to the first one above from.
    while (at > 0 && expiries[at - 1] > from) at--;
    return at < expiries.length && expiries[at] <= to;
  }

  /* ---------------- JavaScript-compatible value helpers ---------------- */

  /**
   * String.prototype.localeCompare as the desk's browser does it (ICU, English): accents and case are
   * ignored first, spaces and punctuation sort before digits and digits before letters (Java's
   * Collator would ignore spaces and punctuation instead), and on a tie lowercase comes first.
   */
  static int localeCompare(String a, String b) {
    String pa = primary(a), pb = primary(b);
    for (int i = 0; i < Math.min(pa.length(), pb.length()); i++) {
      int c = Integer.compare(rank(pa.charAt(i)), rank(pb.charAt(i)));
      if (c != 0) return c;
    }
    if (pa.length() != pb.length()) return Integer.compare(pa.length(), pb.length());
    for (int i = 0; i < Math.min(a.length(), b.length()); i++) {
      char x = a.charAt(i), y = b.charAt(i);
      if (x != y && Character.toLowerCase(x) == Character.toLowerCase(y)) return Character.isLowerCase(x) ? -1 : 1;
    }
    return a.compareTo(b) == 0 ? 0 : Integer.signum(a.compareTo(b));
  }

  private static String primary(String text) {
    return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
  }

  private static int rank(char c) {
    if (Character.isWhitespace(c)) return c;
    if (Character.isLetter(c)) return 0x30000 + c;
    if (Character.isDigit(c)) return 0x20000 + c;
    return 0x10000 + c; // punctuation and symbols
  }

  /** Date.parse: epoch millis, or null when absent or unparseable. */
  static Long ms(JsonNode value) {
    if (value == null || !value.isTextual()) return null;
    try {
      return OffsetDateTime.parse(value.asText()).toInstant().toEpochMilli();
    } catch (DateTimeParseException ex) {
      try {
        return Instant.parse(value.asText()).toEpochMilli();
      } catch (DateTimeParseException again) {
        return null;
      }
    }
  }

  /** Number(v): JSON null and "" are 0, a missing field or non-numeric text is NaN. */
  static double jsNumber(JsonNode v) {
    if (v == null || v.isMissingNode()) return Double.NaN;
    if (v.isNull()) return 0;
    if (v.isNumber()) return v.asDouble();
    if (v.isBoolean()) return v.asBoolean() ? 1 : 0;
    String text = v.asText().trim();
    if (text.isEmpty()) return 0;
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException ex) {
      return Double.NaN;
    }
  }

  /** crm.js num(): null for null, missing, "" and non-numeric values. */
  static Double num(JsonNode v) {
    if (v == null || v.isMissingNode() || v.isNull() || (v.isTextual() && v.asText().isEmpty())) return null;
    double d = jsNumber(v);
    return Double.isFinite(d) ? d : null;
  }

  private static String text(JsonNode node, String field) {
    var v = node.path(field);
    return v.isNull() || v.isMissingNode() ? "" : v.asText();
  }

  /** JavaScript truthiness for a string field: present and non-empty. */
  private static boolean truthy(JsonNode node, String field) {
    var v = node.path(field);
    if (v.isMissingNode() || v.isNull()) return false;
    if (v.isTextual()) return !v.asText().isEmpty();
    if (v.isNumber()) return v.asDouble() != 0;
    if (v.isBoolean()) return v.asBoolean();
    return true;
  }

  private static Iterable<JsonNode> each(JsonNode array) {
    return array != null && array.isArray() ? array : List.of();
  }

  private static Long jsRound(Double value) {
    return value == null ? null : Math.round(value);
  }

  private static Double median(List<Double> sorted) {
    if (sorted.isEmpty()) return null;
    int mid = sorted.size() >> 1;
    return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
  }

  /* ---------------- Overview (process.js) ---------------- */

  private static JsonNode lastQuote(JsonNode request) {
    JsonNode last = null;
    for (var o : each(request.path("outgoing"))) if ("quote".equals(text(o, "kind"))) last = o;
    return last;
  }

  /**
   * {booked, total} over a request's separately priced shipments (inbox shipment_bookings). Each is
   * booked on its own, so a request with some of them booked counts as booked, noted "1/2".
   */
  static int[] shipmentsBooked(JsonNode r) {
    int booked = 0, total = 0;
    for (var leg : each(r.path("shipment_bookings"))) {
      total++;
      if (truthy(leg, "booking_reference")) booked++;
    }
    return new int[] {booked, total};
  }

  /** "" when every shipment is booked, else " 1/2". */
  private static String shipmentsNote(int[] n) {
    return n[0] == n[1] ? "" : " " + n[0] + "/" + n[1];
  }

  /**
   * The request's booking as the Shipments views show it: its own booking, else one entry summing
   * its booked shipments (references and airlines joined, air prices added).
   */
  static JsonNode bookingOf(JsonNode r) {
    if (truthy(r.path("booking"), "booking_reference")) return r.path("booking");
    var n = shipmentsBooked(r);
    if (n[0] == 0) return null;
    var refs = new ArrayList<String>();
    var teams = new ArrayList<String>();
    var airlines = new LinkedHashSet<String>();
    var codes = new LinkedHashSet<String>();
    var currencies = new LinkedHashSet<String>();
    Double price = 0.0;
    for (var leg : each(r.path("shipment_bookings"))) {
      if (!truthy(leg, "booking_reference")) continue;
      refs.add(text(leg, "booking_reference"));
      if (truthy(leg, "team_reference")) teams.add(text(leg, "team_reference"));
      if (truthy(leg, "airline")) airlines.add(text(leg, "airline"));
      if (truthy(leg, "airline_code")) codes.add(text(leg, "airline_code"));
      if (truthy(leg, "currency")) currencies.add(text(leg, "currency"));
      Double p = num(leg.path("price"));
      price = price == null || p == null ? null : price + p;
    }
    var b = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    b.put("booking_reference", String.join(" · ", refs));
    b.put("team_reference", teams.isEmpty() ? null : String.join(" · ", teams));
    b.put("airline", airlines.isEmpty() ? null : String.join(" · ", airlines));
    b.put("airline_code", codes.size() == 1 ? codes.getFirst() : null);
    b.put("price", price);
    b.put("currency", currencies.size() == 1 ? currencies.getFirst() : null);
    b.put("status", "booked" + shipmentsNote(n));
    b.put("shipments_booked", n[0]);
    b.put("shipments", n[1]);
    return b;
  }

  /** Precedence: declined > booked > quoted > needs attention > expired > in progress. */
  static String outcomeOf(JsonNode r, long now) {
    String stage = text(r, "stage");
    if ("declined".equals(stage)) return "declined";
    if (truthy(r.path("booking"), "booking_reference") || "booked".equals(stage) || shipmentsBooked(r)[0] > 0)
      return "booked";
    String delivery = truthy(r, "delivery") ? text(r, "delivery")
        : lastQuote(r) == null ? "" : text(lastQuote(r), "state");
    if ("sent".equals(delivery)) return "quoted";
    Long received = ms(r.path("received"));
    boolean windowOpen = received == null || now - received <= WINDOW_MS;
    if (windowOpen && (ATTENTION_DELIVERY.contains(delivery) || ATTENTION_STAGES.contains(stage))) return "needs attention";
    if (!windowOpen) return "expired";
    return "in progress";
  }

  static Map<String, Object> overview(JsonNode messages, long now) {
    int requests = 0, quoted = 0, booked = 0, attention = 0, inProgress = 0, expired = 0, declined = 0, onTime = 0;
    var quoteSecs = new ArrayList<Double>();
    double savedVsNext = 0;
    var bySource = new LinkedHashMap<String, Integer>(Map.of());
    bySource.put("portal", 0);
    bySource.put("tariff", 0);
    bySource.put("email bid", 0);
    var destinations = new LinkedHashMap<String, Integer>();
    for (var r : each(messages)) {
      if (!"request".equals(text(r, "kind"))) continue;
      requests++;
      String o = outcomeOf(r, now);
      switch (o) {
        case "booked" -> { booked++; quoted++; }
        case "quoted" -> quoted++;
        case "needs attention" -> attention++;
        case "expired" -> expired++;
        case "declined" -> declined++;
        default -> inProgress++;
      }
      var quote = lastQuote(r);
      Long qt = quote == null ? null : ms(quote.path("time"));
      Long rt = ms(r.path("received"));
      if (("quoted".equals(o) || "booked".equals(o)) && qt != null && rt != null) {
        double sec = (qt - rt) / 1000.0;
        quoteSecs.add(sec);
        if (sec <= WINDOW_MS / 1000.0) onTime++;
      }
      var offer = r.path("proposal").path("offer");
      if (truthy(offer, "source")) bySource.merge(text(offer, "source"), 1, Integer::sum);
      var totals = new ArrayList<Double>();
      for (var a : each(r.path("proposal").path("alternatives"))) {
        double t = jsNumber(a.path("total_eur"));
        if (Double.isFinite(t)) totals.add(t);
      }
      Collections.sort(totals);
      if (totals.size() >= 2) savedVsNext += totals.get(1) - totals.get(0);
      if (truthy(r.path("fields"), "destination")) destinations.merge(text(r.path("fields"), "destination"), 1, Integer::sum);
    }
    Collections.sort(quoteSecs);
    var byDestination = destinations.entrySet().stream()
        .sorted((a, b) -> b.getValue() - a.getValue() != 0 ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()))
        .map(e -> Map.<String, Object>of("code", e.getKey(), "n", e.getValue())).toList();
    var out = new LinkedHashMap<String, Object>();
    out.put("requests", requests);
    out.put("quoted", quoted);
    out.put("booked", booked);
    out.put("attention", attention);
    out.put("inProgress", inProgress);
    out.put("expired", expired);
    out.put("declined", declined);
    out.put("medianQuoteSeconds", jsRound(median(quoteSecs)));
    out.put("onTimeRate", quoteSecs.isEmpty() ? null : (double) onTime / quoteSecs.size());
    out.put("savedVsNext", Math.round(savedVsNext));
    out.put("bySource", bySource);
    out.put("byDestination", byDestination);
    return out;
  }

  /* ---------------- Shipments (crm.js) ---------------- */

  private static final Map<String, String> BOARD_REASONS = Map.of(
      "too_low", "Price too low", "too_high", "Price too high", "no_pdf_offer", "No PDF offer",
      "wrong_language", "Wrong language", "excluded_airline", "Excluded airline",
      "dg_docs_missing", "DG documents missing");
  private static final Pattern BOOKING_ATTENTION = Pattern.compile("(?i)^booking needs attention");

  record Result(String group, String key, String label, String tone) {}

  private static long threadOf(JsonNode m) {
    var thread = m.path("thread_id");
    if (!thread.isMissingNode() && !thread.isNull()) return thread.asLong();
    var root = m.path("root");
    long id = m.path("id").asLong();
    return !root.isMissingNode() && !root.isNull() && root.asLong() != id ? root.asLong() : id;
  }

  /** "lindqvist-medtech@hackloop.mail" → "Lindqvist Medtech". */
  static String customerName(String email) {
    String local = Objects.toString(email, "").split("@", -1)[0];
    if (local.isEmpty()) return "Unknown sender";
    var words = new ArrayList<String>();
    for (String w : local.split("[-_.]+")) if (!w.isEmpty()) words.add(Character.toUpperCase(w.charAt(0)) + w.substring(1));
    return String.join(" ", words);
  }

  private static Result resultOf(JsonNode r, boolean quoted, int followUpsAfterQuote, long now) {
    if (truthy(r.path("booking"), "booking_reference")) return new Result("won", "booked", "Booked", "ok");
    var shipments = shipmentsBooked(r);
    if (shipments[0] > 0) return new Result("won", "booked", "Booked" + shipmentsNote(shipments), "ok");
    var b = r.path("board_result");
    String verdict = text(b, "verdict").toLowerCase(Locale.ROOT);
    if ("rejected".equals(verdict)) {
      String key = truthy(b, "reason") ? text(b, "reason") : "rejected";
      String words = key.replace("_", " ");
      return new Result("lost", key, BOARD_REASONS.getOrDefault(key, words.isEmpty() ? "Rejected by board" : words), "bad");
    }
    if (BOOKING_ATTENTION.matcher(text(r, "review_reason")).find())
      return new Result("lost", "booking_blocked", "Booking blocked · carrier changed", "warn");
    String stage = text(r, "stage");
    if ("waiting for documents".equals(stage) || "waiting for customer".equals(stage) || ("review".equals(stage) && !quoted))
      return new Result("open", "hold", "waiting for customer".equals(stage) ? "Waiting for customer" : "On hold · documents", "warn");
    String outcome = outcomeOf(r, now);
    if (!quoted) {
      if ("expired".equals(outcome) || "expired".equals(stage)) return new Result("lost", "expired", "Expired before quote", "bad");
      if ("declined".equals(outcome)) return new Result("lost", "declined", "Declined by us", "muted");
      if ("needs attention".equals(outcome)) return new Result("open", "attention", "Needs attention", "warn");
      return new Result("open", "pricing", "Pricing", "accent");
    }
    if (b.path("closed").asBoolean(false) && b.path("booked").isBoolean() && !b.path("booked").asBoolean())
      return followUpsAfterQuote > 0
          ? new Result("lost", "not_booked", "Customer reply, not booked", "warn")
          : new Result("lost", "no_reply", "No customer reply", "muted");
    if ("no answer".equals(verdict)) return new Result("lost", "no_reply", "No customer reply", "muted");
    return new Result("open", "awaiting", "Awaiting customer", "info");
  }

  private static final Pattern CONFIRM = Pattern.compile("(?i)\\b(book|buchen|gebucht|réserv|reserv|proceed|go ahead|confirm)");

  /** One customer request with its commercial outcome (crm.js deal row). */
  record Deal(long id, String email, String customer, String origin, String destination, Double weightKg, Long received,
      boolean quoted, Long quotedAt, Double quoteSec, Double allIn, Double bookedAllIn, String carrier, Double days,
      boolean accepted, JsonNode board, JsonNode booking, Long bookedAt, Long lastActivity, Result result) {
    boolean booked() {
      return booking != null;
    }

    /** The row the Shipments tables render, in the same shape the desk's local model produced. */
    Map<String, Object> row() {
      var row = new LinkedHashMap<String, Object>();
      row.put("id", id);
      row.put("email", email);
      row.put("customer", customer);
      row.put("origin", origin);
      row.put("destination", destination);
      row.put("weightKg", weightKg);
      row.put("received", received);
      row.put("quoted", quoted);
      row.put("quotedAt", quotedAt);
      row.put("quoteSec", quoteSec);
      row.put("allIn", allIn);
      row.put("bookedAllIn", bookedAllIn);
      row.put("carrier", carrier);
      row.put("days", days);
      row.put("accepted", accepted);
      row.put("board", board);
      row.put("booking", booking);
      row.put("bookedAt", bookedAt);
      row.put("lastActivity", lastActivity);
      row.put("result", result);
      return row;
    }
  }

  /**
   * Booking time is not stored. Best proxy: the customer's booking instruction, i.e. the first
   * follow-up after the quote that asks to book (else the first follow-up after it).
   */
  private static Long bookedAtOf(Long quoteTime, List<JsonNode> followUps) {
    var after = followUps.stream()
        .filter(f -> quoteTime == null || Optional.ofNullable(ms(f.path("received"))).orElse(0L) >= quoteTime).toList();
    var hit = after.stream().filter(f -> CONFIRM.matcher(text(f, "body")).find()).findFirst()
        .orElse(after.isEmpty() ? null : after.getFirst());
    return hit == null ? null : ms(hit.path("received"));
  }

  /** Every customer request as a deal row, newest first. */
  static List<Deal> deals(JsonNode messages, long now) {
    var byThread = new LinkedHashMap<Long, List<JsonNode>>();
    for (var m : each(messages)) byThread.computeIfAbsent(threadOf(m), k -> new ArrayList<>()).add(m);

    var deals = new ArrayList<Deal>();
    for (var r : each(messages)) {
      if (!"request".equals(text(r, "kind"))) continue;
      var thread = byThread.getOrDefault(threadOf(r), List.of());
      Long firstQuote = null;
      boolean quotedState = false;
      for (var q : each(r.path("outgoing"))) {
        String kind = text(q, "kind");
        if (!"quote".equals(kind) && !"requote".equals(kind)) continue;
        Long t = ms(q.path("time"));
        if (t != null && (firstQuote == null || t < firstQuote)) firstQuote = t;
        if ("sent".equals(text(q, "state"))) quotedState = true;
      }
      boolean quoted = "sent".equals(text(r, "delivery")) || quotedState;
      Long received = ms(r.path("received"));
      var followUps = thread.stream().filter(m -> "follow-up".equals(text(m, "kind")))
          .sorted(Comparator.comparingLong(m -> Optional.ofNullable(ms(m.path("received"))).orElse(0L))).toList();
      final Long fq = firstQuote;
      int afterQuote = fq == null ? 0
          : (int) followUps.stream().filter(f -> Optional.ofNullable(ms(f.path("received"))).orElse(0L) >= fq).count();
      var offer = r.path("proposal").path("offer");
      Double allIn = num(offer.path("total_eur"));
      Double pickupValue = num(r.path("proposal").path("pickup_eur"));
      double pickup = pickupValue == null ? 0 : pickupValue;
      Double airPrice = num(r.path("booking").path("price"));
      boolean hasBooking = r.path("booking").isObject();
      Double bookedAllIn = hasBooking ? (airPrice != null ? airPrice + pickup : allIn) : null;
      var booking = bookingOf(r);
      if (booking != null && booking != r.path("booking")) {
        // Separately booked shipments: each booked air price plus that shipment's quoted pickup.
        double sum = 0;
        for (var leg : each(r.path("shipment_bookings"))) {
          if (!truthy(leg, "booking_reference")) continue;
          Double legAir = num(leg.path("price")), legPickup = num(leg.path("pickup_eur"));
          Double legTotal = num(leg.path("total_eur"));
          sum += legAir != null ? legAir + (legPickup == null ? 0 : legPickup) : legTotal == null ? 0 : legTotal;
        }
        bookedAllIn = sum;
      }
      long lastActivity = received == null ? 0 : received;
      for (var m : thread) lastActivity = Math.max(lastActivity, Optional.ofNullable(ms(m.path("received"))).orElse(0L));
      for (var o : each(r.path("outgoing"))) lastActivity = Math.max(lastActivity, Optional.ofNullable(ms(o.path("time"))).orElse(0L));
      String verdict = text(r.path("board_result"), "verdict").toLowerCase(Locale.ROOT);
      boolean booked = booking != null;
      var fields = r.path("fields");
      String carrier = booked && truthy(booking, "airline") ? text(booking, "airline")
          : truthy(r.path("booking"), "airline") ? text(r.path("booking"), "airline")
          : truthy(offer, "airline") ? text(offer, "airline") : null;
      deals.add(new Deal(r.path("id").asLong(), text(r, "sender").toLowerCase(Locale.ROOT), customerName(text(r, "sender")),
          truthy(fields, "origin") ? text(fields, "origin") : null,
          truthy(fields, "destination") ? text(fields, "destination") : null, num(fields.path("weight_kg")), received,
          quoted, firstQuote, quoted && firstQuote != null && received != null ? (firstQuote - received) / 1000.0 : null,
          allIn, bookedAllIn, carrier, num(offer.path("days")), quoted && "accepted".equals(verdict),
          r.path("board_result").isObject() ? r.path("board_result") : null, booked ? booking : null,
          booked ? bookedAtOf(firstQuote, followUps) : null, lastActivity == 0 ? null : lastActivity,
          resultOf(r, quoted, afterQuote, now)));
    }
    return deals;
  }

  static Map<String, Object> shipments(JsonNode messages, long now) {
    var deals = deals(messages, now);
    var bookings = deals.stream().filter(Deal::booked).toList();

    var customers = new LinkedHashMap<String, Map<String, Object>>();
    var lanes = new HashMap<String, LinkedHashMap<String, Integer>>();
    for (var d : deals) {
      var c = customers.computeIfAbsent(d.email(), email -> {
        var row = new LinkedHashMap<String, Object>();
        row.put("email", email);
        row.put("name", d.customer());
        for (String k : List.of("requests", "quoted", "won", "lost", "open")) row.put(k, 0);
        row.put("quotedValue", 0.0);
        row.put("bookedValue", 0.0);
        row.put("lastActivity", null);
        row.put("latestId", null);
        return row;
      });
      c.merge("requests", 1, (a, b) -> (int) a + (int) b);
      if (d.quoted()) {
        c.merge("quoted", 1, (a, b) -> (int) a + (int) b);
        c.put("quotedValue", (double) c.get("quotedValue") + (d.allIn() == null ? 0 : d.allIn()));
      }
      switch (d.result().group()) {
        case "won" -> {
          c.merge("won", 1, (a, b) -> (int) a + (int) b);
          c.put("bookedValue", (double) c.get("bookedValue") + (d.bookedAllIn() == null ? 0 : d.bookedAllIn()));
        }
        case "lost" -> c.merge("lost", 1, (a, b) -> (int) a + (int) b);
        default -> c.merge("open", 1, (a, b) -> (int) a + (int) b);
      }
      if (d.lastActivity() != null && (c.get("lastActivity") == null || d.lastActivity() > (long) c.get("lastActivity"))) {
        c.put("lastActivity", d.lastActivity());
        c.put("latestId", d.id());
      }
      if (d.destination() != null) lanes.computeIfAbsent(d.email(), k -> new LinkedHashMap<>()).merge(d.destination(), 1, Integer::sum);
    }
    for (var c : customers.values()) {
      int won = (int) c.get("won"), lost = (int) c.get("lost");
      c.put("winRate", won + lost == 0 ? null : (double) won / (won + lost));
      var mine = lanes.getOrDefault((String) c.get("email"), new LinkedHashMap<>());
      String top = null;
      int best = -1;
      for (var e : mine.entrySet()) if (e.getValue() > best) { best = e.getValue(); top = e.getKey(); }
      c.put("topDestination", top);
    }

    long quotedCount = deals.stream().filter(Deal::quoted).count();
    var funnel = List.of(
        Map.of("key", "rfq", "label", "RFQs received", "n", deals.size()),
        Map.of("key", "quoted", "label", "Quoted", "n", quotedCount),
        Map.of("key", "accepted", "label", "Accepted by board", "n", deals.stream().filter(Deal::accepted).count()),
        Map.of("key", "booked", "label", "Booked", "n", bookings.size()));

    var reasonMap = new LinkedHashMap<String, Map<String, Object>>();
    for (var d : deals) {
      if ("won".equals(d.result().group())) continue;
      String id = d.result().group() + ":" + d.result().key();
      var row = reasonMap.computeIfAbsent(id, k -> {
        var x = new LinkedHashMap<String, Object>();
        x.put("id", k);
        x.put("group", d.result().group());
        x.put("key", d.result().key());
        x.put("label", d.result().label());
        x.put("tone", d.result().tone());
        x.put("n", 0);
        x.put("value", 0.0);
        return x;
      });
      row.put("n", (int) row.get("n") + 1);
      row.put("value", (double) row.get("value") + (d.allIn() == null ? 0 : d.allIn()));
    }
    var reasons = new ArrayList<>(reasonMap.values());
    reasons.sort((a, b) -> (int) b.get("n") != (int) a.get("n") ? (int) b.get("n") - (int) a.get("n")
        : localeCompare((String) a.get("label"), (String) b.get("label")));

    var quoteSecs = deals.stream().map(Deal::quoteSec).filter(Objects::nonNull).sorted().toList();
    double bookedValue = bookings.stream().mapToDouble(d -> d.bookedAllIn() == null ? 0 : d.bookedAllIn()).sum();
    var open = deals.stream().filter(d -> "open".equals(d.result().group())).toList();
    var kpis = new LinkedHashMap<String, Object>();
    kpis.put("conversion", deals.isEmpty() ? null : (double) bookings.size() / deals.size());
    kpis.put("quoteToBook", quotedCount == 0 ? null : (double) bookings.size() / quotedCount);
    kpis.put("bookedValue", bookedValue);
    kpis.put("avgBooking", bookings.isEmpty() ? null : bookedValue / bookings.size());
    kpis.put("avgQuoteSec", quoteSecs.isEmpty() ? null : quoteSecs.stream().mapToDouble(Double::doubleValue).sum() / quoteSecs.size());
    kpis.put("medianQuoteSec", median(quoteSecs));
    kpis.put("fastQuotes", quoteSecs.stream().filter(s -> s <= 120).count());
    kpis.put("quoteCount", quoteSecs.size());
    kpis.put("quotedValue", deals.stream().mapToDouble(d -> d.quoted() && d.allIn() != null ? d.allIn() : 0).sum());
    kpis.put("openCount", open.size());
    kpis.put("openValue", open.stream().mapToDouble(d -> d.allIn() == null ? 0 : d.allIn()).sum());
    kpis.put("lostCount", deals.stream().filter(d -> "lost".equals(d.result().group())).count());
    kpis.put("lostValue", deals.stream()
        .mapToDouble(d -> "lost".equals(d.result().group()) && d.quoted() && d.allIn() != null ? d.allIn() : 0).sum());

    var out = new LinkedHashMap<String, Object>();
    out.put("kpis", kpis);
    out.put("funnel", funnel);
    out.put("reasons", reasons);
    out.put("customers", new ArrayList<>(customers.values()));
    return out;
  }

  /* ---------------- Scoreboard (score.js) ---------------- */

  private record Criterion(String key, String label, int max, String hint) {}
  private static final List<Criterion> CRITERIA = List.of(
      new Criterion("accuracy", "Price accuracy", 45, "Full at the best price"),
      new Criterion("cost", "LLM cost", 40, "Full at no gateway spend"),
      new Criterion("time", "Response time", 15, "Full within 2 minutes"));
  private static final List<String[]> BONUSES = List.of(
      new String[] {"tracking", "Tracking answers", "10"}, new String[] {"humanity", "Most human email", "10"});
  private static final Map<String, String> SCORE_REASONS = Map.of(
      "too_low", "Price too low", "too_high", "Price too high", "no_pdf_offer", "No PDF offer",
      "wrong_language", "Wrong language", "excluded_airline", "Excluded airline",
      "dg_docs_missing", "DG documents missing");

  static String reasonLabel(String reason) {
    if (reason == null || reason.isEmpty()) return "No reason given";
    if (SCORE_REASONS.containsKey(reason)) return SCORE_REASONS.get(reason);
    String words = reason.replace("_", " ");
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }

  /** BoardScore.boardTotal: the board's total, else the sum of its finite points, else null. */
  static Double boardTotal(JsonNode result) {
    if (result == null || result.isMissingNode() || result.isNull()) return null;
    var total = result.path("total");
    if (!total.isMissingNode() && !total.isNull()) return jsNumber(total);
    double sum = 0;
    int n = 0;
    for (var p : (Iterable<JsonNode>) () -> result.path("points").elements()) {
      double v = jsNumber(p);
      if (Double.isFinite(v)) { sum += v; n++; }
    }
    return n == 0 ? null : sum;
  }

  private record Round(long id, String received, JsonNode result, Double total, String verdict) {}

  static Map<String, Object> score(JsonNode messages) {
    var rounds = new ArrayList<Round>();
    for (var m : each(messages)) {
      var b = m.path("board_result");
      if (!b.isObject()) continue;
      rounds.add(new Round(m.path("id").asLong(), text(m, "received"), b, boardTotal(b),
          text(b, "verdict").toLowerCase(Locale.ROOT)));
    }
    rounds.sort(Comparator.<Round>comparingDouble(r -> r.result().path("round_no").isNumber() ? r.result().path("round_no").asDouble() : 0)
        .thenComparingLong(r -> Optional.ofNullable(ms(com.fasterxml.jackson.databind.node.TextNode.valueOf(r.received()))).orElse(0L)));
    var accepted = rounds.stream().filter(r -> "accepted".equals(r.verdict())).toList();
    var rejected = rounds.stream().filter(r -> "rejected".equals(r.verdict())).toList();
    var unanswered = rounds.stream().filter(r -> "no answer".equals(r.verdict())).toList();

    var criteria = new ArrayList<Map<String, Object>>();
    for (var c : CRITERIA) {
      var values = accepted.stream().map(r -> jsNumber(r.result().path("points").path(c.key()))).filter(Double::isFinite).toList();
      var row = new LinkedHashMap<String, Object>();
      row.put("key", c.key());
      row.put("label", c.label());
      row.put("max", c.max());
      row.put("hint", c.hint());
      row.put("avg", values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).sum() / values.size());
      row.put("full", values.stream().filter(v -> v >= c.max() - 0.005).count());
      row.put("n", values.size());
      criteria.add(row);
    }
    var bonuses = new ArrayList<Map<String, Object>>();
    for (var b : BONUSES) {
      var values = rounds.stream().map(r -> jsNumber(r.result().path("points").path(b[0]))).filter(Double::isFinite).toList();
      var row = new LinkedHashMap<String, Object>();
      row.put("key", b[0]);
      row.put("label", b[1]);
      row.put("each", Integer.parseInt(b[2]));
      row.put("total", values.stream().mapToDouble(Double::doubleValue).sum());
      row.put("earned", values.stream().filter(v -> v > 0).count());
      row.put("n", values.size());
      bonuses.add(row);
    }
    var adjustments = rounds.stream().map(r -> jsNumber(r.result().path("points").path("adjustment"))).filter(Double::isFinite).toList();

    var groups = new LinkedHashMap<String, List<Round>>();
    for (var r : rejected) groups.computeIfAbsent(reasonLabel(text(r.result(), "reason").isEmpty() ? null : text(r.result(), "reason")),
        k -> new ArrayList<>()).add(r);
    var losses = new ArrayList<Map<String, Object>>();
    for (var e : groups.entrySet())
      losses.add(Map.of("label", e.getKey(), "n", e.getValue().size(), "unanswered", false, "latestId", e.getValue().getLast().id()));
    if (!unanswered.isEmpty())
      losses.add(Map.of("label", "No answer from the board", "n", unanswered.size(), "unanswered", true,
          "latestId", unanswered.getLast().id()));
    losses.sort((a, b) -> (int) b.get("n") - (int) a.get("n"));

    double total = rounds.stream().mapToDouble(r -> r.total() == null ? 0 : r.total()).sum();
    var out = new LinkedHashMap<String, Object>();
    out.put("rounds", rounds.stream().map(r -> {
      var row = new LinkedHashMap<String, Object>();
      row.put("id", r.id());
      row.put("received", r.received());
      row.put("round_no", r.result().path("round_no").isNumber() ? r.result().path("round_no").asLong() : null);
      row.put("verdict", text(r.result(), "verdict"));
      row.put("total", r.total());
      return row;
    }).toList());
    out.put("total", total);
    out.put("accepted", accepted.size());
    out.put("rejected", rejected.size());
    out.put("unanswered", unanswered.size());
    out.put("perAccepted", accepted.isEmpty() ? null
        : accepted.stream().mapToDouble(r -> r.total() == null ? 0 : r.total()).sum() / accepted.size());
    out.put("criteria", criteria);
    out.put("bonuses", bonuses);
    out.put("adjustment", Map.of("total", adjustments.stream().mapToDouble(Double::doubleValue).sum(),
        "n", adjustments.stream().filter(v -> v != 0).count()));
    out.put("losses", losses);
    out.put("pending", rounds.stream().filter(r -> r.result().path("cost_pending").asBoolean(false)).count());
    out.put("maxTotal", Math.max(100, rounds.stream().mapToDouble(r -> r.total() == null ? 0 : r.total()).max().orElse(0)));
    return out;
  }
}
