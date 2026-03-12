package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SolarCloudTrend}.
 */
class SolarCloudTrendTest {

    @Test
    @DisplayName("isBuilding() returns true when last - first >= 20pp")
    void isBuilding_risingTrend_returnsTrue() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 5),
                new SolarCloudTrend.SolarCloudSlot(2, 12),
                new SolarCloudTrend.SolarCloudSlot(1, 25),
                new SolarCloudTrend.SolarCloudSlot(0, 30)));

        assertThat(trend.isBuilding()).isTrue();
    }

    @Test
    @DisplayName("isBuilding() returns true at exactly 20pp threshold")
    void isBuilding_exactlyTwentyPp_returnsTrue() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(0, 30)));

        assertThat(trend.isBuilding()).isTrue();
    }

    @Test
    @DisplayName("isBuilding() returns false when increase is less than 20pp")
    void isBuilding_smallIncrease_returnsFalse() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(2, 12),
                new SolarCloudTrend.SolarCloudSlot(1, 15),
                new SolarCloudTrend.SolarCloudSlot(0, 18)));

        assertThat(trend.isBuilding()).isFalse();
    }

    @Test
    @DisplayName("isBuilding() returns false when cloud is decreasing")
    void isBuilding_decreasingTrend_returnsFalse() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 80),
                new SolarCloudTrend.SolarCloudSlot(2, 60),
                new SolarCloudTrend.SolarCloudSlot(1, 30),
                new SolarCloudTrend.SolarCloudSlot(0, 10)));

        assertThat(trend.isBuilding()).isFalse();
    }

    @Test
    @DisplayName("isBuilding() returns false for single slot")
    void isBuilding_singleSlot_returnsFalse() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(0, 50)));

        assertThat(trend.isBuilding()).isFalse();
    }

    @Test
    @DisplayName("isBuilding() returns false for empty slots")
    void isBuilding_emptySlots_returnsFalse() {
        var trend = new SolarCloudTrend(List.of());

        assertThat(trend.isBuilding()).isFalse();
    }

    @Test
    @DisplayName("isBuilding() returns false for null slots")
    void isBuilding_nullSlots_returnsFalse() {
        var trend = new SolarCloudTrend(null);

        assertThat(trend.isBuilding()).isFalse();
    }
}
