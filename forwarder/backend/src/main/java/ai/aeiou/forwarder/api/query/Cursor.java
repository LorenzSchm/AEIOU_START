package ai.aeiou.forwarder.api.query;

import ai.aeiou.forwarder.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Opaque keyset cursor: base64url of {@code "<sortKey>:<id>"}. The sort key is either a timestamp in epoch
 * microseconds (Postgres precision, so the row comparison is exact) or a plain number.
 */
public record Cursor(long sortKey, long id) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public static String of(OffsetDateTime at, long id) {
        Instant i = at.toInstant();
        return new Cursor(ChronoUnit.MICROS.between(Instant.EPOCH, i), id).encode();
    }

    public static String of(long id) {
        return new Cursor(id, id).encode();
    }

    /** Returns null for a null/blank cursor (first page); a malformed cursor is a 400. */
    public static Cursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            int sep = raw.indexOf(':');
            return new Cursor(Long.parseLong(raw.substring(0, sep)), Long.parseLong(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid cursor");
        }
    }

    public static int clampLimit(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        if (limit < 1) throw ApiException.badRequest("limit must be >= 1");
        return Math.min(limit, MAX_LIMIT);
    }

    public OffsetDateTime sortTime() {
        return Instant.EPOCH.plus(sortKey, ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((sortKey + ":" + id).getBytes(StandardCharsets.US_ASCII));
    }
}
