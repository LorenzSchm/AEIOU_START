package ai.aeiou.forwarder.rfq;

import ai.aeiou.forwarder.writer.Facts;
import java.util.Map;

/**
 * Last-resort quote body, used only when the written mail does not state the total exactly once. Plain, short and
 * correct beats polished and wrong: a quote with two prices is not "one all-in total".
 */
final class QuoteTemplate {

    /** Quote facts beyond the writer's well-known {@link Facts} keys. */
    static final String ROUTE = "route";
    static final String DOOR_PICKUP = "doorPickup";

    private QuoteTemplate() {}

    static String render(Map<String, Object> facts, String totalLabel, String recipientName) {
        String greeting = recipientName == null || recipientName.isBlank() ? "Hello," : "Hi " + recipientName.trim() + ",";
        boolean door = Boolean.TRUE.equals(facts.get(DOOR_PICKUP));
        StringBuilder b = new StringBuilder()
                .append(greeting).append("\n\n")
                .append("thanks for your request. For ").append(facts.get(Facts.CHARGEABLE_KG)).append(" kg chargeable on ")
                .append(facts.get(ROUTE)).append(door ? ", including pickup at your door," : "")
                .append(" we can offer ").append(facts.get(Facts.AIRLINE));
        if (facts.get(Facts.TRANSIT_DAYS) != null) b.append(" with ").append(facts.get(Facts.TRANSIT_DAYS)).append(" days in transit");
        b.append(".\n\n")
                .append("All-in total: ").append(totalLabel).append(".\n\n");
        if (facts.get(Facts.VALID_UNTIL) != null) b.append("The offer is valid until ").append(facts.get(Facts.VALID_UNTIL)).append(". ");
        b.append("Just reply to this mail and we will book it right away.\n\n")
                .append("Best regards,\nAEIOU Air Freight desk");
        return b.toString();
    }
}
