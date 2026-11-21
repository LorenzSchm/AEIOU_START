package ai.aeiou.forwarder.settings;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link SettingsService} backed by the {@code setting} table. All rows are cached in memory on first
 * use (reads happen on hot paths such as the router); writes go to the DB first, then to the cache,
 * and leave a {@code setting.changed} event in the global feed.
 */
@Service
public class JdbcSettingsService implements SettingsService {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final EventLog events;
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();
    private final ReentrantLock loadLock = new ReentrantLock(); // no pinning of virtual threads
    private volatile boolean loaded;

    public JdbcSettingsService(JdbcClient jdbc, JsonMapper json, EventLog events) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
    }

    @Override
    public int getInt(String key, int defaultValue) {
        return read(key, defaultValue, n -> n.isNumber() ? n.asInt() : Integer.parseInt(n.asString().trim()));
    }

    @Override
    public double getDouble(String key, double defaultValue) {
        return read(key, defaultValue, n -> n.isNumber() ? n.asDouble() : Double.parseDouble(n.asString().trim()));
    }

    @Override
    public boolean getBool(String key, boolean defaultValue) {
        return read(key, defaultValue, n -> n.isBoolean() ? n.asBoolean() : Boolean.parseBoolean(n.asString().trim()));
    }

    @Override
    public String getString(String key, String defaultValue) {
        return read(key, defaultValue, n -> n.isValueNode() ? n.asString() : n.toString());
    }

    @Override
    @Transactional
    public void set(String key, Object value, String updatedBy) {
        JsonNode node = json.valueToTree(value);
        jdbc.sql("""
                        INSERT INTO setting (key, value, updated_at, updated_by)
                        VALUES (:key, CAST(:value AS jsonb), now(), :by)
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), updated_by = EXCLUDED.updated_by""")
                .param("key", key)
                .param("value", json.writeValueAsString(node))
                .param("by", updatedBy)
                .update();
        JsonNode previous = cache.put(key, node);
        Actor actor = updatedBy == null || updatedBy.equals("system") ? Actor.system : Actor.human;
        events.append(null, "setting.changed", actor, "Setting " + key + " = " + node,
                Map.of("key", key, "value", node, "previous", previous == null ? json.nullNode() : previous,
                        "updatedBy", updatedBy == null ? "system" : updatedBy));
    }

    /** Drops the cache; the next read reloads every row. */
    public void reload() {
        loaded = false;
    }

    private <T> T read(String key, T defaultValue, Function<JsonNode, T> convert) {
        ensureLoaded();
        JsonNode node = cache.get(key);
        if (node == null || node.isNull()) return defaultValue;
        try {
            return convert.apply(node);
        } catch (RuntimeException e) {
            return defaultValue; // a malformed value must never break a hot path
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loadLock.lock();
        try {
            if (loaded) return;
            cache.clear();
            jdbc.sql("SELECT key, value::text AS value FROM setting")
                    .query((rs, i) -> Map.entry(rs.getString("key"), json.readTree(rs.getString("value"))))
                    .list()
                    .forEach(e -> cache.put(e.getKey(), e.getValue()));
            loaded = true;
        } finally {
            loadLock.unlock();
        }
    }
}
