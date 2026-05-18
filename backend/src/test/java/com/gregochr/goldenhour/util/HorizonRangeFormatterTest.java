package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HorizonRangeFormatter}.
 */
class HorizonRangeFormatterTest {

    @Test
    @DisplayName("Empty input returns empty string")
    void emptyInput_returnsEmptyString() {
        assertThat(HorizonRangeFormatter.format(Set.of())).isEqualTo("");
        assertThat(HorizonRangeFormatter.format(null)).isEqualTo("");
    }

    @Test
    @DisplayName("Single day-zero offset renders as 'T'")
    void singleZeroOffset_rendersAsT() {
        assertThat(HorizonRangeFormatter.format(Set.of(0))).isEqualTo("T");
    }

    @Test
    @DisplayName("Single positive offset renders as 'T+N'")
    void singlePositiveOffset_rendersAsTPlusN() {
        assertThat(HorizonRangeFormatter.format(Set.of(1))).isEqualTo("T+1");
        assertThat(HorizonRangeFormatter.format(Set.of(3))).isEqualTo("T+3");
    }

    @Test
    @DisplayName("Single negative offset renders as 'T-N'")
    void singleNegativeOffset_rendersAsTMinusN() {
        assertThat(HorizonRangeFormatter.format(Set.of(-1))).isEqualTo("T-1");
    }

    @Test
    @DisplayName("Contiguous range renders as 'T to T+N'")
    void contiguousRange_rendersAsRange() {
        assertThat(HorizonRangeFormatter.format(List.of(0, 1, 2))).isEqualTo("T to T+2");
        assertThat(HorizonRangeFormatter.format(List.of(1, 2, 3))).isEqualTo("T+1 to T+3");
    }

    @Test
    @DisplayName("Contiguous two-element range renders as 'T+1 to T+2'")
    void twoContiguousElements_rendersAsRange() {
        assertThat(HorizonRangeFormatter.format(List.of(1, 2))).isEqualTo("T+1 to T+2");
    }

    @Test
    @DisplayName("Non-contiguous offsets render as comma-separated list")
    void nonContiguous_rendersAsList() {
        assertThat(HorizonRangeFormatter.format(List.of(0, 2))).isEqualTo("T, T+2");
        assertThat(HorizonRangeFormatter.format(List.of(0, 2, 5))).isEqualTo("T, T+2, T+5");
    }

    @Test
    @DisplayName("Duplicates collapse and order is ascending")
    void duplicates_collapseAndSort() {
        assertThat(HorizonRangeFormatter.format(List.of(2, 1, 1, 0))).isEqualTo("T to T+2");
        assertThat(HorizonRangeFormatter.format(List.of(2, 0, 2))).isEqualTo("T, T+2");
    }
}
