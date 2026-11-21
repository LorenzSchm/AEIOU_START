package ai.aeiou.forwarder.llm;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives a JSON schema for OpenAI structured outputs ({@code strict: true}) from a Java record.
 *
 * <p>Strict mode requires every property to be listed in {@code required} and
 * {@code additionalProperties: false} on every object. Optional values are therefore expressed as
 * nullable types: reference-typed components (String, boxed numbers, enums, lists, nested records)
 * accept {@code null}; primitive components ({@code double}, {@code int}, {@code boolean}) do not.
 *
 * <p>Supported: records, nested records, enums, String, numbers, booleans, {@code java.time} values
 * (as strings) and {@code List}/{@code Collection} of those. Maps and recursive records are rejected,
 * because strict mode cannot express them without {@code $defs}.
 */
public final class JsonSchemaGenerator {

    private JsonSchemaGenerator() {}

    /** Schema of the root object; the root itself is never nullable. */
    public static Map<String, Object> forRecord(Class<?> type) {
        if (!type.isRecord()) throw new IllegalArgumentException(type.getName() + " is not a record");
        return objectSchema(type, new HashSet<>());
    }

    private static Map<String, Object> schema(Type type, boolean nullable, Set<Class<?>> visiting) {
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw
                && Collection.class.isAssignableFrom(raw)) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", nullable ? List.of("array", "null") : "array");
            s.put("items", schema(p.getActualTypeArguments()[0], false, visiting));
            return s;
        }
        if (!(type instanceof Class<?> c)) throw new IllegalArgumentException("Unsupported type " + type);

        if (c.isPrimitive()) return Map.of("type", primitiveType(c));
        if (c.isEnum()) {
            List<String> values = new ArrayList<>();
            for (Object constant : c.getEnumConstants()) values.add(((Enum<?>) constant).name());
            Map<String, Object> s = Map.of("type", "string", "enum", values);
            return nullable ? anyOfNull(s) : s;
        }
        if (c.isRecord()) {
            Map<String, Object> s = objectSchema(c, visiting);
            return nullable ? anyOfNull(s) : s;
        }
        String scalar = scalarType(c);
        if (scalar == null) throw new IllegalArgumentException("Unsupported type " + c.getName());
        return Map.of("type", nullable ? List.of(scalar, "null") : scalar);
    }

    private static Map<String, Object> objectSchema(Class<?> record, Set<Class<?>> visiting) {
        if (!visiting.add(record)) throw new IllegalArgumentException("Recursive record " + record.getName());
        Map<String, Object> properties = new LinkedHashMap<>();
        for (RecordComponent rc : record.getRecordComponents()) {
            properties.put(rc.getName(), schema(rc.getGenericType(), !rc.getType().isPrimitive(), visiting));
        }
        visiting.remove(record);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", List.copyOf(properties.keySet()));
        s.put("additionalProperties", false);
        return s;
    }

    private static Map<String, Object> anyOfNull(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }

    private static String primitiveType(Class<?> c) {
        if (c == boolean.class) return "boolean";
        if (c == double.class || c == float.class) return "number";
        if (c == char.class) return "string";
        return "integer";
    }

    private static String scalarType(Class<?> c) {
        if (c == String.class || CharSequence.class.isAssignableFrom(c) || Temporal.class.isAssignableFrom(c)) return "string";
        if (c == Boolean.class) return "boolean";
        if (c == Integer.class || c == Long.class || c == Short.class || c == Byte.class || c == BigInteger.class) return "integer";
        if (Number.class.isAssignableFrom(c) || c == BigDecimal.class) return "number";
        return null;
    }
}
