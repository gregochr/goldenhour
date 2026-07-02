package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DayLabels}.
 */
class DayLabelsTest {

    // 2026-06-17 is a Wednesday; +2 = Friday, +3 = Saturday.
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);

    @Test
    @DisplayName("relative labels today, tomorrow and weekday")
    void relative_labels() {
        assertThat(DayLabels.relative(TODAY, TODAY)).isEqualTo("today");
        assertThat(DayLabels.relative(TODAY.plusDays(1), TODAY)).isEqualTo("tomorrow");
        assertThat(DayLabels.relative(TODAY.plusDays(2), TODAY)).isEqualTo("Friday");
    }

    @Test
    @DisplayName("joinRelative: one, two and three-plus dates")
    void joinRelative_naturalList() {
        assertThat(DayLabels.joinRelative(List.of(TODAY), TODAY))
                .isEqualTo("today");
        assertThat(DayLabels.joinRelative(List.of(TODAY, TODAY.plusDays(1)), TODAY))
                .isEqualTo("today and tomorrow");
        assertThat(DayLabels.joinRelative(
                List.of(TODAY, TODAY.plusDays(1), TODAY.plusDays(3)), TODAY))
                .isEqualTo("today, tomorrow and Saturday");
    }
}
