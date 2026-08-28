package com.gregochr.goldenhour.model.comingup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ComingUpCondition}. */
class ComingUpConditionTest {

    private static final ComingUpConditionPeak PEAK =
            new ComingUpConditionPeak("Thu 26 Nov", "5.2 m", 9.0);
    private static final ComingUpConditionOccurrence OCCURRENCE = new ComingUpConditionOccurrence(
            java.time.LocalDate.of(2026, 8, 30), "30 Aug", "4.8 m", 5.4, null, "insidePlan", null);

    @Test
    @DisplayName("carries every field, including a nullable peak")
    void carriesEveryField() {
        ComingUpCondition condition = new ComingUpCondition(
                "COASTAL_TIDES", "Coastal tides", "deterministic", false,
                "a run every 14.8 days", "rarity 3.9", PEAK, List.of(OCCURRENCE));

        assertThat(condition.type()).isEqualTo("COASTAL_TIDES");
        assertThat(condition.name()).isEqualTo("Coastal tides");
        assertThat(condition.cadence()).isEqualTo("deterministic");
        assertThat(condition.interim()).isFalse();
        assertThat(condition.rateLabel()).isEqualTo("a run every 14.8 days");
        assertThat(condition.quantLabel()).isEqualTo("rarity 3.9");
        assertThat(condition.peak()).isEqualTo(PEAK);
        assertThat(condition.occurrences()).containsExactly(OCCURRENCE);
    }

    @Test
    @DisplayName("a null peak means no occurrence passed the gate, and a null occurrences list "
            + "normalises to empty")
    void nullPeakAndOccurrencesAreHandled() {
        ComingUpCondition condition = new ComingUpCondition(
                "DUST", "Saharan dust", "recurrent", true, "3 plumes since 12 Aug", null, null, null);

        assertThat(condition.peak()).isNull();
        assertThat(condition.occurrences()).isEmpty();
        assertThat(condition.interim()).isTrue();
    }
}
