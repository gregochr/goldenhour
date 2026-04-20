package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TriageReason}.
 *
 * <p>Exercises every branch of {@link TriageReason#fromRule(TriageRule)} so a mutation that
 * collapses the switch into a single return value or re-wires any arm to a different reason
 * will be caught.
 */
class TriageReasonTest {

    @Test
    @DisplayName("fromRule(HIGH_CLOUD) → HIGH_CLOUD")
    void fromRule_highCloud() {
        assertThat(TriageReason.fromRule(TriageRule.HIGH_CLOUD)).isEqualTo(TriageReason.HIGH_CLOUD);
    }

    @Test
    @DisplayName("fromRule(PRECIPITATION) → PRECIPITATION")
    void fromRule_precipitation() {
        assertThat(TriageReason.fromRule(TriageRule.PRECIPITATION)).isEqualTo(TriageReason.PRECIPITATION);
    }

    @Test
    @DisplayName("fromRule(LOW_VISIBILITY) → LOW_VISIBILITY")
    void fromRule_lowVisibility() {
        assertThat(TriageReason.fromRule(TriageRule.LOW_VISIBILITY)).isEqualTo(TriageReason.LOW_VISIBILITY);
    }

    @Test
    @DisplayName("fromRule(TIDE_MISALIGNED) → TIDE_MISALIGNED")
    void fromRule_tideMisaligned() {
        assertThat(TriageReason.fromRule(TriageRule.TIDE_MISALIGNED)).isEqualTo(TriageReason.TIDE_MISALIGNED);
    }

    @Test
    @DisplayName("fromRule(null) → GENERIC (used for sentinel/region skips with no specific rule)")
    void fromRule_null_returnsGeneric() {
        assertThat(TriageReason.fromRule(null)).isEqualTo(TriageReason.GENERIC);
    }

    @Test
    @DisplayName("Every TriageRule maps to a non-null, non-GENERIC reason")
    void everyRuleMaps_toNonGenericReason() {
        for (TriageRule rule : TriageRule.values()) {
            TriageReason reason = TriageReason.fromRule(rule);
            assertThat(reason)
                    .as("rule %s should map to a specific reason", rule)
                    .isNotNull()
                    .isNotEqualTo(TriageReason.GENERIC);
        }
    }
}
