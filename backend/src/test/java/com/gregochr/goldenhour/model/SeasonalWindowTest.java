package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SeasonalWindow}.
 */
class SeasonalWindowTest {

    @Test
    @DisplayName("BLUEBELL window includes start date (Apr 18)")
    void isActive_startDate_returnsTrue() {
        assertThat(SeasonalWindow.BLUEBELL.isActive(LocalDate.of(2026, 4, 18))).isTrue();
    }

    @Test
    @DisplayName("BLUEBELL window includes end date (May 18)")
    void isActive_endDate_returnsTrue() {
        assertThat(SeasonalWindow.BLUEBELL.isActive(LocalDate.of(2026, 5, 18))).isTrue();
    }

    @Test
    @DisplayName("BLUEBELL window includes a mid-season date")
    void isActive_midSeason_returnsTrue() {
        assertThat(SeasonalWindow.BLUEBELL.isActive(LocalDate.of(2026, 5, 1))).isTrue();
    }

    @Test
    @DisplayName("BLUEBELL window excludes the day before start (Apr 17)")
    void isActive_dayBeforeStart_returnsFalse() {
        assertThat(SeasonalWindow.BLUEBELL.isActive(LocalDate.of(2026, 4, 17))).isFalse();
    }

    @Test
    @DisplayName("BLUEBELL window excludes the day after end (May 19)")
    void isActive_dayAfterEnd_returnsFalse() {
        assertThat(SeasonalWindow.BLUEBELL.isActive(LocalDate.of(2026, 5, 19))).isFalse();
    }

    @Test
    @DisplayName("BLUEBELL window excludes a date well outside season")
    void isActive_winterDate_returnsFalse() {
        assertThat(SeasonalWindow.BLUEBELL.isActive(LocalDate.of(2026, 12, 1))).isFalse();
    }

    @Test
    @DisplayName("BLUEBELL window name is BLUEBELL")
    void name_isBLUEBELL() {
        assertThat(SeasonalWindow.BLUEBELL.name()).isEqualTo("BLUEBELL");
    }

    @Test
    @DisplayName("BLUEBELL window works in different years")
    void isActive_differentYear_worksCorrectly() {
        assertThat(SeasonalWindow.BLUEBELL.isActive(LocalDate.of(2027, 4, 30))).isTrue();
        assertThat(SeasonalWindow.BLUEBELL.isActive(LocalDate.of(2027, 5, 20))).isFalse();
    }
}
