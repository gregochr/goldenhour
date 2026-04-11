package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SurgeBlockFormatter}.
 */
class SurgeBlockFormatterTest {

    @Test
    @DisplayName("Significant surge produces full block with all sections")
    void significantSurge_fullBlock() {
        var surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test");

        String result = SurgeBlockFormatter.format(surge, 5.4, 5.0);

        assertThat(result)
                .contains("STORM SURGE FORECAST:")
                .contains("Pressure effect: +0.25m")
                .contains("low pressure 985 hPa")
                .contains("Wind effect: +0.15m")
                .contains("onshore wind")
                .contains("Total estimated surge: +0.40m (upper bound)")
                .contains("Adjusted tidal range: up to 5.4m (predicted 5.0m + 0.40m surge)")
                .contains("Risk level: MODERATE")
                .contains("Upper-bound estimate");
    }

    @Test
    @DisplayName("Insignificant surge returns empty string")
    void insignificantSurge_emptyString() {
        var surge = new StormSurgeBreakdown(
                0.01, 0.01, 0.02, 1015.0, 3.0, 180.0, 0.0,
                TideRiskLevel.NONE, "No surge");

        assertThat(SurgeBlockFormatter.format(surge, 4.02, 4.0)).isEmpty();
    }

    @Test
    @DisplayName("Null surge returns empty string")
    void nullSurge_emptyString() {
        assertThat(SurgeBlockFormatter.format(null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("Wind below 0.02m threshold omits wind line")
    void windBelowThreshold_omitsWindLine() {
        var surge = new StormSurgeBreakdown(
                0.15, 0.01, 0.16, 998.0, 5.0, 180.0, 0.0,
                TideRiskLevel.LOW, "Test");

        String result = SurgeBlockFormatter.format(surge, 4.16, 4.0);

        assertThat(result).contains("Pressure effect:");
        assertThat(result).doesNotContain("Wind effect:");
    }

    @Test
    @DisplayName("Null adjusted range omits tidal range line")
    void nullAdjustedRange_omitsRangeLine() {
        var surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test");

        String result = SurgeBlockFormatter.format(surge, null, null);

        assertThat(result).contains("STORM SURGE FORECAST:");
        assertThat(result).doesNotContain("Adjusted tidal range");
    }

    @Test
    @DisplayName("High pressure shows 'high' label")
    void highPressure_showsHighLabel() {
        var surge = new StormSurgeBreakdown(
                -0.10, 0.05, 0.10, 1030.0, 10.0, 80.0, 0.5,
                TideRiskLevel.LOW, "Test");

        String result = SurgeBlockFormatter.format(surge, 4.1, 4.0);

        assertThat(result).contains("high pressure 1030 hPa");
    }
}
