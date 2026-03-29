package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Confidence}.
 */
class ConfidenceTest {

    @Test
    @DisplayName("fromString parses lowercase values")
    void parsesLowercase() {
        assertThat(Confidence.fromString("high")).isEqualTo(Confidence.HIGH);
        assertThat(Confidence.fromString("medium")).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.fromString("low")).isEqualTo(Confidence.LOW);
    }

    @Test
    @DisplayName("fromString is case-insensitive")
    void caseInsensitive() {
        assertThat(Confidence.fromString("HIGH")).isEqualTo(Confidence.HIGH);
        assertThat(Confidence.fromString("Medium")).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.fromString("Low")).isEqualTo(Confidence.LOW);
    }

    @Test
    @DisplayName("fromString defaults to MEDIUM for null")
    void nullDefaultsMedium() {
        assertThat(Confidence.fromString(null)).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    @DisplayName("fromString defaults to MEDIUM for unrecognised value")
    void unknownDefaultsMedium() {
        assertThat(Confidence.fromString("very_high")).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.fromString("")).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    @DisplayName("getValue returns lowercase for JSON serialization")
    void getValueLowercase() {
        assertThat(Confidence.HIGH.getValue()).isEqualTo("high");
        assertThat(Confidence.MEDIUM.getValue()).isEqualTo("medium");
        assertThat(Confidence.LOW.getValue()).isEqualTo("low");
    }
}
