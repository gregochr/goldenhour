package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TideRiskLevel}.
 */
class TideRiskLevelTest {

    @Test
    @DisplayName("All four risk levels exist")
    void allLevelsExist() {
        assertThat(TideRiskLevel.values()).containsExactly(
                TideRiskLevel.NONE,
                TideRiskLevel.LOW,
                TideRiskLevel.MODERATE,
                TideRiskLevel.HIGH
        );
    }

    @Test
    @DisplayName("valueOf round-trips correctly")
    void valueOfRoundTrips() {
        for (TideRiskLevel level : TideRiskLevel.values()) {
            assertThat(TideRiskLevel.valueOf(level.name())).isEqualTo(level);
        }
    }

    @Test
    @DisplayName("Ordinal values reflect severity ordering")
    void ordinalOrdering() {
        assertThat(TideRiskLevel.NONE.ordinal()).isLessThan(TideRiskLevel.LOW.ordinal());
        assertThat(TideRiskLevel.LOW.ordinal()).isLessThan(TideRiskLevel.MODERATE.ordinal());
        assertThat(TideRiskLevel.MODERATE.ordinal()).isLessThan(TideRiskLevel.HIGH.ordinal());
    }
}
