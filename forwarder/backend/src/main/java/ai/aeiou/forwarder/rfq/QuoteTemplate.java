package ai.aeiou.forwarder.rfq;

import java.util.Map;

/**
 * Last-resort quote body, used only when the written mail does not state the total exactly once. Plain, short and
 * correct beats polished and wrong: a quote with two prices is not "one all-in total".
 */
final class QuoteTemplate {

    private QuoteTemplate() {}

    static String render(Map<String, Object> facts, String totalLabel, String recipientName) {
        String greeting = recipientName == null || recipientName.isBlank() ? "Hello," : "Hi " + recipientName.trim() + ",";
        boolean door = Boolean.TRUE.equals(facts.get("doorPickup"));
        StringBuilder b = new StringBuilder()
                .append(greeting).append("\n\n")
                .append("thanks for your request. For ").append(facts.get("chargeableKg")).append(" kg chargeable on ")
                .append(facts.get("route")).append(door ? ", including pickup at your door," : "")
                .append(" we can offer ").append(facts.get("airline"));
        if (facts.get("transitDays") != null) b.append(" with ").append(facts.get("transitDays")).append(" days in transit");
        b.append(".\n\n")
                .append("All-in total: ").append(totalLabel).append(".\n\n");
        if (facts.get("validity") != null) b.append("The offer is valid until ").append(facts.get("validity")).append(". ");
        b.append("Just reply to this mail and we will book it right away.\n\n")
                .append("Best regards,\nAEIOU Air Freight desk");
        return b.toString();
    }
}
