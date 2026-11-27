package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.CostProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the read-only {@link CostCalculator#ratesFor} accessor that the CI spend poller snapshots, including the
 * parity guarantee: a calculator built from a {@link CostProperties} filled with a stored snapshot prices exactly as
 * the original (the same code path), for every priced model and with 1-hour cache writes.
 */
class CostCalculatorRatesTest {

    private static final TokenUsage USAGE = new TokenUsage(612_000, 38_000, 21_000, 434_000, 9_000);

    @Test
    @DisplayName("ratesFor returns the five configured rates of the model's tier")
    void ratesFor_readsTheTierRates() {
        CostCalculator.ModelRates rates = new CostCalculator(new CostProperties()).ratesFor(EvaluationModel.SONNET);

        assertThat(rates).isEqualTo(new CostCalculator.ModelRates(3.00, 15.00, 3.75, 6.00, 0.30));
    }

    @Test
    @DisplayName("ratesFor gives an extended-thinking variant its base model's rates and WILDLIFE zeros")
    void ratesFor_variantsAndFree() {
        CostCalculator calculator = new CostCalculator(new CostProperties());

        assertThat(calculator.ratesFor(EvaluationModel.OPUS_ET)).isEqualTo(calculator.ratesFor(EvaluationModel.OPUS));
        assertThat(calculator.ratesFor(EvaluationModel.WILDLIFE))
                .isEqualTo(new CostCalculator.ModelRates(0.0, 0.0, 0.0, 0.0, 0.0));
    }

    @ParameterizedTest
    @EnumSource(value = EvaluationModel.class, names = {"HAIKU", "HAIKU_55", "SONNET", "SONNET_55", "OPUS"})
    @DisplayName("a calculator built from the stored rate snapshot prices identically, 1-hour writes included")
    void snapshotReproducesTheCost(EvaluationModel model) {
        CostCalculator original = new CostCalculator(new CostProperties());
        CostCalculator.ModelRates snapshot = original.ratesFor(model);

        CostCalculator rebuilt = new CostCalculator(propertiesFilledWith(model, snapshot));

        assertThat(rebuilt.calculateCostMicroDollars(model, USAGE, false))
                .isEqualTo(original.calculateCostMicroDollars(model, USAGE, false))
                .isPositive();
        assertThat(rebuilt.ratesFor(model)).isEqualTo(snapshot);
    }

    private static CostProperties propertiesFilledWith(EvaluationModel model, CostCalculator.ModelRates r) {
        CostProperties p = new CostProperties();
        switch (model.pricingTier()) {
            case HAIKU -> {
                p.setHaikuInputUsdPerMtok(r.inputUsdPerMtok());
                p.setHaikuOutputUsdPerMtok(r.outputUsdPerMtok());
                p.setHaikuCacheWriteUsdPerMtok(r.cacheWriteUsdPerMtok());
                p.setHaikuCacheWrite1hUsdPerMtok(r.cacheWrite1hUsdPerMtok());
                p.setHaikuCacheReadUsdPerMtok(r.cacheReadUsdPerMtok());
            }
            case HAIKU_55 -> {
                p.setHaiku55InputUsdPerMtok(r.inputUsdPerMtok());
                p.setHaiku55OutputUsdPerMtok(r.outputUsdPerMtok());
                p.setHaiku55CacheWriteUsdPerMtok(r.cacheWriteUsdPerMtok());
                p.setHaiku55CacheWrite1hUsdPerMtok(r.cacheWrite1hUsdPerMtok());
                p.setHaiku55CacheReadUsdPerMtok(r.cacheReadUsdPerMtok());
            }
            case SONNET -> {
                p.setSonnetInputUsdPerMtok(r.inputUsdPerMtok());
                p.setSonnetOutputUsdPerMtok(r.outputUsdPerMtok());
                p.setSonnetCacheWriteUsdPerMtok(r.cacheWriteUsdPerMtok());
                p.setSonnetCacheWrite1hUsdPerMtok(r.cacheWrite1hUsdPerMtok());
                p.setSonnetCacheReadUsdPerMtok(r.cacheReadUsdPerMtok());
            }
            case SONNET_55 -> {
                p.setSonnet55InputUsdPerMtok(r.inputUsdPerMtok());
                p.setSonnet55OutputUsdPerMtok(r.outputUsdPerMtok());
                p.setSonnet55CacheWriteUsdPerMtok(r.cacheWriteUsdPerMtok());
                p.setSonnet55CacheWrite1hUsdPerMtok(r.cacheWrite1hUsdPerMtok());
                p.setSonnet55CacheReadUsdPerMtok(r.cacheReadUsdPerMtok());
            }
            case OPUS -> {
                p.setOpusInputUsdPerMtok(r.inputUsdPerMtok());
                p.setOpusOutputUsdPerMtok(r.outputUsdPerMtok());
                p.setOpusCacheWriteUsdPerMtok(r.cacheWriteUsdPerMtok());
                p.setOpusCacheWrite1hUsdPerMtok(r.cacheWrite1hUsdPerMtok());
                p.setOpusCacheReadUsdPerMtok(r.cacheReadUsdPerMtok());
            }
            default -> throw new IllegalArgumentException("not a priced tier: " + model.pricingTier());
        }
        return p;
    }
}
