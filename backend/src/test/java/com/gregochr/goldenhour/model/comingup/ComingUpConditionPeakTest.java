package com.gregochr.goldenhour.model.comingup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ComingUpConditionPeak}. */
class ComingUpConditionPeakTest {

    @Test
    @DisplayName("carries the peak's formatted date, value and bits verbatim")
    void carriesThePeak() {
        ComingUpConditionPeak peak = new ComingUpConditionPeak("Thu 26 Nov", "5.2 m", 9.0);

        assertThat(peak.dateLabel()).isEqualTo("Thu 26 Nov");
        assertThat(peak.valueLabel()).isEqualTo("5.2 m");
        assertThat(peak.bits()).isEqualTo(9.0);
    }
}
