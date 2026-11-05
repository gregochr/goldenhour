package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cover and rain flags of {@link WoodlandVerdictEvaluator#buildFlags}: harsh dapple under broken sun, no cover
 * flag in the 30-69% band, and heavy rain only above 1.0 mm.
 */
class WoodlandVerdictEvaluatorFlagsTest {

    private final WoodlandVerdictEvaluator evaluator = new WoodlandVerdictEvaluator();

    private static WoodlandVerdictEvaluator.WoodlandMetrics metrics(int low, int mid, int visibility, String precip) {
        return new WoodlandVerdictEvaluator.WoodlandMetrics(low, mid, visibility,
                precip == null ? null : new BigDecimal(precip), new BigDecimal("1.0"));
    }

    @Test
    @DisplayName("combined cover below 30% in clear air flags harsh dapple")
    void clearSkyFlagsHarshDapple() {
        assertThat(evaluator.buildFlags(metrics(10, 10, 20_000, "0.0"))).containsExactly("Harsh dapple");
    }

    @Test
    @DisplayName("combined cover of exactly 30% is not harsh dapple, and the 30-69% band carries no cover flag")
    void middleBandCarriesNoCoverFlag() {
        assertThat(evaluator.buildFlags(metrics(15, 15, 20_000, "0.0"))).isEmpty();
        assertThat(evaluator.buildFlags(metrics(35, 34, 20_000, "0.0"))).isEmpty();
    }

    @Test
    @DisplayName("cover of 70% or more flags soft even light")
    void heavyCoverFlagsSoftLight() {
        assertThat(evaluator.buildFlags(metrics(40, 30, 20_000, "0.0"))).containsExactly("Soft even light");
    }

    @Test
    @DisplayName("rain above 1.0 mm is flagged heavy; exactly 1.0 mm is not; no reading is not")
    void heavyRainFlag() {
        assertThat(evaluator.buildFlags(metrics(40, 40, 20_000, "1.1"))).containsExactly("Soft even light",
                "Heavy rain");
        assertThat(evaluator.buildFlags(metrics(40, 40, 20_000, "1.0"))).containsExactly("Soft even light");
        assertThat(evaluator.buildFlags(metrics(40, 40, 20_000, null))).containsExactly("Soft even light");
    }
}
