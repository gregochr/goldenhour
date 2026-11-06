package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DrivePoint#parse}: a drive point is a name and a coordinate pair, all three or none.
 */
class DrivePointTest {

    @Test
    @DisplayName("all three absent is no drive point, and a blank name counts as absent")
    void parse_allAbsent_isNull() {
        assertThat(DrivePoint.parse(null, null, null)).isNull();
        assertThat(DrivePoint.parse("   ", null, null)).isNull();
    }

    @Test
    @DisplayName("a name with both coordinates parses, with the name trimmed")
    void parse_complete_parses() {
        DrivePoint point = DrivePoint.parse("  Glenridding ", 54.5442, -2.9505);

        assertThat(point).isEqualTo(new DrivePoint("Glenridding", 54.5442, -2.9505));
    }

    @Test
    @DisplayName("any partial combination is refused, whichever field is missing")
    void parse_partial_isRefused() {
        assertThatThrownBy(() -> DrivePoint.parse("Glenridding", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("together");
        assertThatThrownBy(() -> DrivePoint.parse(null, 54.5, -2.9))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("together");
        assertThatThrownBy(() -> DrivePoint.parse("Glenridding", 54.5, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrivePoint.parse("Glenridding", null, -2.9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a name over the column length is refused")
    void parse_longName_isRefused() {
        String tooLong = "x".repeat(DrivePoint.MAX_NAME_LENGTH + 1);

        assertThatThrownBy(() -> DrivePoint.parse(tooLong, 54.5, -2.9))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("120");
        assertThat(DrivePoint.parse("x".repeat(DrivePoint.MAX_NAME_LENGTH), 54.5, -2.9)).isNotNull();
    }

    @Test
    @DisplayName("coordinates outside their range, or not finite, are refused")
    void parse_badCoordinates_areRefused() {
        assertThatThrownBy(() -> DrivePoint.parse("A", 91.0, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrivePoint.parse("A", -91.0, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrivePoint.parse("A", 0.0, 181.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrivePoint.parse("A", 0.0, -181.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrivePoint.parse("A", Double.NaN, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrivePoint.parse("A", 0.0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DrivePoint.parse("A", 90.0, 180.0)).isNotNull();
        assertThat(DrivePoint.parse("A", -90.0, -180.0)).isNotNull();
    }

    @Test
    @DisplayName("a drive point within 25 km of the pin passes, one beyond it is refused")
    void requireWithinRangeOf_enforcesTheLimit() {
        DrivePoint glenridding = new DrivePoint("Glenridding", 54.5442, -2.9505);

        glenridding.requireWithinRangeOf(54.5275, -3.0162);
        assertThatThrownBy(() -> glenridding.requireWithinRangeOf(55.5, -1.7))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("25 km");
    }
}
