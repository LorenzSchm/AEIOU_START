package ai.aeiou.forwarder.booking;

import java.util.LinkedHashMap;
import java.util.Map;

/** Event payloads from key/value pairs; unlike {@link Map#of} it tolerates (and drops) null values. */
public final class Payloads {

    private Payloads() {}

    public static Map<String, Object> of(Object... keyValues) {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("key/value pairs expected");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
