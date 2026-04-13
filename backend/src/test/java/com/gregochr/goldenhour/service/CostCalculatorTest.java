package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.CostProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CostCalculator}.
 */
class CostCalculatorTest {

    private CostCalculator calculator() {
        return new CostCalculator(new CostProperties());
    }

    // --- Token-based micro-dollar tests ---

    @Test
    @DisplayName("calculateCostMicroDollars() for Haiku: 500 input tokens = 500 µ$")
    void calculateCostMicroDollars_haiku_inputTokens() {
        TokenUsage usage = new TokenUsage(500, 0, 0, 0);
        long cost = calculator().calculateCostMicroDollars(EvaluationModel.HAIKU, usage);
        // 500 tokens * $1.00/MTok = 500 µ$
        assertThat(cost).isEqualTo(500);
    }

    @Test
    @DisplayName("calculateCostMicroDollars() for Haiku: 100 output tokens = 500 µ$")
    void calculateCostMicroDollars_haiku_outputTokens() {
        TokenUsage usage = new TokenUsage(0, 100, 0, 0);
        long cost = calculator().calculateCostMicroDollars(EvaluationModel.HAIKU, usage);
        // 100 tokens * $5.00/MTok = 500 µ$
        assertThat(cost).isEqualTo(500);
    }

    @Test
    @DisplayName("calculateCostMicroDollars() for Opus: mixed tokens")
    void calculateCostMicroDollars_opus_mixedTokens() {
        TokenUsage usage = new TokenUsage(1000, 100, 500, 200);
        long cost = calculator().calculateCostMicroDollars(EvaluationModel.OPUS, usage);
        // Input: 1000 * 5.00 = 5000
        // Output: 100 * 25.00 = 2500
        // Cache write: 500 * 6.25 = 3125
        // Cache read: 200 * 0.50 = 100
        // Total: 10725
        assertThat(cost).isEqualTo(10725);
    }

    @Test
    @DisplayName("calculateCostMicroDollars() for Sonnet: cache write tokens")
    void calculateCostMicroDollars_sonnet_cacheWriteTokens() {
        TokenUsage usage = new TokenUsage(0, 0, 1000, 0);
        long cost = calculator().calculateCostMicroDollars(EvaluationModel.SONNET, usage);
        // 1000 tokens * $3.75/MTok = 3750 µ$
        assertThat(cost).isEqualTo(3750);
    }

    @Test
    @DisplayName("calculateCostMicroDollars() for Haiku: cache read tokens")
    void calculateCostMicroDollars_haiku_cacheReadTokens() {
        TokenUsage usage = new TokenUsage(0, 0, 0, 1000);
        long cost = calculator().calculateCostMicroDollars(EvaluationModel.HAIKU, usage);
        // 1000 tokens * $0.10/MTok = 100 µ$
        assertThat(cost).isEqualTo(100);
    }

    @Test
    @DisplayName("calculateCostMicroDollars() with batch flag halves cost")
    void calculateCostMicroDollars_batch_halvesCost() {
        TokenUsage usage = new TokenUsage(1000, 0, 0, 0);
        long normalCost = calculator().calculateCostMicroDollars(EvaluationModel.HAIKU, usage, false);
        long batchCost = calculator().calculateCostMicroDollars(EvaluationModel.HAIKU, usage, true);
        assertThat(batchCost).isEqualTo(normalCost / 2);
    }

    @Test
    @DisplayName("calculateCostMicroDollars() returns 0 for EMPTY usage")
    void calculateCostMicroDollars_empty_returnsZero() {
        long cost = calculator().calculateCostMicroDollars(EvaluationModel.SONNET, TokenUsage.EMPTY);
        assertThat(cost).isZero();
    }

    @Test
    @DisplayName("calculateCostMicroDollars() returns 0 for null usage")
    void calculateCostMicroDollars_nullUsage_returnsZero() {
        long cost = calculator().calculateCostMicroDollars(EvaluationModel.SONNET, null);
        assertThat(cost).isZero();
    }

    @Test
    @DisplayName("calculateCostMicroDollars() returns 0 for null model")
    void calculateCostMicroDollars_nullModel_returnsZero() {
        long cost = calculator().calculateCostMicroDollars(null, new TokenUsage(100, 50, 0, 0));
        assertThat(cost).isZero();
    }

    // --- Flat cost micro-dollar tests ---

    @Test
    @DisplayName("calculateFlatCostMicroDollars() for WorldTides returns configured value")
    void calculateFlatCostMicroDollars_worldTides() {
        assertThat(calculator().calculateFlatCostMicroDollars(ServiceName.WORLD_TIDES))
                .isEqualTo(3000);
    }

    @Test
    @DisplayName("calculateFlatCostMicroDollars() for OpenMeteo returns 0")
    void calculateFlatCostMicroDollars_openMeteo() {
        assertThat(calculator().calculateFlatCostMicroDollars(ServiceName.OPEN_METEO_FORECAST)).isZero();
        assertThat(calculator().calculateFlatCostMicroDollars(ServiceName.OPEN_METEO_AIR_QUALITY)).isZero();
    }

    // --- Legacy flat-rate tests (backward compatibility) ---

    @Test
    @DisplayName("calculateCost() legacy: returns Haiku cost for HAIKU model")
    @SuppressWarnings("deprecation")
    void calculateCost_legacy_haiku() {
        assertThat(calculator().calculateCost(ServiceName.ANTHROPIC, EvaluationModel.HAIKU)).isEqualTo(5);
    }

    @Test
    @DisplayName("calculateCost() legacy: returns Sonnet cost for SONNET model")
    @SuppressWarnings("deprecation")
    void calculateCost_legacy_sonnet() {
        assertThat(calculator().calculateCost(ServiceName.ANTHROPIC, EvaluationModel.SONNET)).isEqualTo(13);
    }

    @Test
    @DisplayName("calculateCost() legacy: returns Opus cost for OPUS model")
    @SuppressWarnings("deprecation")
    void calculateCost_legacy_opus() {
        assertThat(calculator().calculateCost(ServiceName.ANTHROPIC, EvaluationModel.OPUS)).isEqualTo(75);
    }

    @Test
    @DisplayName("calculateCost() legacy: returns WorldTides cost")
    @SuppressWarnings("deprecation")
    void calculateCost_legacy_worldTides() {
        assertThat(calculator().calculateCost(ServiceName.WORLD_TIDES)).isEqualTo(2);
    }

    @Test
    @DisplayName("calculateCost() legacy: returns zero for OpenMeteo")
    @SuppressWarnings("deprecation")
    void calculateCost_legacy_openMeteo() {
        assertThat(calculator().calculateCost(ServiceName.OPEN_METEO_FORECAST)).isZero();
    }

    @Test
    @DisplayName("calculateFlatCostMicroDollars() for LIGHT_POLLUTION returns 0 (free API)")
    void calculateFlatCostMicroDollars_lightPollution_returnsZero() {
        assertThat(calculator().calculateFlatCostMicroDollars(ServiceName.LIGHT_POLLUTION)).isZero();
    }

    @Test
    @DisplayName("calculateCost() legacy: returns zero for LIGHT_POLLUTION (free API)")
    @SuppressWarnings("deprecation")
    void calculateCost_legacy_lightPollution_returnsZero() {
        assertThat(calculator().calculateCost(ServiceName.LIGHT_POLLUTION)).isZero();
    }
}
