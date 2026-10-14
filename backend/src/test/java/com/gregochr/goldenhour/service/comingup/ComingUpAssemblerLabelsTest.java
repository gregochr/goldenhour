package com.gregochr.goldenhour.service.comingup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure wording helpers of {@link ComingUpAssembler} (supermoon-tide-run-plan.md §2.2). */
class ComingUpAssemblerLabelsTest {

    private static final String NB = " ";

    @Test
    @DisplayName("ordinals follow English: 1st 2nd 3rd 4th, the teens are all th, 21st 22nd 23rd, 31st")
    void ordinals() {
        assertThat(IntStream.of(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31)
                .mapToObj(ComingUpAssembler::ordinal))
                .containsExactly("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd",
                        "23rd", "31st");
    }

    @Test
    @DisplayName("span labels: one day, same month, crossing a month — non-breaking inside each date, "
            + "a normal space only around the en dash")
    void spanLabels() {
        assertThat(ComingUpAssembler.spanLabel(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 26)))
                .isEqualTo("Mon" + NB + "26" + NB + "Oct");
        assertThat(ComingUpAssembler.spanLabel(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 30)))
                .isEqualTo("Sun" + NB + "25 – Fri" + NB + "30" + NB + "Oct");
        assertThat(ComingUpAssembler.spanLabel(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 3)))
                .isEqualTo("Fri" + NB + "30" + NB + "Oct – Tue" + NB + "3" + NB + "Nov");
    }
}
