package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.CostProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how {@link CostCalculator} prices cache writes by lifetime: the 1-hour portion at 2x the
 * input rate, the remainder at the 5-minute 1.25x rate. Every expected value is hand-computed.
 */
class CostCalculatorOneHourCacheTest {

    private final CostCalculator calculator = new CostCalculator(new CostProperties());

    private static TokenUsage writes(long total, long oneHour) {
        return new TokenUsage(0, 0, total, 0, oneHour);
    }

    // Haiku: 5-minute 1.25, 1-hour 2.00 (USD/MTok == micro-dollars per token)

    @Test
    @DisplayName("Haiku, no 1-hour portion: 1000 writes at 1.25 = 1250")
    void haiku_noOneHour() {
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.HAIKU, writes(1000, 0))).isEqualTo(1250);
    }

    @Test
    @DisplayName("Haiku, partial: 600 at 2.00 + 400 at 1.25 = 1700")
    void haiku_partial() {
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.HAIKU, writes(1000, 600))).isEqualTo(1700);
    }

    @Test
    @DisplayName("Haiku, all 1-hour: 1000 at 2.00 = 2000")
    void haiku_allOneHour() {
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.HAIKU, writes(1000, 1000))).isEqualTo(2000);
    }

    @Test
    @DisplayName("Haiku batch halves each case: 625 / 850 / 1000")
    void haiku_batch() {
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.HAIKU, writes(1000, 0), true)).isEqualTo(625);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.HAIKU, writes(1000, 600), true))
                .isEqualTo(850);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.HAIKU, writes(1000, 1000), true))
                .isEqualTo(1000);
    }

    // Sonnet: 5-minute 3.75, 1-hour 6.00

    @Test
    @DisplayName("Sonnet: 3750 / 5100 (600*6 + 400*3.75) / 6000")
    void sonnet_nonBatch() {
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET, writes(1000, 0))).isEqualTo(3750);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET, writes(1000, 600))).isEqualTo(5100);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET, writes(1000, 1000))).isEqualTo(6000);
    }

    @Test
    @DisplayName("Sonnet batch: 1875 / 2550 / 3000")
    void sonnet_batch() {
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET, writes(1000, 0), true))
                .isEqualTo(1875);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET, writes(1000, 600), true))
                .isEqualTo(2550);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET, writes(1000, 1000), true))
                .isEqualTo(3000);
    }

    // Opus: 5-minute 6.25, 1-hour 10.00

    @Test
    @DisplayName("Opus: 6250 / 8500 (600*10 + 400*6.25) / 10000")
    void opus_nonBatch() {
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.OPUS, writes(1000, 0))).isEqualTo(6250);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.OPUS, writes(1000, 600))).isEqualTo(8500);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.OPUS, writes(1000, 1000))).isEqualTo(10000);
    }

    @Test
    @DisplayName("Opus batch: 3125 / 4250 / 5000")
    void opus_batch() {
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.OPUS, writes(1000, 0), true)).isEqualTo(3125);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.OPUS, writes(1000, 600), true))
                .isEqualTo(4250);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.OPUS, writes(1000, 1000), true))
                .isEqualTo(5000);
    }

    @Test
    @DisplayName("a usage with no 1-hour portion costs exactly what the four-argument usage did")
    void fourArgumentUsage_pricesAsBefore() {
        TokenUsage legacy = new TokenUsage(1000, 100, 500, 200);

        assertThat(legacy.cacheCreationOneHourTokens()).isZero();
        // Opus: 1000*5 + 100*25 + 500*6.25 + 200*0.50 = 5000 + 2500 + 3125 + 100
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.OPUS, legacy)).isEqualTo(10725);
    }

    @Test
    @DisplayName("1-hour writes add to the other token categories rather than replacing them")
    void oneHourWrites_combineWithOtherCategories() {
        TokenUsage usage = new TokenUsage(1000, 100, 500, 200, 500);

        // Haiku: 1000*1 + 100*5 + 500*2.00 + 200*0.10 = 1000 + 500 + 1000 + 20
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.HAIKU, usage)).isEqualTo(2520);
    }

    // Sonnet 5.5: 5-minute 2.50, 1-hour 4.00

    @Test
    @DisplayName("Sonnet 5.5: 2500 / 3400 (600*4 + 400*2.5) / 4000, and batch 1250 / 1700 / 2000")
    void sonnet55_oneHourPricing() {
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET_55, writes(1000, 0)))
                .isEqualTo(2500);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET_55, writes(1000, 600)))
                .isEqualTo(3400);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET_55, writes(1000, 1000)))
                .isEqualTo(4000);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET_55, writes(1000, 0), true))
                .isEqualTo(1250);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET_55, writes(1000, 600), true))
                .isEqualTo(1700);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.SONNET_55, writes(1000, 1000), true))
                .isEqualTo(2000);
    }

    @Test
    @DisplayName("a 1-hour portion larger than the total is clamped and never prices extra tokens")
    void oneHourPortion_cannotExceedTotal() {
        TokenUsage oversized = writes(1000, 5000);

        assertThat(oversized.cacheCreationOneHourTokens()).isEqualTo(1000);
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.HAIKU, oversized)).isEqualTo(2000);
    }

    @Test
    @DisplayName("a negative 1-hour portion is clamped to zero")
    void oneHourPortion_negativeClampsToZero() {
        TokenUsage negative = writes(1000, -50);

        assertThat(negative.cacheCreationOneHourTokens()).isZero();
        assertThat(calculator.calculateCostMicroDollars(EvaluationModel.HAIKU, negative)).isEqualTo(1250);
    }
}
