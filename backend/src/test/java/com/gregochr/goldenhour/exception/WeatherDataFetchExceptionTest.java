package com.gregochr.goldenhour.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WeatherDataFetchException}.
 */
class WeatherDataFetchExceptionTest {

    @Test
    @DisplayName("constructor stores message, locationName, targetType and cause")
    void constructor_storesAllFields() {
        RuntimeException cause = new RuntimeException("upstream error");

        WeatherDataFetchException ex = new WeatherDataFetchException(
                "fetch failed", "Durham UK", "SUNRISE", cause);

        assertThat(ex.getMessage()).isEqualTo("fetch failed");
        assertThat(ex.getLocationName()).isEqualTo("Durham UK");
        assertThat(ex.getTargetType()).isEqualTo("SUNRISE");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("constructor accepts null cause")
    void constructor_nullCause_isAllowed() {
        WeatherDataFetchException ex = new WeatherDataFetchException(
                "null return", "Scarborough", "SUNSET", null);

        assertThat(ex.getLocationName()).isEqualTo("Scarborough");
        assertThat(ex.getTargetType()).isEqualTo("SUNSET");
        assertThat(ex.getCause()).isNull();
    }
}
