package ai.aeiou.forwarder.api;

import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.infrastructure.Ledger;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
class Pages {
  private final Ledger store;
  private final Clock clock;

  Pages(Ledger store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  JsonNode page(String resource, Map<String, String> params, Supplier<List<JsonNode>> load) {
    var filters = new TreeMap<>(params);
    String cursor = filters.remove("cursor");
    String size = filters.remove("limit");
    int limit;
    try {
      limit = size == null ? 25 : Integer.parseInt(size);
    } catch (NumberFormatException e) {
      throw Problem.invalid("Invalid limit");
    }
    if (limit < 1 || limit > 100) throw Problem.invalid("Page size must be 1–100");
    String scope = resource + " " + M.valueToTree(filters);
    List<JsonNode> items;
    int first = 0;
    String id;
    if (cursor == null) {
      items = load.get();
      id = UUID.randomUUID().toString();
    } else {
      var parts = cursor.split("\\.", -1);
      if (parts.length != 2 || !parts[0].matches("[0-9a-f-]{36}") || !parts[1].matches("\\d{1,9}"))
        throw Problem.invalid("Malformed cursor");
      id = parts[0];
      first = Integer.parseInt(parts[1]);
      var rows =
          store.db.query(
              "select scope,expires_at,items from page_snapshot where id=?",
              (r, i) -> {
                if (!scope.equals(r.getString(1))) throw Problem.invalid("Cursor filters changed");
                if (!clock.instant().isBefore(r.getTimestamp(2).toInstant()))
                  throw new Problem(410, "CURSOR_EXPIRED", "Refetch the first page");
                return parse(r.getString(3)).valueStream().toList();
              },
              id);
      if (rows.isEmpty()) throw new Problem(410, "CURSOR_EXPIRED", "Refetch the first page");
      items = rows.getFirst();
    }
    if (first > items.size()) throw Problem.invalid("Invalid page boundary");
    int end = Math.min(items.size(), first + limit);
    if (cursor == null && end < items.size()) {
      store.db.update(
          "delete from page_snapshot where expires_at<?", java.sql.Timestamp.from(clock.instant()));
      store.db.update(
          "insert into page_snapshot values(?,?,?,?)",
          id,
          scope,
          java.sql.Timestamp.from(clock.instant().plusSeconds(900)),
          M.valueToTree(items).toString());
    }
    return obj(
        "items",
        items.subList(first, end),
        "nextCursor",
        end < items.size() ? id + "." + end : null);
  }
}
