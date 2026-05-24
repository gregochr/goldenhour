package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingVerdictEvaluator.StanddownReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Gate 2 gating policy. Verifies that:
 *
 * <ul>
 *   <li>GO and MARGINAL slots are always eligible.</li>
 *   <li>Weather-condition STANDDOWN reasons are eligible (the Gate 2 redesign).</li>
 *   <li>Only the hard-constraint reason {@code TIDE_MISMATCH} is ineligible.</li>
 *   <li>Every {@link StanddownReason} value's label is recognised by the policy — a
 *       guard against silent label-drift breaking the gate.</li>
 * </ul>
 */
class BriefingGatingPolicyTest {

    private static final LocalDateTime TIME =
            LocalDateTime.of(LocalDate.of(2026, 5, 23), LocalTime.of(18, 0));

    @Nested
    @DisplayName("isEligibleForEvaluation")
    class IsEligibleForEvaluation {

        @Test
        @DisplayName("GO slot is eligible regardless of reason")
        void goSlot_isEligible() {
            BriefingSlot slot = slot(Verdict.GO, null);
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isTrue();
        }

        @Test
        @DisplayName("MARGINAL slot is eligible regardless of reason")
        void marginalSlot_isEligible() {
            BriefingSlot slot = slot(Verdict.MARGINAL, null);
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isTrue();
        }

        @ParameterizedTest(name = "STANDDOWN + {0} is eligible (weather condition)")
        @EnumSource(value = StanddownReason.class, names = {
                "HEAVY_CLOUD", "OVERCAST", "RAIN", "POOR_VISIBILITY",
                "BUILDING_CLOUD", "SUN_BLOCKED_HORIZON", "CLEAR_SKY", "POOR_CONDITIONS"
        })
        void weatherStanddown_isEligible(StanddownReason reason) {
            BriefingSlot slot = slot(Verdict.STANDDOWN, reason.label());
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isTrue();
        }

        @Test
        @DisplayName("STANDDOWN + TIDE_MISMATCH is NOT eligible (hard constraint)")
        void tideMismatchStanddown_isNotEligible() {
            BriefingSlot slot = slot(Verdict.STANDDOWN, StanddownReason.TIDE_MISMATCH.label());
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isFalse();
        }

        @Test
        @DisplayName("STANDDOWN with null reason is eligible (safe default)")
        void standdownWithNullReason_isEligible() {
            BriefingSlot slot = slot(Verdict.STANDDOWN, null);
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isTrue();
        }

        @Test
        @DisplayName("STANDDOWN with unrecognised reason label is eligible (safe default)")
        void standdownWithUnknownLabel_isEligible() {
            BriefingSlot slot = slot(Verdict.STANDDOWN, "Some new reason we have not seen");
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isTrue();
        }
    }

    @Nested
    @DisplayName("hasAnyEligibleSlot")
    class HasAnyEligibleSlot {

        @Test
        @DisplayName("All-TIDE_MISMATCH region has no eligible slots")
        void allTideMismatch_returnsFalse() {
            BriefingRegion region = region(
                    slot(Verdict.STANDDOWN, StanddownReason.TIDE_MISMATCH.label()),
                    slot(Verdict.STANDDOWN, StanddownReason.TIDE_MISMATCH.label()));
            assertThat(BriefingGatingPolicy.hasAnyEligibleSlot(region)).isFalse();
        }

        @Test
        @DisplayName("Mixed region with at least one weather-STANDDOWN slot has eligible slots")
        void mixedWithWeatherStanddown_returnsTrue() {
            BriefingRegion region = region(
                    slot(Verdict.STANDDOWN, StanddownReason.TIDE_MISMATCH.label()),
                    slot(Verdict.STANDDOWN, StanddownReason.HEAVY_CLOUD.label()));
            assertThat(BriefingGatingPolicy.hasAnyEligibleSlot(region)).isTrue();
        }

        @Test
        @DisplayName("All-GO region has eligible slots")
        void allGo_returnsTrue() {
            BriefingRegion region = region(
                    slot(Verdict.GO, null), slot(Verdict.GO, null));
            assertThat(BriefingGatingPolicy.hasAnyEligibleSlot(region)).isTrue();
        }
    }

    @Nested
    @DisplayName("isHardConstraintSkip")
    class IsHardConstraintSkip {

        @Test
        @DisplayName("STANDDOWN + TIDE_MISMATCH is a hard-constraint skip")
        void tideMismatch_isHardConstraint() {
            BriefingSlot slot = slot(Verdict.STANDDOWN, StanddownReason.TIDE_MISMATCH.label());
            assertThat(BriefingGatingPolicy.isHardConstraintSkip(slot)).isTrue();
        }

        @ParameterizedTest(name = "STANDDOWN + {0} is NOT a hard-constraint skip")
        @EnumSource(value = StanddownReason.class, names = {
                "HEAVY_CLOUD", "OVERCAST", "RAIN", "POOR_VISIBILITY",
                "BUILDING_CLOUD", "SUN_BLOCKED_HORIZON", "CLEAR_SKY", "POOR_CONDITIONS"
        })
        void weatherStanddown_isNotHardConstraint(StanddownReason reason) {
            BriefingSlot slot = slot(Verdict.STANDDOWN, reason.label());
            assertThat(BriefingGatingPolicy.isHardConstraintSkip(slot)).isFalse();
        }

        @Test
        @DisplayName("GO slot is not a hard-constraint skip")
        void goSlot_isNotHardConstraint() {
            BriefingSlot slot = slot(Verdict.GO, null);
            assertThat(BriefingGatingPolicy.isHardConstraintSkip(slot)).isFalse();
        }

        @Test
        @DisplayName("STANDDOWN with null reason is not a hard-constraint skip")
        void standdownWithNullReason_isNotHardConstraint() {
            BriefingSlot slot = slot(Verdict.STANDDOWN, null);
            assertThat(BriefingGatingPolicy.isHardConstraintSkip(slot)).isFalse();
        }

        @Test
        @DisplayName("STANDDOWN with unrecognised reason label is not a hard-constraint skip")
        void standdownWithUnknownLabel_isNotHardConstraint() {
            BriefingSlot slot = slot(Verdict.STANDDOWN, "Some new reason we have not seen");
            assertThat(BriefingGatingPolicy.isHardConstraintSkip(slot)).isFalse();
        }
    }

    @Test
    @DisplayName("Every StanddownReason label is recognised by the policy")
    void allStanddownReasonLabels_areRecognised() {
        // If a label drifts, weather-STANDDOWN slots would silently start passing
        // through as "unknown reason → eligible" — which is the safe default but
        // hides the reason in logs/metrics. This guard fails before that drifts.
        for (StanddownReason reason : StanddownReason.values()) {
            BriefingSlot slot = slot(Verdict.STANDDOWN, reason.label());
            boolean expectedEligible = reason != StanddownReason.TIDE_MISMATCH;
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot))
                    .as("eligibility for STANDDOWN + %s ('%s')", reason.name(), reason.label())
                    .isEqualTo(expectedEligible);
        }
    }

    private static BriefingSlot slot(Verdict verdict, String standdownReason) {
        return new BriefingSlot(
                "Buttermere", TIME, verdict,
                null, BriefingSlot.TideInfo.NONE, List.of(), standdownReason);
    }

    private static BriefingRegion region(BriefingSlot... slots) {
        return new BriefingRegion(
                "Lake District", Verdict.STANDDOWN, "summary",
                List.of(), List.of(slots), null, null, null, null, null, null);
    }
}
