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

    @ParameterizedTest(name = "fromKp({0}, moderate at {1}) = {2}")
    @CsvSource({
        // A lower MODERATE threshold: 4.67 is MODERATE, not MINOR, and MINOR starts where it did.
        "4.49, 4.5, MINOR",
        "4.5,  4.5, MODERATE",
        "4.67, 4.5, MODERATE",
        "3.99, 4.5, QUIET",
        // A higher one: Kp 5 and 6 stay MINOR until 6.
        "5.99, 6.0, MINOR",
        "6.0,  6.0, MODERATE",
        // STRONG is fixed at 7 whatever the threshold.
        "6.99, 6.0, MODERATE",
        "7.0,  6.0, STRONG",
        "7.0,  4.5, STRONG",
        // A threshold above 7 cannot demote STRONG, and leaves MODERATE unreachable by Kp.
        "7.0,  8.0, STRONG",
        "6.99, 8.0, MINOR",
        // A threshold below 4 makes MODERATE start there, and leaves MINOR unreachable by Kp.
        "3.49, 3.5, QUIET",
        "3.5,  3.5, MODERATE",
        "3.99, 3.5, MODERATE",
        "4.0,  3.5, MODERATE"
    })
    @DisplayName("fromKp with a configured MODERATE threshold maps Kp at and around each boundary")
    void fromKp_withModerateThreshold_mapsAtEachBoundary(double kp, double moderateKp,
            AlertLevel expected) {
        assertThat(AlertLevel.fromKp(kp, moderateKp)).isEqualTo(expected);
    }

    @Test
    @DisplayName("fromKp never falls as Kp rises, for any MODERATE threshold")
    void fromKp_neverFallsAsKpRises() {
        // A night poll's level is kept at or above the rest-of-tonight forecast's by mapping a Kp
        // figure that is never lower through this same function. That argument is only as good as
        // this property.
        for (int thresholdTenths = 30; thresholdTenths <= 90; thresholdTenths++) {
            double moderateKp = thresholdTenths / 10.0;
            AlertLevel previous = AlertLevel.QUIET;
            for (int hundredths = 0; hundredths <= 900; hundredths++) {
                AlertLevel level = AlertLevel.fromKp(hundredths / 100.0, moderateKp);
                assertThat(level.severity())
                        .as("Kp %.2f with MODERATE at %.1f", hundredths / 100.0, moderateKp)
                        .isGreaterThanOrEqualTo(previous.severity());
                previous = level;
            }
        }
    }

    @ParameterizedTest(name = "gScaleFromKp({0}) = {1}")
    @CsvSource({
        "4.9, ",
        "5.0, G1",
        "5.9, G1",
        "6.0, G2",
        "7.0, G3",
        "8.0, G4",
        "8.67, G4",
        "9.0, G5",
        "12.0, G5"
    })
    @DisplayName("gScaleFromKp maps Kp values to NOAA storm scale, null below G1")
    void gScaleFromKp_mapping(double kp, String expected) {
        assertThat(AlertLevel.gScaleFromKp(kp)).isEqualTo(expected);
    }

    @Test
    @DisplayName("gScaleFromKp returns null for a null Kp")
    void gScaleFromKp_null() {
        assertThat(AlertLevel.gScaleFromKp(null)).isNull();
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
