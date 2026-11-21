package ai.aeiou.forwarder.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ModelPricingTest {

    @Test
    void costFollowsThePerMillionTokenPrices() {
        // gpt-4o-mini: $0.15 in, $0.60 out per 1M tokens
        assertThat(ModelPricing.costUsd("gpt-4o-mini", 1_000, 200)).isCloseTo(0.00027, within(1e-9));
    }

    @Test
    void datedModelIdsMatchTheLongestPrefix() {
        assertThat(ModelPricing.priceOf("gpt-4o-mini-2024-07-18")).isEqualTo(ModelPricing.priceOf("gpt-4o-mini"));
        assertThat(ModelPricing.priceOf("gpt-4o-2024-08-06").inputPerMTok()).isEqualTo(2.50);
    }

    @Test
    void unknownModelsAreBilledConservatively() {
        assertThat(ModelPricing.priceOf("some-new-model")).isEqualTo(ModelPricing.UNKNOWN_MODEL);
        assertThat(ModelPricing.priceOf(null)).isEqualTo(ModelPricing.UNKNOWN_MODEL);
    }
}
