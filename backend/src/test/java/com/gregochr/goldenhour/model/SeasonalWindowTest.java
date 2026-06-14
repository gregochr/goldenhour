package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SeasonalWindow}.
 *
 * <p>The bluebell window is now built from config (see
 * {@link com.gregochr.goldenhour.config.SeasonConfig}); these tests exercise the record's
 * {@code isActive} contract using a window constructed with the historic default boundaries
 * (Apr 18 – May 18).
 */
class SeasonalWindowTest {

    /** A window built from the historic default bluebell boundaries. */
    private static final SeasonalWindow BLUEBELL =
            new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL");

    @Test
    @DisplayName("Window includes start date (Apr 18)")
    void isActive_startDate_returnsTrue() {
        assertThat(BLUEBELL.isActive(LocalDate.of(2026, 4, 18))).isTrue();
    }

    @Test
    @DisplayName("Window includes end date (May 18)")
    void isActive_endDate_returnsTrue() {
        assertThat(BLUEBELL.isActive(LocalDate.of(2026, 5, 18))).isTrue();
    }

    @Test
    @DisplayName("Window includes a mid-season date")
    void isActive_midSeason_returnsTrue() {
        assertThat(BLUEBELL.isActive(LocalDate.of(2026, 5, 1))).isTrue();
    }

    @Test
    @DisplayName("Window excludes the day before start (Apr 17)")
    void isActive_dayBeforeStart_returnsFalse() {
        assertThat(BLUEBELL.isActive(LocalDate.of(2026, 4, 17))).isFalse();
    }

    @Test
    @DisplayName("Window excludes the day after end (May 19)")
    void isActive_dayAfterEnd_returnsFalse() {
        assertThat(BLUEBELL.isActive(LocalDate.of(2026, 5, 19))).isFalse();
    }

    @Test
    @DisplayName("Window excludes a date well outside season")
    void isActive_winterDate_returnsFalse() {
        assertThat(BLUEBELL.isActive(LocalDate.of(2026, 12, 1))).isFalse();
    }

    @Test
    @DisplayName("Window retains its name")
    void name_isBLUEBELL() {
        assertThat(BLUEBELL.name()).isEqualTo("BLUEBELL");
    }

    @Test
    @DisplayName("Window works in different years")
    void isActive_differentYear_worksCorrectly() {
        assertThat(BLUEBELL.isActive(LocalDate.of(2027, 4, 30))).isTrue();
        assertThat(BLUEBELL.isActive(LocalDate.of(2027, 5, 20))).isFalse();
    }
}
