package ai.aeiou.forwarder.api.query;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Turns JSONB text into plain Java values (Map/List/String/Number/Boolean) for the response DTOs. */
@Component
public class JsonColumns {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private final JsonMapper mapper;

    public JsonColumns(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public Object value(String json) {
        return json == null ? null : mapper.readValue(json, Object.class);
    }

    public Map<String, Object> object(String json) {
        return json == null ? null : mapper.readValue(json, MAP);
    }

    /** Keeps only numeric entries, e.g. board points per kind. */
    public Map<String, Double> numbers(String json) {
        Map<String, Object> raw = object(json);
        if (raw == null) return null;
        Map<String, Double> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v instanceof Number n) out.put(k, n.doubleValue());
        });
        return out;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }
}
