package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AlertLevel}.
 */
class AlertLevelTest {

    @Test
    @DisplayName("Severity ordering: GREEN < YELLOW < AMBER < RED")
    void severityOrdering() {
        assertThat(AlertLevel.GREEN.severity()).isLessThan(AlertLevel.YELLOW.severity());
        assertThat(AlertLevel.YELLOW.severity()).isLessThan(AlertLevel.AMBER.severity());
        assertThat(AlertLevel.AMBER.severity()).isLessThan(AlertLevel.RED.severity());
    }

    @Test
    @DisplayName("Only AMBER and RED are alert-worthy")
    void alertWorthy() {
        assertThat(AlertLevel.GREEN.isAlertWorthy()).isFalse();
        assertThat(AlertLevel.YELLOW.isAlertWorthy()).isFalse();
        assertThat(AlertLevel.AMBER.isAlertWorthy()).isTrue();
        assertThat(AlertLevel.RED.isAlertWorthy()).isTrue();
    }

    @ParameterizedTest(name = "fromStatusId(\"{0}\") = {1}")
    @CsvSource({
        "green,  GREEN",
        "yellow, YELLOW",
        "amber,  AMBER",
        "red,    RED",
        "GREEN,  GREEN",
        "AMBER,  AMBER"
    })
    @DisplayName("fromStatusId parses all known values case-insensitively")
    void fromStatusId_knownValues(String input, AlertLevel expected) {
        assertThat(AlertLevel.fromStatusId(input.trim())).isEqualTo(expected);
    }

    @Test
    @DisplayName("fromStatusId throws for unknown values")
    void fromStatusId_unknownValue_throws() {
        assertThatThrownBy(() -> AlertLevel.fromStatusId("purple"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purple");
    }

    @ParameterizedTest(name = "{0} hexColour matches AuroraWatch spec")
    @CsvSource({
        "GREEN,  #33ff33",
        "YELLOW, #ffff00",
        "AMBER,  #ff9900",
        "RED,    #ff0000"
    })
    @DisplayName("hexColour matches AuroraWatch specification")
    void hexColourMatchesSpec(AlertLevel level, String expectedHex) {
        assertThat(level.hexColour()).isEqualTo(expectedHex.trim());
    }

    @Test
    @DisplayName("description is non-blank for all levels")
    void descriptionNonBlank() {
        for (AlertLevel level : AlertLevel.values()) {
            assertThat(level.description()).isNotBlank();
        }
    }
}
