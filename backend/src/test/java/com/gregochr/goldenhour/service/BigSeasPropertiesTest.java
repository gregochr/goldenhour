package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link BigSeasProperties}: the Java defaults and every bound, at and beyond. */
class BigSeasPropertiesTest {

    @Test
    @DisplayName("a default-constructed instance carries the plan's thresholds, with no YAML at all")
    void defaults() {
        BigSeasProperties p = new BigSeasProperties();

        assertThat(p.getMinWaveHeightM()).isEqualTo(3.0);
        assertThat(p.getMaxOnshoreAngleDeg()).isEqualTo(60);
        assertThat(p.getMinWavePeriodS()).isEqualTo(8.0);
        assertThat(p.getHalfWindowMinutes()).isEqualTo(90);
        assertThat(p.getMaxSampleAgeHours()).isEqualTo(30);
        assertThatCode(p::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("each setter accepts both ends of its range and stores the value")
    void boundsAreInclusive() {
        BigSeasProperties p = new BigSeasProperties();

        p.setMinWaveHeightM(0.5);
        p.setMinWaveHeightM(15.0);
        p.setMaxOnshoreAngleDeg(0);
        p.setMaxOnshoreAngleDeg(180);
        p.setMinWavePeriodS(1.0);
        p.setMinWavePeriodS(30.0);
        p.setHalfWindowMinutes(15);
        p.setHalfWindowMinutes(180);
        p.setMaxSampleAgeHours(1);
        p.setMaxSampleAgeHours(168);

        assertThat(p.getMinWaveHeightM()).isEqualTo(15.0);
        assertThat(p.getMaxOnshoreAngleDeg()).isEqualTo(180);
        assertThat(p.getMinWavePeriodS()).isEqualTo(30.0);
        assertThat(p.getHalfWindowMinutes()).isEqualTo(180);
        assertThat(p.getMaxSampleAgeHours()).isEqualTo(168);
    }

    @Test
    @DisplayName("a value one step outside any range, or NaN, is refused and names its key")
    void outOfRangeIsRefused() {
        BigSeasProperties p = new BigSeasProperties();

        assertThatThrownBy(() -> p.setMinWaveHeightM(0.49)).hasMessageContaining("min-wave-height-m");
        assertThatThrownBy(() -> p.setMinWaveHeightM(15.01)).hasMessageContaining("min-wave-height-m");
        assertThatThrownBy(() -> p.setMinWaveHeightM(Double.NaN)).hasMessageContaining("min-wave-height-m");
        assertThatThrownBy(() -> p.setMaxOnshoreAngleDeg(-1)).hasMessageContaining("max-onshore-angle-deg");
        assertThatThrownBy(() -> p.setMaxOnshoreAngleDeg(181)).hasMessageContaining("max-onshore-angle-deg");
        assertThatThrownBy(() -> p.setMinWavePeriodS(0.99)).hasMessageContaining("min-wave-period-s");
        assertThatThrownBy(() -> p.setMinWavePeriodS(30.01)).hasMessageContaining("min-wave-period-s");
        assertThatThrownBy(() -> p.setHalfWindowMinutes(14)).hasMessageContaining("half-window-minutes");
        assertThatThrownBy(() -> p.setHalfWindowMinutes(181)).hasMessageContaining("half-window-minutes");
        assertThatThrownBy(() -> p.setMaxSampleAgeHours(0)).hasMessageContaining("max-sample-age-hours");
        assertThatThrownBy(() -> p.setMaxSampleAgeHours(169)).hasMessageContaining("max-sample-age-hours");
    }
}
