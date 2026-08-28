package com.gregochr.goldenhour.model.comingup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ComingUpCounts}. */
class ComingUpCountsTest {

    @Test
    @DisplayName("carries the fixed/forecast totals and the per-family breakdown")
    void carriesTheCounts() {
        ComingUpCounts counts = new ComingUpCounts(8, 1, Map.of("coastal", 4, "sun-moon", 2));

        assertThat(counts.fixed()).isEqualTo(8);
        assertThat(counts.forecast()).isEqualTo(1);
        assertThat(counts.byFamily()).containsEntry("coastal", 4).containsEntry("sun-moon", 2);
    }

    @Test
    @DisplayName("a null byFamily normalises to empty so a consumer never has to null-check it")
    void nullByFamilyBecomesEmpty() {
        ComingUpCounts counts = new ComingUpCounts(0, 0, null);

        assertThat(counts.byFamily()).isEmpty();
    }
}
