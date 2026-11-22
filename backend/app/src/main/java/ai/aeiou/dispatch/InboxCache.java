package ai.aeiou.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The whole inbox, built once per change and shared by the event stream and the desk's read
 * endpoints (messages, summary, queue, shipments, thread).
 *
 * <p>{@link Ledger#inbox()} reads and assembles every message (~80 ms for ~1,000). Every caller used
 * to build its own copy; a live desk refetching its Queue and Shipments pages after each change made
 * that several builds per change. The cache asks {@link LedgerWatch} whether the inbox tables changed
 * and rebuilds only then, so it is always as fresh as building on demand.
 *
 * <p>A snapshot is shared: callers must not modify its messages.
 */
@Component
class InboxCache {
  private static final Logger log = LoggerFactory.getLogger(InboxCache.class);

  /** One built inbox: the messages, the same as a JSON tree, and the content fingerprint it was built at. */
  record Snapshot(long build, List<Map<String, Object>> messages, JsonNode tree) {}

  private final Ledger ledger;
  private final ObjectMapper json;
  private final LedgerWatch watch;
  private Snapshot current;
  private OptionalLong builtAt = OptionalLong.empty();
  private long builds;

  @org.springframework.beans.factory.annotation.Autowired
  InboxCache(Ledger ledger, ObjectMapper json) {
    this(ledger, json, LedgerWatch.of(ledger));
  }

  InboxCache(Ledger ledger, ObjectMapper json, LedgerWatch watch) {
    this.ledger = ledger;
    this.json = json;
    this.watch = watch;
  }

  /** The current inbox: the cached snapshot when the inbox tables are unchanged, else a fresh build. */
  synchronized Snapshot current() {
    var fingerprint = watch.fingerprint();
    if (current != null && fingerprint.isPresent() && fingerprint.equals(builtAt)) return current;
    long started = System.nanoTime();
    var messages = Collections.unmodifiableList(new ArrayList<>(ledger.inbox()));
    current = new Snapshot(++builds, messages, json.valueToTree(messages));
    builtAt = fingerprint;
    log.debug("Inbox rebuilt ({} messages) in {} ms", messages.size(), (System.nanoTime() - started) / 1_000_000);
    return current;
  }

  /** How many times the inbox was built. */
  synchronized long builds() {
    return builds;
  }

  /** How many content fingerprints were computed (one per commit seen). */
  long fingerprints() {
    return watch.fingerprints();
  }

  @PreDestroy
  void stop() {
    watch.close();
  }
}
