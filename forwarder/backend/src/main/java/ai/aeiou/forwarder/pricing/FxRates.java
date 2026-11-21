package ai.aeiou.forwarder.pricing;

import ai.aeiou.forwarder.settings.SettingsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one EUR-per-USD rate used to compare prices (sheets, portal rates, bids). The portal states
 * {@code fx_eur_per_usd} with every USD rate and the latest one wins; until then the runtime setting
 * {@code fxEurPerUsd} applies, which defaults to {@code app.pricing.default-fx-eur-per-usd}.
 */
@Component
public class FxRates {

    public static final String SETTING = "fxEurPerUsd";

    private final Supplier<SettingsService> settings;
    private final BigDecimal defaultEurPerUsd;
    private volatile BigDecimal portalEurPerUsd;

    @Autowired
    public FxRates(ObjectProvider<SettingsService> settings,
                   @Value("${app.pricing.default-fx-eur-per-usd:0.86}") BigDecimal defaultEurPerUsd) {
        this.settings = settings::getIfAvailable;
        this.defaultEurPerUsd = defaultEurPerUsd;
    }

    /** For tests and wiring without a Spring context. */
    public FxRates(SettingsService settings, BigDecimal defaultEurPerUsd) {
        this.settings = () -> settings;
        this.defaultEurPerUsd = defaultEurPerUsd;
    }

    /** EUR per USD: the portal's latest stated rate, else the setting, else the configured default. */
    public BigDecimal eurPerUsd() {
        BigDecimal stated = portalEurPerUsd;
        if (stated != null) return stated;
        SettingsService s = settings.get();
        return s == null ? defaultEurPerUsd : BigDecimal.valueOf(s.getDouble(SETTING, defaultEurPerUsd.doubleValue()));
    }

    /** Records the {@code fx_eur_per_usd} the portal stated with a rate in {@code currency}; only USD rates count. */
    public void learn(String currency, BigDecimal eurPerUnit) {
        if ("USD".equals(normalise(currency)) && eurPerUnit != null && eurPerUnit.signum() > 0) {
            portalEurPerUsd = eurPerUnit;
        }
    }

    /** Amount in EUR, or empty for a currency we have no rate for. */
    public Optional<BigDecimal> toEur(BigDecimal amount, String currency) {
        if (amount == null) return Optional.empty();
        return switch (normalise(currency)) {
            case "EUR" -> Optional.of(amount);
            case "USD" -> Optional.of(amount.multiply(eurPerUsd()).setScale(2, RoundingMode.HALF_UP));
            default -> Optional.empty();
        };
    }

    /** ISO code for loose currency spellings in bids ("€", "$", "eur"). */
    public static String normalise(String currency) {
        if (currency == null || currency.isBlank()) return "EUR";
        String c = currency.trim().toUpperCase();
        return switch (c) {
            case "€", "EURO", "EUROS" -> "EUR";
            case "$", "US$", "USD$", "DOLLAR", "DOLLARS" -> "USD";
            default -> c;
        };
    }
}
