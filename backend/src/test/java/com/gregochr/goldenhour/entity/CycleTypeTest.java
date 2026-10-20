package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.entity.CycleType.FailureEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins each {@link CycleType} constant's answer to the properties the orchestrator reads.
 */
class CycleTypeTest {

    @Test
    @DisplayName("NIGHTLY publishes the stability snapshot, records no reclassify phase, counts failures")
    void nightlyProperties() {
        assertThat(CycleType.NIGHTLY.publishesStabilitySnapshot()).isTrue();
        assertThat(CycleType.NIGHTLY.recordsReclassifyPhase()).isFalse();
        assertThat(CycleType.NIGHTLY.failureEvidence()).isEqualTo(FailureEvidence.COUNTED);
    }

    @Test
    @DisplayName("INTRADAY publishes no snapshot, records the reclassify phase, counts failures")
    void intradayProperties() {
        assertThat(CycleType.INTRADAY.publishesStabilitySnapshot()).isFalse();
        assertThat(CycleType.INTRADAY.recordsReclassifyPhase()).isTrue();
        assertThat(CycleType.INTRADAY.failureEvidence()).isEqualTo(FailureEvidence.COUNTED);
    }
}
