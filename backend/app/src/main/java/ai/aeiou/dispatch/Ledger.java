package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class Ledger {
  static final long COUNTER_WAIT_SECONDS = 20;
  private static final Pattern CASE_REFERENCE = Pattern.compile("(?i)\\bAEIOU-(\\d{1,18})\\b");
  private static final Pattern COUNTER_REFERENCE =
      Pattern.compile("(?i)\\bAEIOU-(\\d{1,18})\\s*\\|\\s*counteroffer\\b");
  private static final Pattern AIRLINE_ROUTES = Pattern.compile(
      "(?m)^We fly ([A-Z]{3}-[A-Z]{3}(?:,\\h*[A-Z]{3}-[A-Z]{3})*)\\.?\\h*$");
  private static final Pattern REVISED_RATE =
      Pattern.compile(
          "(?i)Revised all-in rate:\\s*EUR\\s+\\d+(?:[.,]\\d{1,2})?\\s*\\.?"
              + "(?:\\s*Transit:\\s*(?:\\d+\\s*(?:days?|hours?)|same day)\\s*\\.?)?");
  private final JdbcTemplate db;

  JdbcTemplate jdbc() {
    return db;
  }

  private final TransactionTemplate tx;

  TransactionTemplate transactions() {
    return tx;
  }

  final ObjectMapper json;

  Ledger(@Value("${dispatch.db}") String file, ObjectMapper json) throws Exception {
    this.json = json;
    var path = Path.of(file).toAbsolutePath();
    Files.createDirectories(path.getParent());
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + path + "?busy_timeout=10000");
    db = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    db.execute("PRAGMA journal_mode=WAL");
    db.execute("CREATE TABLE IF NOT EXISTS cursor(id INTEGER PRIMARY KEY,value INTEGER NOT NULL)");
    db.update("INSERT OR IGNORE INTO cursor VALUES(1,0)");
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS mail(id INTEGER PRIMARY KEY,root INTEGER,sender TEXT,subject TEXT,body TEXT,
        received TEXT,kind TEXT,fields TEXT,attachments TEXT,stage TEXT DEFAULT 'review',proposal TEXT,booking TEXT)
        """);
    db.execute(
        "CREATE TABLE IF NOT EXISTS deliveries(action TEXT PRIMARY KEY,state TEXT NOT NULL,response"
            + " TEXT)");
    tx.executeWithoutResult(
        t -> {
          var columns =
              db.queryForList("SELECT name FROM pragma_table_info('deliveries')", String.class);
          if (!columns.contains("payload"))
            db.execute("ALTER TABLE deliveries ADD COLUMN payload TEXT");
          if (!columns.contains("attempted_at"))
            db.execute("ALTER TABLE deliveries ADD COLUMN attempted_at TEXT");
        });
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS wave_case(id INTEGER PRIMARY KEY, reason TEXT, offers TEXT, queried INTEGER DEFAULT 0,
        opened INTEGER NOT NULL, operator INTEGER NOT NULL DEFAULT 0)
        """);
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS tender(request_id INTEGER,email TEXT,leg TEXT,reply_id INTEGER,euros TEXT,days INTEGER,
        issue TEXT, terms TEXT, PRIMARY KEY(request_id,email))
        """);
    ensureColumn("tender", "bid_action", "TEXT");
    ensureColumn("wave_case", "customer_reply", "INTEGER");
    ensureColumn("wave_case", "portal_version", "TEXT");
    ensureColumn("wave_case", "invitation_lane", "TEXT");
    ensureColumn("wave_case", "invitation_airlines", "TEXT");
    db.execute(
        "CREATE TABLE IF NOT EXISTS retry(action TEXT PRIMARY KEY, after_epoch INTEGER NOT NULL)");
    db.execute(
        "CREATE TABLE IF NOT EXISTS board_result(received TEXT PRIMARY KEY, result TEXT NOT NULL)");
    db.execute(
        "CREATE TABLE IF NOT EXISTS truck_bid_parse(reply_id INTEGER PRIMARY KEY, state TEXT NOT NULL,"
            + " result TEXT, verified_days REAL)");
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS rfq_parse(request_id INTEGER PRIMARY KEY, state TEXT NOT NULL,
        model TEXT NOT NULL, result TEXT)
        """);
    db.execute(
        "CREATE TABLE IF NOT EXISTS document_check(request_id INTEGER PRIMARY KEY, checked_through"
            + " INTEGER NOT NULL)");
    db.execute(
        "CREATE TABLE IF NOT EXISTS booking_attempt(request_id INTEGER PRIMARY KEY,rate_id TEXT NOT"
            + " NULL,retry_at INTEGER DEFAULT 0,issue TEXT)");
    // Existing operator drafts are preserved across the Python-to-Java handoff.
    db.update(
        "INSERT OR IGNORE INTO wave_case(id,opened,operator,reason) SELECT id,0,1,'Existing"
            + " operator case' FROM mail WHERE kind='request'");
  }

  private void ensureColumn(String table, String column, String type) {
    if (!db.queryForList("SELECT name FROM pragma_table_info(?)", String.class, table)
        .contains(column)) db.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
  }

  String write(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  <T> T read(Object v, Class<T> type) {
    if (v == null) return null;
    try {
      return json.readValue(v.toString(), type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  static String str(Map<?, ?> map, String... keys) {
    for (String key : keys) if (map.get(key) != null) return map.get(key).toString();
    return "";
  }

  static long id(Object n) {
    return Long.parseLong(n.toString());
  }

  static Instant instant(Object raw) {
    if (raw == null) return null;
    String s = raw.toString().trim();
    try {
      if (s.matches("\\d{10,13}"))
        return s.length() == 13
            ? Instant.ofEpochMilli(Long.parseLong(s))
            : Instant.ofEpochSecond(Long.parseLong(s));
      s = s.replace(' ', 'T');
      try {
        return Instant.parse(s);
      } catch (DateTimeException ignored) {
        return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
      }
    } catch (DateTimeException ex) {
      return null;
    }
  }

  int cursor() {
    return db.queryForObject("SELECT value FROM cursor WHERE id=1", Integer.class);
  }

  Map<String, Object> mail(long id) {
    return db.queryForList("SELECT * FROM mail WHERE id=?", id).stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown message"));
  }

  String delivery(String key) {
    return db
        .queryForList("SELECT state FROM deliveries WHERE action=?", String.class, key)
        .stream()
        .findFirst()
        .orElse(null);
  }

  List<Map<String, Object>> tenders(long id) {
    return db.queryForList("SELECT * FROM tender WHERE request_id=? ORDER BY email", id);
  }

  Map<String, Set<String>> airlineRoutesBefore(long request) {
    var routes = new HashMap<String, Set<String>>();
    for (var row : db.queryForList("""
        SELECT sender,body FROM mail WHERE kind='carrier' AND id<? AND body LIKE '%We fly %'
        ORDER BY id DESC
        """, request)) {
      String sender = str(row, "sender");
      if (routes.containsKey(sender)) continue;
      var declared = AIRLINE_ROUTES.matcher(MailParser.authoredText(str(row, "body")));
      if (declared.find())
        routes.put(sender, Set.copyOf(Arrays.asList(declared.group(1).split(",\\h*"))));
    }
    return routes;
  }

  Instant lastInvitation(long request) {
    return db.queryForList("""
        SELECT d.attempted_at FROM tender t JOIN deliveries d
          ON d.action=COALESCE(t.bid_action,'bid:'||t.request_id||':'||t.email)
        WHERE t.request_id=? AND d.state IN ('sent','pending','uncertain')
        """, String.class, request).stream().map(Ledger::instant).filter(Objects::nonNull)
        .max(Instant::compareTo).orElse(null);
  }

  static String bidAction(Map<String, Object> tender) {
    return tender.get("bid_action") == null
        ? "bid:" + tender.get("request_id") + ":" + tender.get("email")
        : str(tender, "bid_action");
  }

  boolean counterAnswered(long request) {
    return !db.queryForList(
            "SELECT id FROM mail WHERE root=? AND stage IN ('counter accepted','counter closed')"
                + " LIMIT 1",
            request)
        .isEmpty();
  }

  void stage(long id, String stage, String reason) {
    tx.executeWithoutResult(
        t -> {
          db.update("UPDATE mail SET stage=? WHERE id=?", stage, id);
          db.update("UPDATE wave_case SET reason=? WHERE id=?", reason, id);
        });
  }

  void ingest(Map<String, Object> page, Catalog catalog, Instant now) {
    tx.executeWithoutResult(
        t -> {
          for (Object raw : (List<?>) page.get("messages")) {
            @SuppressWarnings("unchecked")
            var m = (Map<String, Object>) raw;
            long mid = id(m.get("id"));
            String sender =
                str(m, "from", "from_address", "sender", "from_email", "from_addr", "sender_email")
                    .toLowerCase(Locale.ROOT);
            String body = str(m, "body"), subject = str(m, "subject");
            List<?> refs = m.get("references") instanceof List<?> r ? r : List.of();
            long root =
                refs.isEmpty()
                    ? id(
                        m.getOrDefault("in_reply_to", mid) == null
                            ? mid
                            : m.getOrDefault("in_reply_to", mid))
                    : id(refs.getFirst());
            // An unthreaded response to our existing tender is follow-up mail even when the sender
            // is unknown.
            var reference = CASE_REFERENCE.matcher(subject);
            if (root == mid && reference.find()) {
              long referenced = Long.parseLong(reference.group(1));
              if (!db.queryForList(
                      "SELECT 1 FROM tender WHERE request_id=? LIMIT 1", Integer.class, referenced)
                  .isEmpty()) root = referenced;
            }
            var carrier = catalog.contact(sender);
            String kind =
                carrier.isPresent()
                    ? "carrier"
                    : sender.equals("ratedesk@hackloop.mail")
                        ? "ratedesk"
                        : sender.equals("tracking@hackloop.mail")
                            ? "tracking"
                            : root != mid ? "follow-up" : "request";
            List<?> attachments = m.get("attachments") instanceof List<?> a ? a : List.of();
            var received =
                instant(
                    str(
                        m,
                        "sent_at",
                        "created_at",
                        "received_at",
                        "timestamp",
                        "date",
                        "sent_at_utc",
                        "created_at_utc",
                        "ts"));
            var parsed = MailParser.request(subject, body, !attachments.isEmpty(), catalog);
            int inserted =
                db.update(
                    "INSERT OR IGNORE INTO"
                        + " mail(id,root,sender,subject,body,received,kind,fields,attachments,stage)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                    mid,
                    root,
                    sender,
                    subject,
                    body,
                    received == null ? null : received.toString(),
                    kind,
                    write(parsed.shipment()),
                    write(attachments),
                    kind.equals("request") ? "new" : "received");
            if (inserted == 0) continue;
            if (kind.equals("request"))
              db.update(
                  "INSERT INTO wave_case(id,opened,reason) VALUES(?,?,?)",
                  mid,
                  now.getEpochSecond(),
                  parsed.issue());
            if (carrier.isPresent())
              recordBid(mid, m, sender, subject, body, refs, carrier.get().truck(), catalog, now);
          }
          db.update("UPDATE cursor SET value=max(value,?) WHERE id=1", page.get("next_since_id"));
        });
  }

  private void recordBid(
      long mid,
      Map<String, Object> m,
      String sender,
      String subject,
      String body,
      List<?> refs,
      boolean truck,
      Catalog catalog,
      Instant now) {
    var parents = new HashSet<>(refs.stream().map(Object::toString).toList());
    if (m.get("in_reply_to") != null) parents.add(str(m, "in_reply_to"));
    if (recordCounterReply(mid, sender, subject, body, parents, truck, now)) return;
    var matches = new HashSet<Long>();
    var tag = CASE_REFERENCE.matcher(subject);
    while (tag.find()) matches.add(Long.parseLong(tag.group(1)));
    var linked = new HashSet<Long>();
    for (var row :
        db.queryForList(
            """
            SELECT t.request_id,d.response FROM tender t JOIN deliveries d
              ON d.action IN (COALESCE(t.bid_action,'bid:'||t.request_id||':'||t.email),
                COALESCE(t.bid_action,'bid:'||t.request_id||':'||t.email)||':transit')
            WHERE t.email=? AND d.response IS NOT NULL
            """,
            sender)) {
      var receipt = read(row.get("response"), Map.class);
      if (parents.contains(String.valueOf(receipt.get("id")))) linked.add(id(row.get("request_id")));
    }
    matches.addAll(linked);
    if (matches.size() != 1) return;
    long request = matches.iterator().next();
    var parsed = parseCarrierBid(request, sender, body, truck, catalog);
    var bid = parsed.terms();
    var price = bid.map(b -> b.euros().toPlainString()).orElse(null);
    int updated =
        db.update(
            """
            UPDATE tender SET reply_id=?,euros=?,days=?,issue=? WHERE request_id=? AND email=? AND
            EXISTS (SELECT 1 FROM deliveries WHERE
              action=COALESCE(tender.bid_action,'bid:'||tender.request_id||':'||tender.email))
            AND (? OR COALESCE(bid_action,'bid:'||request_id||':'||email)='bid:'||request_id||':'||email)
            """,
            mid,
            price,
            bid.map(MailParser.Bid::days).orElse(null),
            parsed.issue(),
            request,
            sender,
            linked.contains(request));
    if (updated > 0)
      db.update(
          "UPDATE mail SET root=?,stage=? WHERE id=?",
          request,
          bid.isPresent() ? "bid received" : "bid unreadable",
          mid);
  }

  private record CarrierBid(Optional<MailParser.Bid> terms, String issue) {}

  private CarrierBid parseCarrierBid(long request, String sender, String body, boolean truck,
      Catalog catalog) {
    var bid = MailParser.bid(body, truck);
    if (bid.isEmpty()) return new CarrierBid(bid, "No unambiguous EUR total and transit");
    if (truck) {
      var zones = db.queryForList("""
          SELECT json_extract(terms,'$.pickup_zone') FROM tender
          WHERE request_id=? AND email=? AND bid_action LIKE '%:area'
          """, String.class, request, sender);
      if (!zones.isEmpty()) {
        String area = catalog.pickupAreas.get(zones.getFirst());
        if (area == null || !MailParser.confirmsPickupArea(body, area))
          return new CarrierBid(Optional.empty(), "Carrier did not confirm citywide coverage");
      }
    }
    return new CarrierBid(bid, null);
  }

  void recheckOpenTruckBids(Instant receivedAfter, Catalog catalog) {
    var candidates = db.queryForList("""
        SELECT t.request_id,t.email,t.reply_id,f.body,p.verified_days
        FROM tender t JOIN mail r ON r.id=t.request_id JOIN wave_case w ON w.id=r.id
          JOIN mail f ON f.id=t.reply_id AND f.root=r.id AND f.sender=t.email
          LEFT JOIN truck_bid_parse p ON p.reply_id=t.reply_id
        WHERE w.operator=0 AND t.leg='trucker'
          AND f.kind='carrier' AND f.stage IN ('bid received','bid unreadable')
          AND r.stage IN ('new','collecting','waiting for pickup','waiting for air price','waiting for customer')
          AND julianday(r.received)>julianday(?)
          AND NOT EXISTS (SELECT 1 FROM deliveries WHERE action='quote:'||r.id)
        """, receivedAfter.toString());
    for (var row : candidates) {
      long request = id(row.get("request_id"));
      String sender = str(row, "email");
      var parsed = parseCarrierBid(request, sender, str(row, "body"), true, catalog);
      var bid = parsed.terms().orElse(null);
      String euros = bid == null ? null : bid.euros().toPlainString();
      Number storedDays = (Number) row.get("verified_days");
      Double days = bid == null ? null : bid.days();
      if (bid != null && days == null && storedDays != null) days = storedDays.doubleValue();
      Double transit = days;
      tx.executeWithoutResult(t -> {
        int updated = db.update("""
            UPDATE tender SET euros=?,days=?,issue=? WHERE request_id=? AND email=? AND reply_id=?
              AND (euros IS NOT ? OR days IS NOT ? OR issue IS NOT ?)
            """, euros, transit, parsed.issue(), request, sender, row.get("reply_id"),
            euros, transit, parsed.issue());
        if (updated > 0)
          db.update("UPDATE mail SET stage=? WHERE id=?",
              bid == null ? "bid unreadable" : "bid received", row.get("reply_id"));
      });
    }
  }

  private boolean recordCounterReply(
      long mid,
      String sender,
      String subject,
      String body,
      Set<String> parents,
      boolean truck,
      Instant now) {
    var reference = COUNTER_REFERENCE.matcher(subject);
    boolean tagged = reference.find();
    Long referenced = tagged ? Long.valueOf(reference.group(1)) : null;
    var matches =
        db
            .queryForList(
                """
                SELECT t.request_id, t.reply_id, t.euros, t.days, d.response, d.payload, d.attempted_at
                FROM tender t JOIN deliveries d ON d.action='counter:'||t.request_id
                WHERE t.email=? AND json_extract(d.payload, '$.to')=t.email
                """,
                sender)
            .stream()
            .filter(
                row -> {
                  var receipt = read(row.get("response"), Map.class);
                  return (receipt != null && parents.contains(str(receipt, "id")))
                      || Objects.equals(referenced, id(row.get("request_id")));
                })
            .toList();
    if (matches.isEmpty()) return tagged;
    if (matches.size() != 1) return true;

    var counter = matches.getFirst();
    long request = id(counter.get("request_id"));
    long originalReply = id(read(counter.get("payload"), Map.class).get("in_reply_to"));
    boolean open =
        now.isBefore(instant(counter.get("attempted_at")).plusSeconds(COUNTER_WAIT_SECONDS))
            && delivery("quote:" + request) == null
            && !counterAnswered(request);
    String stage = "counter closed";
    if (open && counter.get("euros") != null && originalReply == id(counter.get("reply_id"))) {
      String text =
          body.split("(?im)^\\s*(?:>|On .+ wrote:|Am .+ schrieb|-----Original Message-----)", 2)[0]
              .strip();
      var bid =
          REVISED_RATE.matcher(text).matches()
              ? MailParser.bid(text, truck)
              : Optional.<MailParser.Bid>empty();
      var previousPrice = new BigDecimal(counter.get("euros").toString());
      var previousDays = (Number) counter.get("days");
      if (bid.isPresent()
          && bid.get().euros().compareTo(previousPrice) < 0
          && (previousDays == null
              || (bid.get().days() != null && bid.get().days() <= previousDays.doubleValue()))) {
        db.update(
            "UPDATE tender SET reply_id=?,euros=?,days=?,issue=NULL WHERE request_id=? AND email=?",
            mid,
            bid.get().euros().toPlainString(),
            bid.get().days(),
            request,
            sender);
        stage = "counter accepted";
      }
    }
    db.update("UPDATE mail SET root=?,stage=? WHERE id=?", request, stage, mid);
    return true;
  }

  record Outgoing(
      String action,
      String kind,
      String state,
      Object messageId,
      String recipient,
      String subject,
      String body,
      Instant time) {}

  private Map<Long, List<Outgoing>> outgoing(Map<Long, Long> parents) {
    Map<Long, List<Outgoing>> result = new HashMap<>();
    for (var row : db.queryForList("SELECT * FROM deliveries ORDER BY rowid")) {
      String action = str(row, "action");
      String[] parts = action.split(":", 3);
      if (parts.length < 2 || !Set.of("quote", "bid", "counter", "reply", "airport-delivery").contains(parts[0]))
        continue;
      long owner = Long.parseLong(parts[1]);
      Map<?, ?> receipt = read(row.get("response"), Map.class);
      Map<?, ?> payload = read(row.get("payload"), Map.class);
      // Older receipts may include the message. Never reconstruct a sent body from a draft.
      if (payload == null) payload = receipt == null ? Map.of() : receipt;
      Object messageId = receipt == null ? null : receipt.get("id");
      if (messageId != null) parents.put(id(messageId), owner);
      String recipient = str(payload, "to");
      if (recipient.isEmpty() && parts.length == 3) recipient = parts[2];
      Instant time =
          receipt == null ? null : instant(str(receipt, "sent_at", "created_at", "received_at"));
      if (time == null) time = instant(row.get("attempted_at"));
      var item =
          new Outgoing(
              action,
              "airport-delivery".equals(parts[0]) ? "reply" : parts[0],
              str(row, "state"),
              messageId,
              recipient,
              str(payload, "subject"),
              (String) payload.get("body"),
              time);
      result.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(item);
    }
    return result;
  }

  private static long threadId(long id, Map<Long, Long> parents) {
    var seen = new HashSet<Long>();
    while (parents.containsKey(id) && seen.add(id)) id = parents.get(id);
    return id;
  }

  List<Map<String, Object>> inbox() {
    var rows =
        db.queryForList(
            "SELECT m.*,s.result AS board_result,COALESCE(w.reason,CASE WHEN m.booking IS NULL THEN b.issue END) AS"
                + " review_reason FROM mail m LEFT JOIN wave_case w ON w.id=m.id LEFT JOIN"
                + " booking_attempt b ON b.request_id=m.id LEFT JOIN board_result s"
                + " ON m.kind='request' AND s.received=m.received ORDER BY m.id DESC");
    Map<Long, Long> parents = new HashMap<>();
    for (var row : rows) {
      long mid = id(row.get("id"));
      if (row.get("root") != null && mid != id(row.get("root")))
        parents.put(mid, id(row.get("root")));
    }
    var outgoing = outgoing(parents);
    for (var row : rows) {
      long mid = id(row.get("id"));
      for (String k : List.of("fields", "attachments", "proposal", "booking", "board_result"))
        row.put(k, read(row.get(k), Object.class));
      row.put("thread_id", threadId(mid, parents));
      row.put("outgoing", outgoing.getOrDefault(mid, List.of()));
      row.put("delivery", delivery("quote:" + mid));
      row.put("reply_delivery", delivery("reply:" + mid));
      var bids = new LinkedHashMap<String, String>();
      for (var t : tenders(mid)) {
        var email = str(t, "email");
        bids.put(
            email,
            t.get("reply_id") != null
                ? t.get("euros") != null ? "priced" : "unreadable"
                : delivery(bidAction(t)));
      }
      row.put("bid_requests", bids);
    }
    return rows;
  }
}
