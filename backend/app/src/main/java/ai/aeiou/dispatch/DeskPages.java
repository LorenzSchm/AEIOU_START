package ai.aeiou.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Function;

/**
 * Paged rows for the desk's Queue and Shipments tables. Filtering, search and sorting follow the rules
 * the desk applied in the browser (Queue.jsx, Crm.jsx), so a page shows exactly what the full list
 * showed, just one page at a time.
 */
final class DeskPages {
  private DeskPages() {}

  private static final List<String> ATTENTION_STAGES =
      List.of("review", "waiting for pickup", "waiting for air price", "operator review");

  /* ---------------- Queue ---------------- */

  private static String text(JsonNode node, String field) {
    var v = node.path(field);
    return v.isNull() || v.isMissingNode() ? "" : v.asText();
  }

  /** JavaScript's String(value) for a concatenation: null and missing become "null"/"undefined". */
  private static String jsString(JsonNode node, String field) {
    var v = node.path(field);
    return v.isMissingNode() ? "undefined" : v.isNull() ? "null" : v.asText();
  }

  static boolean topLevel(JsonNode m) {
    var root = m.path("root");
    return root.isMissingNode() || root.isNull() || root.asLong() == 0 || root.asLong() == m.path("id").asLong();
  }

  /** format.js needsAttention: a request waiting on a person whose quote has not been sent. */
  static boolean needsAttention(JsonNode m) {
    String delivery = text(m, "delivery");
    return "request".equals(text(m, "kind"))
        && (ATTENTION_STAGES.contains(text(m, "stage")) || "uncertain".equals(delivery) || "pending".equals(delivery))
        && !"sent".equals(delivery);
  }

  static long threadKey(JsonNode m) {
    var thread = m.path("thread_id");
    if (!thread.isMissingNode() && !thread.isNull()) return thread.asLong();
    var root = m.path("root");
    long id = m.path("id").asLong();
    return !root.isMissingNode() && !root.isNull() && root.asLong() != id ? root.asLong() : id;
  }

  private static String queueText(JsonNode m) {
    var fields = m.path("fields");
    return (jsString(m, "subject") + " " + jsString(m, "sender") + " " + m.path("id").asText() + " "
        + text(m, "body") + " " + text(m, "voice_transcript") + " " + text(fields, "origin") + " "
        + text(fields, "destination")).toLowerCase(Locale.ROOT);
  }

  /**
   * One page of the queue, newest first. {@code threads} holds the messages of every thread a row
   * belongs to (rows show their process from it), each thread sent once.
   */
  static Map<String, Object> queue(JsonNode messages, String scope, String query, Long before, int limit) {
    var top = new ArrayList<JsonNode>();
    int attention = 0, requests = 0;
    var byThread = new LinkedHashMap<Long, List<JsonNode>>();
    for (var m : messages) {
      byThread.computeIfAbsent(threadKey(m), k -> new ArrayList<>()).add(m);
      if (!topLevel(m)) continue;
      top.add(m);
      if (needsAttention(m)) attention++;
      if ("request".equals(text(m, "kind"))) requests++;
    }
    List<JsonNode> base = switch (scope == null ? "attention" : scope) {
      case "attention" -> top.stream().filter(DeskPages::needsAttention).toList();
      case "requests" -> top.stream().filter(m -> "request".equals(text(m, "kind"))).toList();
      case "all" -> { var all = new ArrayList<JsonNode>(); messages.forEach(all::add); yield all; }
      default -> throw new IllegalArgumentException("scope must be attention, requests or all.");
    };
    String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
    var matching = base.stream().filter(m -> q.isEmpty() || queueText(m).contains(q))
        .filter(m -> before == null || m.path("id").asLong() < before).toList();
    var rows = matching.stream().limit(limit).toList();
    var threads = new LinkedHashMap<String, List<JsonNode>>();
    for (var m : rows) threads.putIfAbsent(Long.toString(threadKey(m)), byThread.getOrDefault(threadKey(m), List.of()));
    var out = new LinkedHashMap<String, Object>();
    out.put("messages", rows);
    out.put("threads", threads);
    out.put("counts", Map.of("attention", attention, "requests", requests));
    out.put("matches", matching.size());
    out.put("next_before", matching.size() > limit ? rows.getLast().path("id").asLong() : null);
    return out;
  }

