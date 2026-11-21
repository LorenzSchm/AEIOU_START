package ai.aeiou.forwarder.api.query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/** Null-safe column readers shared by the read models. */
final class Rows {

    private Rows() {}

    static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static Double doubleOrNull(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    static OffsetDateTime time(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, OffsetDateTime.class);
    }

    static LocalDate date(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    /** Money on the wire: decimal string with exactly 2 places, e.g. {@code "1387.35"}. */
    static String money(ResultSet rs, String col) throws SQLException {
        return money(rs.getBigDecimal(col));
    }

    static String money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Rates, weights and USD costs keep their precision but show at least 2 places. */
    static String decimal(ResultSet rs, String col) throws SQLException {
        return decimal(rs.getBigDecimal(col));
    }

    static String decimal(BigDecimal v) {
        if (v == null) return null;
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 2 ? s.setScale(2, RoundingMode.UNNECESSARY) : s).toPlainString();
    }

    static List<Long> longs(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        if (a == null) return List.of();
        return Arrays.stream((Long[]) a.getArray()).toList();
    }

    static List<String> strings(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        if (a == null) return List.of();
        return List.of((String[]) a.getArray());
    }

    /** Escapes LIKE wildcards and wraps the term for a contains-match. */
    static String containsPattern(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
