package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.model.BriefingSlot;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SlotCloud}. */
class SlotCloudTest {

    private static BriefingSlot.WeatherConditions weather(int low, int mid, int high,
            Integer sunLow, Integer sunMid, Integer sunHigh, Integer antiMid, Integer antiHigh) {
        return new BriefingSlot.WeatherConditions(low, BigDecimal.ZERO, 20000, 65,
                10.0, 9.0, 0, BigDecimal.ONE, mid, high, sunLow, sunMid, sunHigh, antiMid, antiHigh);
    }

    @Test
    @DisplayName("Directional when all three sun-side layers are present")
    void directional_allThree() {
        assertThat(SlotCloud.isDirectional(weather(5, 5, 5, 1, 2, 3, null, null))).isTrue();
    }

    @Test
    @DisplayName("Not directional when no horizon was sampled")
    void directional_none() {
        assertThat(SlotCloud.isDirectional(weather(5, 5, 5, null, null, null, null, null))).isFalse();
    }

    @Test
    @DisplayName("Not directional when any single sun-side layer is null")
    void directional_oneNull() {
        assertThat(SlotCloud.isDirectional(weather(5, 5, 5, null, 2, 3, null, null))).isFalse();
        assertThat(SlotCloud.isDirectional(weather(5, 5, 5, 1, null, 3, null, null))).isFalse();
        assertThat(SlotCloud.isDirectional(weather(5, 5, 5, 1, 2, null, null, null))).isFalse();
    }

    @Test
    @DisplayName("observer() returns the observer point's own layers")
    void observer_layers() {
        assertThat(SlotCloud.observer(weather(7, 8, 9, 1, 2, 3, 4, 5)))
                .isEqualTo(new SlotCloud.Layers(7, 8, 9));
    }

    @Test
    @DisplayName("horizon() takes sun-side low and the larger side for mid and high")
    void horizon_largerSide() {
        assertThat(SlotCloud.horizon(weather(7, 8, 9, 10, 20, 30, 40, 5)))
                .isEqualTo(new SlotCloud.Layers(10, 40, 30));
    }

    @Test
    @DisplayName("horizon() treats null anti-sun layers as 0")
    void horizon_nullAntiSun() {
        assertThat(SlotCloud.horizon(weather(7, 8, 9, 10, 20, 30, null, null)))
                .isEqualTo(new SlotCloud.Layers(10, 20, 30));
    }

    @Test
    @DisplayName("isClear is strictly below 15 on every layer")
    void isClear_boundaries() {
        assertThat(new SlotCloud.Layers(14, 14, 14).isClear()).isTrue();
        assertThat(new SlotCloud.Layers(15, 14, 14).isClear()).isFalse();
        assertThat(new SlotCloud.Layers(14, 15, 14).isClear()).isFalse();
        assertThat(new SlotCloud.Layers(14, 14, 15).isClear()).isFalse();
    }

    @Test
    @DisplayName("canvas is the larger of mid and high")
    void canvas_max() {
        assertThat(new SlotCloud.Layers(0, 30, 70).canvas()).isEqualTo(70);
        assertThat(new SlotCloud.Layers(0, 80, 70).canvas()).isEqualTo(80);
    }
}
