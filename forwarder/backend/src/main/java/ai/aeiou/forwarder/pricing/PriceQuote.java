package ai.aeiou.forwarder.pricing;

import java.math.BigDecimal;
import java.util.Map;

/** One priced option. {@code totalEur} is the comparison value; {@code total}/{@code currency} is what we quote. */
public record PriceQuote(
        String source,          // sheet | portal | bid | ratedesk | truck | combined
        String airlineCode,
        String airlineName,
        BigDecimal total,
        String currency,
        BigDecimal totalEur,
        Integer transitDays,
        String rateId,          // portal only
        Map<String, Object> breakdown) {}
