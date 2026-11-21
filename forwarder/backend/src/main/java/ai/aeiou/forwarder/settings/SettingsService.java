package ai.aeiou.forwarder.settings;

/**
 * Runtime-tunable settings stored in the {@code setting} table (cached in memory, editable from the UI).
 * Known keys: bidWaitSeconds (90), routerConfidenceThreshold (0.75), autopilot (true),
 * quoteDeadlineSeconds (300), portalEnabled (true), bidsEnabled (true).
 */
public interface SettingsService {

    int getInt(String key, int defaultValue);

    double getDouble(String key, double defaultValue);

    boolean getBool(String key, boolean defaultValue);

    String getString(String key, String defaultValue);

    void set(String key, Object value, String updatedBy);
}
