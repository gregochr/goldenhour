package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the single thin-strip definition on {@link DirectionalCloudData}.
 */
class DirectionalCloudDataTest {

    @Test
    @DisplayName("a near reading of 50 with a 30-point drop is a thin strip (both edges inclusive)")
    void isThinStrip_atBothEdges_isTrue() {
        assertThat(DirectionalCloudData.isThinStrip(50, 20)).isTrue();
    }

    @Test
    @DisplayName("a near reading of 49 is not significant, whatever the drop")
    void isThinStrip_nearBelowFifty_isFalse() {
        assertThat(DirectionalCloudData.isThinStrip(49, 0)).isFalse();
    }

    @Test
    @DisplayName("a 29-point drop is a blanket, not a strip")
    void isThinStrip_dropOfTwentyNine_isFalse() {
        assertThat(DirectionalCloudData.isThinStrip(70, 41)).isFalse();
    }

    @Test
    @DisplayName("no far reading means no strip can be shown")
    void isThinStrip_nullFar_isFalse() {
        assertThat(DirectionalCloudData.isThinStrip(90, null)).isFalse();
    }

    @Test
    @DisplayName("the instance method reads the solar and far-solar low cloud")
    void instanceIsThinStrip_delegatesToTheStatic() {
        assertThat(new DirectionalCloudData(70, 10, 5, 20, 10, 5, 40).isThinStrip()).isTrue();
        assertThat(new DirectionalCloudData(70, 10, 5, 20, 10, 5, 60).isThinStrip()).isFalse();
        assertThat(new DirectionalCloudData(70, 10, 5, 20, 10, 5, null).isThinStrip()).isFalse();
    }
}
