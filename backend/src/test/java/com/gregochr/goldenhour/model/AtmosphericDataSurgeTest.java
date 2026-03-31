package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.TestAtmosphericData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the storm surge fields on {@link AtmosphericData}.
 */
class AtmosphericDataSurgeTest {

    @Test
    @DisplayName("withSurge() populates surge fields while preserving other data")
    void withSurgePopulatesFields() {
        AtmosphericData base = TestAtmosphericData.defaults();
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test");

        AtmosphericData result = base.withSurge(surge, 5.4, 5.0);

        assertThat(result.surge()).isEqualTo(surge);
        assertThat(result.adjustedRangeMetres()).isEqualTo(5.4);
        assertThat(result.astronomicalRangeMetres()).isEqualTo(5.0);
        assertThat(result.locationName()).isEqualTo(base.locationName());
        assertThat(result.weather()).isEqualTo(base.weather());
        assertThat(result.cloud()).isEqualTo(base.cloud());
    }

    @Test
    @DisplayName("Default construction has null surge fields")
    void defaultHasNullSurge() {
        AtmosphericData data = TestAtmosphericData.defaults();

        assertThat(data.surge()).isNull();
        assertThat(data.adjustedRangeMetres()).isNull();
        assertThat(data.astronomicalRangeMetres()).isNull();
    }

    @Test
    @DisplayName("withDirectionalCloud preserves existing surge data")
    void withDirectionalCloudPreservesSurge() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test");
        AtmosphericData withSurge = TestAtmosphericData.defaults().withSurge(surge, 5.4, 5.0);

        DirectionalCloudData dc = new DirectionalCloudData(10, 20, 30, 40, 50, 60, 5);
        AtmosphericData result = withSurge.withDirectionalCloud(dc);

        assertThat(result.directionalCloud()).isEqualTo(dc);
        assertThat(result.surge()).isEqualTo(surge);
        assertThat(result.adjustedRangeMetres()).isEqualTo(5.4);
    }

    @Test
    @DisplayName("Backward-compatible 11-arg constructor sets surge fields to null")
    void backwardCompatibleConstructorNullSurge() {
        AtmosphericData data = TestAtmosphericData.defaults();

        assertThat(data.surge()).isNull();
        assertThat(data.adjustedRangeMetres()).isNull();
        assertThat(data.astronomicalRangeMetres()).isNull();
    }

    @Test
    @DisplayName("withTide() preserves existing surge data")
    void withTidePreservesSurge() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test");
        AtmosphericData withSurge = TestAtmosphericData.defaults().withSurge(surge, 5.4, 5.0);

        AtmosphericData result = withSurge.withTide(null);

        assertThat(result.tide()).isNull();
        assertThat(result.surge()).isEqualTo(surge);
        assertThat(result.adjustedRangeMetres()).isEqualTo(5.4);
        assertThat(result.astronomicalRangeMetres()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("withCloudApproach() preserves existing surge data")
    void withCloudApproachPreservesSurge() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test");
        AtmosphericData withSurge = TestAtmosphericData.defaults().withSurge(surge, 5.4, 5.0);

        AtmosphericData result = withSurge.withCloudApproach(null);

        assertThat(result.cloudApproach()).isNull();
        assertThat(result.surge()).isEqualTo(surge);
        assertThat(result.adjustedRangeMetres()).isEqualTo(5.4);
    }
}
