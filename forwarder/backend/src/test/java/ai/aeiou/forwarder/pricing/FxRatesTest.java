package ai.aeiou.forwarder.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.aeiou.forwarder.settings.SettingsService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FxRatesTest {

    private static final BigDecimal DEFAULT = new BigDecimal("0.86");

    @Test
    void defaultThenSettingThenPortal() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getDouble(eq(FxRates.SETTING), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        FxRates fx = new FxRates(settings, DEFAULT);
        assertThat(fx.eurPerUsd()).isEqualByComparingTo("0.86");

        when(settings.getDouble(eq(FxRates.SETTING), anyDouble())).thenReturn(0.9);
        assertThat(fx.toEur(new BigDecimal("100"), "$")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("90.00"));

        fx.learn("EUR", new BigDecimal("0.5")); // only USD rates state fx_eur_per_usd
        fx.learn("USD", new BigDecimal("0.92"));
        assertThat(fx.eurPerUsd()).isEqualByComparingTo("0.92");
    }

    @Test
    void eurPassesThroughAndUnknownCurrenciesHaveNoRate() {
        FxRates fx = new FxRates((SettingsService) null, DEFAULT);
        assertThat(fx.toEur(new BigDecimal("12.34"), "eur")).contains(new BigDecimal("12.34"));
        assertThat(fx.toEur(BigDecimal.TEN, "GBP")).isEmpty();
        assertThat(fx.eurPerUsd()).isEqualByComparingTo("0.86");
    }
}
