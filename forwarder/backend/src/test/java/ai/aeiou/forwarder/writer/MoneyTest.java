package ai.aeiou.forwarder.writer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void formatsWithGroupingAndTwoDecimals() {
        assertThat(Money.format(new BigDecimal("1387.345"), "EUR")).isEqualTo("EUR 1,387.35");
        assertThat(Money.amount(new BigDecimal("980"))).isEqualTo("980.00");
    }

    @Test
    void countsAmountsInAnySpelling() {
        assertThat(Money.countAmounts("EUR 1,387.35 or 1.387,35 € or $980 or 980 USD")).isEqualTo(4);
        assertThat(Money.countAmounts("250 kg, 1.2 cbm, ready 2026-11-23, 4 days")).isZero();
    }

    @Test
    void detectsExactlyOneTotal() {
        String label = "EUR 1,387.35";
        assertThat(Money.statesExactlyOneTotal("All-in: EUR 1,387.35 for 250 kg.", label)).isTrue();
        assertThat(Money.statesExactlyOneTotal("EUR 1,387.35, or EUR 1,200.00 if slower.", label)).isFalse();
        assertThat(Money.statesExactlyOneTotal("EUR 1,387.35 ... again EUR 1,387.35", label)).isFalse();
        assertThat(Money.statesExactlyOneTotal("1,387.35 euros all in", label)).isFalse();
        assertThat(Money.statesExactlyOneTotal(null, label)).isFalse();
    }
}
