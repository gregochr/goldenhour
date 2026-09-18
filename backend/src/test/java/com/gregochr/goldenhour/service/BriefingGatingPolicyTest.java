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
 * Unit tests for the Gate 2 gating policy.
 *
 * <p>⚠️ <b>Rewritten for the tide gate lift</b> (2026-09-18, {@code
 * docs/engineering/tide-window-plan.md} §6 Q1): {@code TIDE_MISMATCH} was the one member of
 * {@link BriefingGatingPolicy#isHardConstraintSkip}'s hard-constraint set, and the owner lifted
 * it — a mismatched tide now reaches Claude and scores through {@code
 * service.evaluation.visitor.TideVisitor} instead of being withheld. {@code
 * BriefingGatingPolicy.HARD_CONSTRAINT_REASONS} is therefore empty, and this class verifies:
 *
 * <ul>
 *   <li>GO and MARGINAL slots are always eligible.</li>
 *   <li>Every {@link StanddownReason} — {@code TIDE_MISMATCH} included — is now eligible,
 *       because the set it used to be tested against is empty.</li>
 *   <li>{@code isHardConstraintSkip}/{@code hardConstraintReason} answer "not a hard
 *       constraint"/empty for every reason, {@code TIDE_MISMATCH} included.</li>
 *   <li>The label round-trip guard still holds — every {@link StanddownReason} value's label is
 *       still recognised by the policy, so a future member added back to the set would decode
 *       correctly rather than silently falling through the "unrecognised label" safe default.</li>
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

        @ParameterizedTest(name = "STANDDOWN + {0} is eligible — HARD_CONSTRAINT_REASONS is empty")
        @EnumSource(StanddownReason.class)
        void everyStanddownReason_isEligible(StanddownReason reason) {
            BriefingSlot slot = slot(Verdict.STANDDOWN, reason.label());
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isTrue();
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
        @DisplayName("An all-TIDE_MISMATCH region still has eligible slots — the tide gate lift "
                + "means a tide mismatch is no longer a hard constraint")
        void allTideMismatch_returnsTrue() {
            BriefingRegion region = region(
                    slot(Verdict.STANDDOWN, StanddownReason.TIDE_MISMATCH.label()),
                    slot(Verdict.STANDDOWN, StanddownReason.TIDE_MISMATCH.label()));
            assertThat(BriefingGatingPolicy.hasAnyEligibleSlot(region)).isTrue();
        }

        @Test
        @DisplayName("A mixed region with any STANDDOWN slot has eligible slots")
        void mixedStanddown_returnsTrue() {
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
    @DisplayName("isHardConstraintSkip — always false today (HARD_CONSTRAINT_REASONS is empty)")
    class IsHardConstraintSkip {

        @ParameterizedTest(name = "STANDDOWN + {0} is NOT a hard-constraint skip")
        @EnumSource(StanddownReason.class)
        void everyStanddownReason_isNotHardConstraint(StanddownReason reason) {
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

    @Nested
    @DisplayName("hardConstraintReason — empty for every slot today, tide included")
    class HardConstraintReason {

        @Test
        @DisplayName("a tide mismatch decodes to nothing — it reaches Claude")
        void tideMismatch_decodesToNothing() {
            BriefingSlot slot = slot(Verdict.STANDDOWN, StanddownReason.TIDE_MISMATCH.label());
            assertThat(BriefingGatingPolicy.hardConstraintReason(slot)).isEmpty();
        }

        @ParameterizedTest(name = "STANDDOWN + {0} decodes to nothing — it reaches Claude")
        @EnumSource(StanddownReason.class)
        void everyStanddownReason_decodesToNothing(StanddownReason reason) {
            assertThat(BriefingGatingPolicy.hardConstraintReason(
                    slot(Verdict.STANDDOWN, reason.label()))).isEmpty();
        }

        @Test
        @DisplayName("GO, a null label and an unknown label all decode to nothing — the same safe default")
        void nonGated_decodesToNothing() {
            assertThat(BriefingGatingPolicy.hardConstraintReason(slot(Verdict.GO, null))).isEmpty();
            assertThat(BriefingGatingPolicy.hardConstraintReason(slot(Verdict.STANDDOWN, null)))
                    .isEmpty();
            assertThat(BriefingGatingPolicy.hardConstraintReason(
                    slot(Verdict.STANDDOWN, "Some new reason we have not seen"))).isEmpty();
        }

        @Test
        @DisplayName("isHardConstraintSkip is exactly 'a reason decodes' — one rule, two shapes")
        void booleanForm_agreesWithDecodedForm() {
            for (StanddownReason reason : StanddownReason.values()) {
                BriefingSlot slot = slot(Verdict.STANDDOWN, reason.label());
                assertThat(BriefingGatingPolicy.isHardConstraintSkip(slot))
                        .as(reason.name())
                        .isEqualTo(BriefingGatingPolicy.hardConstraintReason(slot).isPresent());
            }
        }
    }

    @Test
    @DisplayName("Every StanddownReason label is recognised by the policy — the round-trip guard "
            + "survives HARD_CONSTRAINT_REASONS going empty")
    void allStanddownReasonLabels_areRecognised() {
        // If a label drifts, a STANDDOWN slot would silently start passing through as "unknown
        // reason → eligible" — the safe default, but one that hides the reason in logs/metrics.
        // This guard fails before that drifts.
        //
        // ⚠️ Asserted through decodeLabel directly, NOT through isEligibleForEvaluation. With
        // HARD_CONSTRAINT_REASONS empty (the tide gate lift), eligibility answers `true` whether a
        // label decodes correctly or fails to decode — the two paths converge on the same output,
        // so testing the decode through eligibility would silently stop catching label drift the
        // moment the set went empty (found in adversarial review: the prior version of this test
        // could not fail even for a corrupted REASON_BY_LABEL, because no assertion here depended
        // on which path produced `true`).
        for (StanddownReason reason : StanddownReason.values()) {
            assertThat(BriefingGatingPolicy.decodeLabel(reason.label()))
                    .as("decode for %s ('%s')", reason.name(), reason.label())
                    .contains(reason);
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