  /* ---------------- Shipments ---------------- */

  private static Object bookingValue(DeskSummary.Deal d, String field) {
    var v = d.booking() == null ? null : d.booking().path(field);
    return v == null || v.isMissingNode() || v.isNull() || v.asText().isEmpty() ? null : v.asText();
  }

  private static final Map<String, Function<DeskSummary.Deal, Object>> BOOKING_SORT = Map.of(
      "ref", d -> bookingValue(d, "booking_reference"),
      "customer", DeskSummary.Deal::customer,
      "lane", d -> Objects.toString(d.origin(), "") + Objects.toString(d.destination(), ""),
      "carrier", DeskSummary.Deal::carrier,
      "weight", DeskSummary.Deal::weightKg,
      "price", DeskSummary.Deal::bookedAllIn,
      "transit", DeskSummary.Deal::days,
      "booked", d -> d.bookedAt() != null ? d.bookedAt() : d.received(),
      "status", d -> d.board() != null && d.board().path("booked").isBoolean() && !d.board().path("booked").asBoolean() ? 0 : 1);

  private static final Map<String, Function<DeskSummary.Deal, Object>> PIPELINE_SORT = Map.of(
      "id", DeskSummary.Deal::id,
      "customer", DeskSummary.Deal::customer,
      "lane", d -> Objects.toString(d.origin(), "") + Objects.toString(d.destination(), ""),
      "weight", DeskSummary.Deal::weightKg,
      "price", d -> d.quoted() ? d.allIn() : null,
      "reason", d -> d.result().label(),
      "received", DeskSummary.Deal::received);

  /** Crm.jsx sortRows: blanks last in both directions, text by locale, numbers numerically. */
  static Comparator<DeskSummary.Deal> order(Function<DeskSummary.Deal, Object> value, int dir) {
    return (a, b) -> {
      Object va = value.apply(a), vb = value.apply(b);
      if (va == null && vb == null) return 0;
      if (va == null) return 1;
      if (vb == null) return -1;
      int c = va instanceof String sa ? DeskSummary.localeCompare(sa, (String) vb)
          : Double.compare(((Number) va).doubleValue(), ((Number) vb).doubleValue());
      return c * dir;
    };
  }

  private static boolean matches(DeskSummary.Deal d, String q) {
    if (q.isEmpty()) return true;
    var parts = new ArrayList<String>();
    for (Object part : new Object[] {d.customer(), d.email(), d.origin(), d.destination(), d.carrier(),
        bookingValue(d, "booking_reference"), bookingValue(d, "team_reference"), "#" + d.id(), d.result().label()})
      if (part != null && !part.toString().isEmpty()) parts.add(part.toString());
    return String.join(" ", parts).toLowerCase(Locale.ROOT).contains(q);
  }

  /** One page of the Bookings or the Lost & open table. */
  static Map<String, Object> shipments(List<DeskSummary.Deal> deals, String tab, String customer, String reason,
      String query, String sort, int dir, int limit, int offset) {
    boolean bookings = "bookings".equals(tab);
    if (!bookings && !"pipeline".equals(tab)) throw new IllegalArgumentException("tab must be bookings or pipeline.");
    var sortKeys = bookings ? BOOKING_SORT : PIPELINE_SORT;
    String key = sort == null ? (bookings ? "booked" : "received") : sort;
    if (!sortKeys.containsKey(key)) throw new IllegalArgumentException("Unknown sort column " + key + ".");
    if (dir != 1 && dir != -1) throw new IllegalArgumentException("dir must be 1 or -1.");
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    var rows = deals.stream()
        .filter(d -> bookings ? d.booked() : !"won".equals(d.result().group()))
        .filter(d -> customer == null || customer.isEmpty() || customer.equals(d.email()))
        .filter(d -> bookings || reason == null || reason.isEmpty()
            || reason.equals(d.result().group() + ":" + d.result().key()))
        .filter(d -> matches(d, q))
        .sorted(order(sortKeys.get(key), dir)).toList();
    var out = new LinkedHashMap<String, Object>();
    out.put("rows", rows.stream().skip(offset).limit(limit).map(DeskSummary.Deal::row).toList());
    out.put("total", rows.size());
    out.put("offset", offset);
    out.put("limit", limit);
    return out;
  }
}
