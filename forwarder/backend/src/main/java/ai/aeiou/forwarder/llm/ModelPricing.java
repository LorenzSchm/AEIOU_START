package ai.aeiou.forwarder.llm;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * USD list prices per million tokens, used to compute the cost of each gateway call.
 * Dated model ids ({@code gpt-4o-mini-2024-07-18}) match by their longest known prefix;
 * unknown models are billed at a conservative (high) default so we never under-report cost.
 */
public final class ModelPricing {

    public record Price(double inputPerMTok, double outputPerMTok) {
        double cost(int promptTokens, int completionTokens) {
            return (promptTokens * inputPerMTok + completionTokens * outputPerMTok) / 1_000_000d;
        }
    }

    static final Price UNKNOWN_MODEL = new Price(2.50, 10.00);

    private static final Map<String, Price> PRICES = Map.of(
            "gpt-4o-mini", new Price(0.15, 0.60),
            "gpt-4o", new Price(2.50, 10.00),
            "gpt-4.1-mini", new Price(0.40, 1.60),
            "gpt-4.1-nano", new Price(0.10, 0.40),
            "gpt-5-mini", new Price(0.25, 2.00),
            "gpt-5-nano", new Price(0.05, 0.40));

    /** Longest prefix first, so "gpt-4o-mini" wins over "gpt-4o". */
    private static final List<String> PREFIXES = PRICES.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    private ModelPricing() {}

    public static Price priceOf(String model) {
        if (model == null) return UNKNOWN_MODEL;
        String m = model.toLowerCase(Locale.ROOT);
        return PREFIXES.stream().filter(m::startsWith).findFirst().map(PRICES::get).orElse(UNKNOWN_MODEL);
    }

    public static double costUsd(String model, int promptTokens, int completionTokens) {
        return priceOf(model).cost(promptTokens, completionTokens);
    }
}
