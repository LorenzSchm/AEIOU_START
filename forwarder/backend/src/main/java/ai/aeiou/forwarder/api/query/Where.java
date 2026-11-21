package ai.aeiou.forwarder.api.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Collects optional filter clauses and their named parameters for one explicit SQL statement. */
final class Where {

    private final List<String> clauses = new ArrayList<>();
    private final Map<String, Object> params = new HashMap<>();

    Where and(String clause) {
        clauses.add(clause);
        return this;
    }

    Where param(String name, Object value) {
        params.put(name, value);
        return this;
    }

    /** Keyset condition for a {@code (time, id) DESC} order. */
    Where before(String timeCol, String idCol, Cursor cursor) {
        if (cursor == null) return this;
        return and("(" + timeCol + ", " + idCol + ") < (:cursorAt, :cursorId)")
                .param("cursorAt", cursor.sortTime())
                .param("cursorId", cursor.id());
    }

    /** Keyset condition for an {@code id DESC} order. */
    Where idBefore(String idCol, Cursor cursor) {
        if (cursor == null) return this;
        return and(idCol + " < :cursorId").param("cursorId", cursor.id());
    }

    /** Keyset condition for an {@code id ASC} order. */
    Where idAfter(String idCol, Cursor cursor) {
        if (cursor == null) return this;
        return and(idCol + " > :cursorId").param("cursorId", cursor.id());
    }

    String sql() {
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    Map<String, Object> params() {
        return params;
    }
}
