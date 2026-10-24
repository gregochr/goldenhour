package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RegistrationProperties}: the shipped defaults and the startup validation of the
 * pending-registration hold window.
 */
class RegistrationPropertiesTest {

    private static RegistrationProperties withWindow(int hold, int first, int last) {
        RegistrationProperties properties = new RegistrationProperties();
        properties.setPendingHoldHours(hold);
        properties.setFirstReminderHoursLeft(first);
        properties.setFinalReminderHoursLeft(last);
        return properties;
    }

    @Test
    @DisplayName("the defaults are LITE_USER, a cap of 10, open registration and a 168/72/24 hold window")
    void defaults() {
        RegistrationProperties properties = new RegistrationProperties();

        assertThat(properties.getDefaultRole()).isEqualTo("LITE_USER");
        assertThat(properties.getMaxUsers()).isEqualTo(10);
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getPendingHoldHours()).isEqualTo(168);
        assertThat(properties.getFirstReminderHoursLeft()).isEqualTo(72);
        assertThat(properties.getFinalReminderHoursLeft()).isEqualTo(24);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "hold={0} first={1} final={2} is refused: {3}")
    @CsvSource({
            "0,   72, 24, hold not positive",
            "-24, 72, 24, hold negative",
            "170, 72, 24, hold not a multiple of 24",
            "168, 70, 24, first not a multiple of 24",
            "168, 0,  24, first not positive",
            "168, 72, 12, final not a multiple of 24",
            "168, 72, 0,  final not positive",
            "168, 48, 48, first minus final under a link life",
            "168, 24, 48, first below final",
            "72,  72, 24, first not inside the hold",
            "48,  72, 24, first beyond the hold"
    })
    @DisplayName("every validation rule rejects its bad case with an IllegalStateException")
    void invalidWindowsAreRefused(int hold, int first, int last, String why) {
        assertThatThrownBy(() -> withWindow(hold, first, last).validate())
                .as(why).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("photocast.registration.");
    }

    @Test
    @DisplayName("a longer, whole-day window is accepted")
    void validCustomWindow() {
        assertThatCode(() -> withWindow(336, 120, 48).validate()).doesNotThrowAnyException();
    }
}
