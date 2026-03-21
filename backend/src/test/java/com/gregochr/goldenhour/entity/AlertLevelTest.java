package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AlertLevel}.
 */
class AlertLevelTest {

    @Test
    @DisplayName("Severity ordering: QUIET < MINOR < MODERATE < STRONG")
    void severityOrdering() {
        assertThat(AlertLevel.QUIET.severity()).isLessThan(AlertLevel.MINOR.severity());
        assertThat(AlertLevel.MINOR.severity()).isLessThan(AlertLevel.MODERATE.severity());
        assertThat(AlertLevel.MODERATE.severity()).isLessThan(AlertLevel.STRONG.severity());
    }

    @Test
    @DisplayName("Only MODERATE and STRONG are alert-worthy")
    void alertWorthy() {
        assertThat(AlertLevel.QUIET.isAlertWorthy()).isFalse();
        assertThat(AlertLevel.MINOR.isAlertWorthy()).isFalse();
        assertThat(AlertLevel.MODERATE.isAlertWorthy()).isTrue();
        assertThat(AlertLevel.STRONG.isAlertWorthy()).isTrue();
    }

    @ParameterizedTest(name = "fromKp({0}) = {1}")
    @CsvSource({
        "0.0, QUIET",
        "3.9, QUIET",
        "4.0, MINOR",
        "4.9, MINOR",
        "5.0, MODERATE",
        "6.9, MODERATE",
        "7.0, STRONG",
        "9.0, STRONG"
    })
    @DisplayName("fromKp maps Kp values to correct levels")
    void fromKp_mapping(double kp, AlertLevel expected) {
        assertThat(AlertLevel.fromKp(kp)).isEqualTo(expected);
    }

    @Test
    @DisplayName("hexColour is non-blank for all levels")
    void hexColourNonBlank() {
        for (AlertLevel level : AlertLevel.values()) {
            assertThat(level.hexColour()).isNotBlank();
        }
    }

    @Test
    @DisplayName("description is non-blank for all levels")
    void descriptionNonBlank() {
        for (AlertLevel level : AlertLevel.values()) {
            assertThat(level.description()).isNotBlank();
        }
    }

    @Test
    @DisplayName("STRONG has hex #ff0000 (red)")
    void strongHexIsRed() {
        assertThat(AlertLevel.STRONG.hexColour()).isEqualTo("#ff0000");
    }

    @Test
    @DisplayName("MODERATE has hex #ff9900 (orange)")
    void moderateHexIsOrange() {
        assertThat(AlertLevel.MODERATE.hexColour()).isEqualTo("#ff9900");
    }
}
