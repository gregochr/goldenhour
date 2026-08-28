package com.gregochr.goldenhour.model.comingup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ComingUpBands}. */
class ComingUpBandsTest {

    @Test
    @DisplayName("carries the three band edges verbatim")
    void carriesTheThreeEdges() {
        ComingUpBands bands = new ComingUpBands(5.0, 7.5, 9.5);

        assertThat(bands.list()).isEqualTo(5.0);
        assertThat(bands.announce()).isEqualTo(7.5);
        assertThat(bands.interrupt()).isEqualTo(9.5);
    }
}
