package ai.aeiou.forwarder.writer;

import static org.assertj.core.api.Assertions.assertThat;

import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import ai.aeiou.forwarder.writer.EmailWriter.Purpose;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MailValidatorTest {

    private static MailIntent quote() {
        return new MailIntent(1L, Purpose.QUOTE, List.of(), Map.of(Facts.TOTAL, "1,387.35", Facts.CURRENCY, "EUR"),
                List.of("EUR 1,387.35"), "en", "Mark", null);
    }

    @Test
    void acceptsTheOneTotalInAnyNotation() {
        String body = "Hi Mark,\n\nHelix can do it for EUR 1,387.35 all-in (that is 1.387,35 € in German notation).\n\nBest regards,";
        assertThat(MailValidator.validate(quote(), "", body, false)).isEmpty();
    }

    @Test
    void rejectsMissingLiteralSecondAmountAndPlaceholder() {
        String body = "Hi [Name],\n\nit is EUR 1,387.00, or USD 1,500 with the faster option.\n\nBest regards,";
        List<String> errors = MailValidator.validate(quote(), "", body, false);
        assertThat(errors).anyMatch(e -> e.contains("\"EUR 1,387.35\" must appear"))
                .anyMatch(e -> e.contains("placeholder"))
                .anyMatch(e -> e.contains("EUR 1,387.00"))
                .anyMatch(e -> e.contains("USD 1,500"));
    }

    @Test
    void rejectsWrongCurrencyForTheRightNumber() {
        String body = "Hi Mark, the price is EUR 1,387.35, which is 1,387.35 USD.";
        assertThat(MailValidator.validate(quote(), "", body, false)).anyMatch(e -> e.contains("not USD"));
    }

    @Test
    void rejectsAiTellsAndMissingSubjectOnFreshMail() {
        MailIntent bid = new MailIntent(1L, Purpose.BID_REQUEST, List.of(), Map.of(), List.of(), "en", null, null);
        List<String> errors = MailValidator.validate(bid, null, "I hope this email finds you well. Please don't hesitate to call.", true);
        assertThat(errors).anyMatch(e -> e.contains("subject line is required"))
                .anyMatch(e -> e.contains("hope this email finds you"))
                .anyMatch(e -> e.contains("don't hesitate"));
    }

    @Test
    void ignoresWeightsAndDatesWhenCheckingAmounts() {
        String body = "Your 320 kg ship on 24.11.2026 for EUR 1,387.35 all-in, 3 days.";
        assertThat(MailValidator.validate(quote(), "", body, false)).isEmpty();
    }

    @Test
    void moneyParsesBothNotations() {
        assertThat(Money.parse("1,387.35")).contains(new BigDecimal("1387.35"));
        assertThat(Money.parse("1.387,35")).contains(new BigDecimal("1387.35"));
        assertThat(Money.parse("1,387")).contains(new BigDecimal("1387"));
        assertThat(Money.parse("12.5")).contains(new BigDecimal("12.5"));
        assertThat(Money.format(new BigDecimal("1387.351"), "eur")).isEqualTo("EUR 1,387.35");
    }
}
