package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TriageResult} — the convenience constructor that auto-derives
 * {@code triageReason} from {@code rule}. Locks down the invariant that a TriageResult
 * constructed from a rule always carries a matching, non-null enum; this protects every
 * upstream call-site (WeatherTriageEvaluator, TideAlignmentEvaluator) without having to
 * duplicate the assertion in each of their tests.
 */
class TriageResultTest {

    @Test
    @DisplayName("2-arg constructor derives triageReason from rule — HIGH_CLOUD")
    void twoArgCtor_deriveHighCloud() {
        TriageResult r = new TriageResult("Low cloud cover 85%", TriageRule.HIGH_CLOUD);
        assertThat(r.reason()).isEqualTo("Low cloud cover 85%");
        assertThat(r.rule()).isEqualTo(TriageRule.HIGH_CLOUD);
        assertThat(r.triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
    }

    @Test
    @DisplayName("2-arg constructor derives triageReason from rule — PRECIPITATION")
    void twoArgCtor_derivePrecipitation() {
        TriageResult r = new TriageResult("Precipitation 3.2 mm", TriageRule.PRECIPITATION);
        assertThat(r.rule()).isEqualTo(TriageRule.PRECIPITATION);
        assertThat(r.triageReason()).isEqualTo(TriageReason.PRECIPITATION);
    }

    @Test
    @DisplayName("2-arg constructor derives triageReason from rule — LOW_VISIBILITY")
    void twoArgCtor_deriveLowVisibility() {
        TriageResult r = new TriageResult("Visibility 3200 m", TriageRule.LOW_VISIBILITY);
        assertThat(r.rule()).isEqualTo(TriageRule.LOW_VISIBILITY);
        assertThat(r.triageReason()).isEqualTo(TriageReason.LOW_VISIBILITY);
    }

    @Test
    @DisplayName("2-arg constructor derives triageReason from rule — TIDE_MISALIGNED")
    void twoArgCtor_deriveTideMisaligned() {
        TriageResult r = new TriageResult("No high tide in window", TriageRule.TIDE_MISALIGNED);
        assertThat(r.rule()).isEqualTo(TriageRule.TIDE_MISALIGNED);
        assertThat(r.triageReason()).isEqualTo(TriageReason.TIDE_MISALIGNED);
    }

    @Test
    @DisplayName("3-arg constructor preserves an explicitly-supplied triageReason (even if mismatched)")
    void threeArgCtor_preservesExplicitReason() {
        TriageResult r = new TriageResult(
                "Sentinel skip — region poor",
                null,
                TriageReason.GENERIC);
        assertThat(r.rule()).isNull();
        assertThat(r.triageReason()).isEqualTo(TriageReason.GENERIC);
    }
}
