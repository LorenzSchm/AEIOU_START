package ai.aeiou.dispatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares two inbox snapshots. Each snapshot maps a message id to that message's serialized JSON, so
 * any change to a message (its stage, an outgoing mail, a bid, a board verdict) shows up as a different
 * string. Pure and side-effect free, so the event stream's change detection can be tested directly.
 */
final class InboxDiff {
  private InboxDiff() {}

  record Changes(List<Long> upserted, List<Long> removed) {
    boolean isEmpty() {
      return upserted.isEmpty() && removed.isEmpty();
    }
  }

  /** Ids of messages that are new or changed in {@code next}, and ids that disappeared from it. */
  static Changes between(Map<Long, String> previous, Map<Long, String> next) {
    var upserted = new ArrayList<Long>();
    for (var entry : next.entrySet())
      if (!entry.getValue().equals(previous.get(entry.getKey()))) upserted.add(entry.getKey());
    var removed = new ArrayList<Long>();
    for (var id : previous.keySet()) if (!next.containsKey(id)) removed.add(id);
    return new Changes(upserted, removed);
  }

  /** Message id to its JSON, in the inbox's own order (newest first). */
  static Map<Long, String> index(List<Map<String, Object>> messages, java.util.function.Function<Object, String> write) {
    var index = new LinkedHashMap<Long, String>();
    for (var message : messages) index.put(Ledger.id(message.get("id")), write.apply(message));
    return index;
  }
}
