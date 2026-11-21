package ai.aeiou.forwarder.settings;

/**
 * Runtime-tunable settings stored in the {@code setting} table (cached in memory, editable from the UI).
 * Known keys: bidWaitSeconds (90), routerConfidenceThreshold (0.75), autopilot (false),
 * quoteDeadlineSeconds (300), portalEnabled (true), bidsEnabled (true), pollingEnabled (true),
 * llmModelRouter (app.llm.model-router), quoteValidityDays (7),
 * fxEurPerUsd (app.pricing.default-fx-eur-per-usd, read through {@code pricing.FxRates}).
 */
public interface SettingsService {

    int getInt(String key, int defaultValue);

    double getDouble(String key, double defaultValue);

    boolean getBool(String key, boolean defaultValue);

    String getString(String key, String defaultValue);

    void set(String key, Object value, String updatedBy);
}
