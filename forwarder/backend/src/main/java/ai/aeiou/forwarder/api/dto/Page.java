package ai.aeiou.forwarder.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;

/** Keyset page. Pass {@code nextCursor} back as {@code cursor} to get the next page; null means the end. */
public record Page<T>(List<T> items, @Schema(nullable = true) String nextCursor) {

    /**
     * Builds a page from a query that fetched {@code limit + 1} rows: the extra row only signals that more exist.
     */
    public static <T> Page<T> of(List<T> rows, int limit, Function<T, String> cursorOf) {
        if (rows.size() <= limit) return new Page<>(rows, null);
        List<T> items = rows.subList(0, limit);
        return new Page<>(List.copyOf(items), cursorOf.apply(items.getLast()));
    }
}
