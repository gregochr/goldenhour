package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StormSurgeBreakdown}.
 */
class StormSurgeBreakdownTest {

    @Test
    @DisplayName("none() returns zero values with NONE risk")
    void noneBreakdown() {
        StormSurgeBreakdown none = StormSurgeBreakdown.none();

        assertThat(none.pressureRiseMetres()).isZero();
        assertThat(none.windRiseMetres()).isZero();
        assertThat(none.totalSurgeMetres()).isZero();
        assertThat(none.pressureHpa()).isEqualTo(1013.25);
        assertThat(none.windSpeedMs()).isZero();
        assertThat(none.windDirectionDegrees()).isZero();
        assertThat(none.onshoreComponentFraction()).isZero();
        assertThat(none.riskLevel()).isEqualTo(TideRiskLevel.NONE);
        assertThat(none.surgeExplanation()).isEqualTo("No significant surge expected");
    }

    @Test
    @DisplayName("isSignificant returns true at 0.05m")
    void significantAtThreshold() {
        var surge = new StormSurgeBreakdown(0.03, 0.02, 0.05, 1010, 10, 80, 0.5,
                TideRiskLevel.LOW, "test");
        assertThat(surge.isSignificant()).isTrue();
    }

    @Test
    @DisplayName("isSignificant returns false below 0.05m")
    void notSignificantBelowThreshold() {
        var surge = new StormSurgeBreakdown(0.02, 0.01, 0.03, 1010, 5, 80, 0.3,
                TideRiskLevel.NONE, "test");
        assertThat(surge.isSignificant()).isFalse();
    }

    @Test
    @DisplayName("isSignificant returns false for none()")
    void noneIsNotSignificant() {
        assertThat(StormSurgeBreakdown.none().isSignificant()).isFalse();
    }

    @Test
    @DisplayName("isSignificant returns true for large surge")
    void largeSurgeIsSignificant() {
        var surge = new StormSurgeBreakdown(0.3, 0.2, 0.5, 980, 20, 80, 0.9,
                TideRiskLevel.HIGH, "test");
        assertThat(surge.isSignificant()).isTrue();
    }

    @Test
    @DisplayName("Record equality works correctly")
    void recordEquality() {
        var a = new StormSurgeBreakdown(0.1, 0.2, 0.3, 990, 15, 80, 0.8,
                TideRiskLevel.MODERATE, "test");
        var b = new StormSurgeBreakdown(0.1, 0.2, 0.3, 990, 15, 80, 0.8,
                TideRiskLevel.MODERATE, "test");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("none() instances are equal")
    void noneEquality() {
        assertThat(StormSurgeBreakdown.none()).isEqualTo(StormSurgeBreakdown.none());
    }

    @Test
    @DisplayName("Boundary: exactly 0.049m is not significant")
    void boundaryBelowSignificance() {
        var surge = new StormSurgeBreakdown(0.02, 0.029, 0.049, 1010, 8, 80, 0.4,
                TideRiskLevel.NONE, "test");
        assertThat(surge.isSignificant()).isFalse();
    }
}
