package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@code pressureHpa} field on {@link WeatherData}.
 */
class WeatherDataPressureTest {

    @Test
    @DisplayName("Pressure field is accessible and returns the provided value")
    void pressureFieldAccessible() {
        WeatherData data = new WeatherData(
                15000, BigDecimal.valueOf(5.0), 180, BigDecimal.ZERO,
                65, 3, BigDecimal.valueOf(100.0), 8.5, 1013.25);

        assertThat(data.pressureHpa()).isEqualTo(1013.25);
    }

    @Test
    @DisplayName("Pressure field can be null")
    void pressureFieldNullable() {
        WeatherData data = new WeatherData(
                15000, BigDecimal.valueOf(5.0), 180, BigDecimal.ZERO,
                65, 3, BigDecimal.valueOf(100.0), 8.5, null);

        assertThat(data.pressureHpa()).isNull();
    }
}
