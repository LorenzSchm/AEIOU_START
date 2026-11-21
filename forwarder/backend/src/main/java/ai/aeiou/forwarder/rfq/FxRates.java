package ai.aeiou.forwarder.rfq;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * EUR conversion for comparing candidates. The portal states {@code fx_eur_per_usd} with every non-EUR price;
 * the latest one wins. Until then (or for bids) the {@code fxEurPerUsd} setting is used.
 */
@Component
public class FxRates {

    private final RfqSettings settings;
    private final Map<String, BigDecimal> eurPerUnit = new ConcurrentHashMap<>();

    public FxRates(RfqSettings settings) {
        this.settings = settings;
    }

    public void learn(String currency, BigDecimal eurPerUnitRate) {
        if (currency != null && eurPerUnitRate != null && eurPerUnitRate.signum() > 0) {
            eurPerUnit.put(currency.toUpperCase(), eurPerUnitRate);
        }
    }

    /** Amount in EUR, or empty for a currency we have no rate for. */
    public Optional<BigDecimal> toEur(BigDecimal amount, String currency) {
        if (amount == null) return Optional.empty();
        String cur = normalise(currency);
        if (cur.equals("EUR")) return Optional.of(amount);
        BigDecimal rate = eurPerUnit.get(cur);
        if (rate == null && cur.equals("USD")) rate = settings.fallbackEurPerUsd();
        return rate == null ? Optional.empty() : Optional.of(amount.multiply(rate).setScale(2, RoundingMode.HALF_UP));
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
