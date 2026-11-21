package ai.aeiou.dispatch;

import ai.aeiou.freight.Catalog;
import java.time.Duration;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Mirrors the carrier directory and every carrier invitation and bid to Supabase. SQLite stays the
 * worker's source of truth. This runs on its own scheduler tick and never throws into the wave.
 */
@Component
class SupabaseSync {
  private static final Logger log = LoggerFactory.getLogger(SupabaseSync.class);
  private final Ledger ledger;
  private final Catalog catalog;
  private final RestClient client;
  // Last row pushed per key, so each tick only sends inserts and changes.
  private final Map<String, String> pushed = new HashMap<>();
  private boolean carriersPushed;
  private String error;

  SupabaseSync(
      Ledger ledger,
      Catalog catalog,
      @Value("${supabase.url}") String url,
      @Value("${supabase.secret-key}") String key) {
    this.ledger = ledger;
    this.catalog = catalog;
    if (url.isBlank() || key.isBlank()) {
      client = null;
      return;
    }
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(10));
    var builder =
        RestClient.builder()
            .baseUrl(url.replaceAll("/+$", "") + "/rest/v1")
            .requestFactory(factory)
            .defaultHeader("apikey", key)
            .defaultHeader("Prefer", "resolution=merge-duplicates,return=minimal");
    // Legacy service_role keys are JWTs. New sb_secret_ keys go in the apikey header only.
    if (key.startsWith("eyJ")) builder.defaultHeader("Authorization", "Bearer " + key);
    client = builder.build();
  }

  @Scheduled(fixedDelayString = "${supabase.sync-ms}")
  synchronized void sync() {
    if (client == null) return;
    try {
      if (!carriersPushed) {
        upsert("carriers", "email", carriers());
        carriersPushed = true;
      }
      var changed = new ArrayList<Map<String, Object>>();
      var keys = new HashMap<String, String>();
      for (var row : bids()) {
        String key = row.get("request_id") + "|" + row.get("carrier_email");
        String value = ledger.write(row);
        if (!value.equals(pushed.get(key))) {
          changed.add(row);
          keys.put(key, value);
        }
      }
      if (!changed.isEmpty()) {
        upsert("carrier_bids", "request_id,carrier_email", changed);
        pushed.putAll(keys);
      }
      error = null;
    } catch (RuntimeException ex) {
      if (error == null) log.warn("Supabase sync failed: {}", ex.getMessage());
      error = "Supabase sync failed (" + ex.getClass().getSimpleName() + ")";
    }
  }

  private void upsert(String table, String conflict, List<Map<String, Object>> rows) {
    client
        .post()
        .uri("/" + table + "?on_conflict=" + conflict)
        .body(rows)
        .retrieve()
        .toBodilessEntity();
  }

  List<Map<String, Object>> carriers() {
    return catalog.carriers.stream()
        .map(
            c -> {
              var row = new LinkedHashMap<String, Object>();
              row.put("email", c.email());
              row.put("name", c.name());
              row.put("kind", c.kind());
              row.put("serves", Arrays.stream(c.serves().split(" ")).filter(s -> !s.isBlank()).toList());
              return (Map<String, Object>) row;
            })
        .toList();
  }

  List<Map<String, Object>> bids() {
    var result = new ArrayList<Map<String, Object>>();
    for (var t :
        ledger
            .jdbc()
            .queryForList(
                """
                SELECT t.*, d.state AS invite_state, d.attempted_at, json_extract(d.response,'$.id') AS invite_message_id,
                  m.subject AS rfq_subject, m.received AS rfq_received, r.received AS replied_at
                FROM tender t
                LEFT JOIN deliveries d ON d.action=COALESCE(t.bid_action,'bid:'||t.request_id||':'||t.email)
                LEFT JOIN mail m ON m.id=t.request_id
                LEFT JOIN mail r ON r.id=t.reply_id
                ORDER BY t.request_id, t.email
                """)) {
      Map<?, ?> terms = ledger.read(t.get("terms"), Map.class);
      if (terms == null) terms = Map.of();
      Object inviteState = t.get("invite_state");
      String status =
          t.get("reply_id") != null
              ? t.get("euros") != null ? "priced" : "unreadable"
              : inviteState == null ? "not sent" : "sent".equals(inviteState) ? "invited" : inviteState.toString();
      var row = new LinkedHashMap<String, Object>();
      row.put("request_id", t.get("request_id"));
      row.put("carrier_email", t.get("email"));
      row.put("leg", t.get("leg"));
      row.put("status", status);
      row.put("rfq_subject", t.get("rfq_subject"));
      row.put("rfq_received_at", t.get("rfq_received"));
      row.put("origin", terms.get("origin"));
      row.put("destination", terms.get("destination"));
      row.put("weight_kg", terms.get("weight_kg"));
      row.put("volume_cbm", terms.get("volume_cbm"));
      row.put("preference", terms.get("preference"));
      row.put("pickup_zone", terms.get("pickup_zone"));
      row.put("invite_action", Ledger.bidAction(t));
      row.put("invite_state", inviteState);
      row.put("invite_message_id", t.get("invite_message_id"));
      row.put("invited_at", t.get("attempted_at"));
      row.put("reply_id", t.get("reply_id"));
      row.put("replied_at", t.get("replied_at"));
      row.put("price_eur", t.get("euros"));
      row.put("transit_days", t.get("days"));
      row.put("issue", t.get("issue"));
      row.put("terms", terms.isEmpty() ? null : terms);
      result.add(row);
    }
    return result;
  }
}
