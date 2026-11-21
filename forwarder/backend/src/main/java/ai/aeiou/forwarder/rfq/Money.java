package ai.aeiou.forwarder.rfq;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that formats quoted amounts, so the writer facts, the body check and the template agree.
 * Same format as the writer's {@code writer.Money.format(amount, currency)} ("EUR 1,387.35").
 * TODO(integration): use writer.Money/Facts once merged.
 */
public final class Money {

    /**
     * Any amount with a currency next to it: "EUR 1,387.35", "1.387,35 €", "$980", "980 USD".
     * Used to verify that a quote body states exactly one total.
     */
    private static final Pattern AMOUNT_WITH_CURRENCY = Pattern.compile(
            "(?:\\b(?:EUR|USD|GBP|CHF)|[€$£])\\s?\\d(?:[\\d.,']*\\d)?"
                    + "|\\b\\d(?:[\\d.,']*\\d)?\\s?(?:(?:EUR|USD|GBP|CHF)\\b|[€$£])");

    private Money() {}

    public static BigDecimal round(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** "1,387.35" */
    public static String format(BigDecimal amount) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(round(amount));
    }

    /** "EUR 1,387.35": the literal every quote must contain. */
    public static String label(BigDecimal amount, String currency) {
        return currency + " " + format(amount);
    }

    /** How many money amounts (number + currency) a text states. */
    public static int countAmounts(String text) {
        if (text == null) return 0;
        Matcher m = AMOUNT_WITH_CURRENCY.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** True when {@code body} states exactly one amount and it is {@code label} verbatim. */
    public static boolean statesExactlyOneTotal(String body, String label) {
        if (body == null) return false;
        int occurrences = body.split(Pattern.quote(label), -1).length - 1;
        return occurrences == 1 && countAmounts(body) == 1;
    }
}
