package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.service.EvaluationViewService.LatestDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fold from what the pipeline recorded to a {@link CoverageReason}, one row of the plan's
 * table at a time (docs/engineering/tide-facts-followups-plan.md section A.2).
 */
class CoverageReasonsTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 14);
    private static final TargetType TYPE = TargetType.SUNSET;

    private static BriefingSlot slot(String name) {
        return new BriefingSlot(name, LocalDateTime.of(DATE, java.time.LocalTime.of(18, 0)),
                com.gregochr.goldenhour.model.Verdict.GO, null,
                BriefingSlot.TideInfo.NONE, List.of());
    }

    private static List<BriefingSlot> slots(int n) {
        List<BriefingSlot> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(slot("Loc" + i));
        }
        return list;
    }

    /** A lookup holding the given categories, in order, against Loc0, Loc1, and so on. */
    private static Map<String, LatestDisposition> latest(Object... categoriesAndEnded) {
        Map<String, LatestDisposition> map = new HashMap<>();
        for (int i = 0; i < categoriesAndEnded.length; i++) {
            Object entry = categoriesAndEnded[i];
            if (entry == null) {
                continue;
            }
            LatestDisposition value = entry instanceof LatestDisposition ld ? ld
                    : new LatestDisposition((DispositionCategory) entry, false);
            map.put(EvaluationViewService.stabilitySkipKey("Loc" + i, DATE, TYPE), value);
        }
        return map;
    }

    private static Object[] repeat(Object value, int n) {
        Object[] out = new Object[n];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static CoverageReason fold(int n, int daysAhead, Map<String, LatestDisposition> map) {
        return CoverageReasons.of(slots(n), DATE, TYPE, daysAhead, map);
    }

    private static LatestDisposition evaluated(boolean ended) {
        return new LatestDisposition(DispositionCategory.EVALUATED, ended);
    }

    @Test
    @DisplayName("rule 1: every slot a travel day is AWAY, at any horizon")
    void allTravelDay_isAway() {
        Map<String, LatestDisposition> map =
                latest(repeat(DispositionCategory.SKIPPED_TRAVEL_DAY, 3));

        assertThat(fold(3, 1, map)).isEqualTo(CoverageReason.AWAY);
        assertThat(fold(3, 5, map)).as("AWAY is checked before the horizon rule")
                .isEqualTo(CoverageReason.AWAY);
    }

    @Test
    @DisplayName("a partial travel day is UNKNOWN")
    void partialTravelDay_isUnknown() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SKIPPED_TRAVEL_DAY,
                DispositionCategory.SKIPPED_TRAVEL_DAY, DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);

        assertThat(fold(3, 1, map)).isEqualTo(CoverageReason.UNKNOWN);
    }

    @Test
    @DisplayName("rule 2: T+4 with no submission is NOT_ATTEMPTED, whatever the triage mix")
    void tPlusFour_isNotAttempted() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SKIPPED_TRIAGED,
                DispositionCategory.SKIPPED_STABILITY, DispositionCategory.SKIPPED_TRIAGED);

        assertThat(fold(3, 4, map)).isEqualTo(CoverageReason.NOT_ATTEMPTED);
        assertThat(fold(3, 4, Map.of())).as("nothing recorded at T+4: the horizon alone is true")
                .isEqualTo(CoverageReason.NOT_ATTEMPTED);
    }

    @Test
    @DisplayName("rule 2 excludes EVALUATED, FORCE_EVALUATED and SUBMISSION_FAILED at T+4: UNKNOWN, "
            + "never NOT_ATTEMPTED and never FAILED (rule 3 stops at T+3)")
    void tPlusFourWithASubmission_isUnknown() {
        for (DispositionCategory category : List.of(DispositionCategory.SUBMISSION_FAILED,
                DispositionCategory.FORCE_EVALUATED, DispositionCategory.EVALUATED)) {
            Map<String, LatestDisposition> map = latest(category,
                    DispositionCategory.SKIPPED_STABILITY, DispositionCategory.SKIPPED_STABILITY);

            assertThat(fold(3, 4, map)).as(category.name()).isEqualTo(CoverageReason.UNKNOWN);
        }
        assertThat(fold(2, 4, latest(evaluated(true), evaluated(true))))
                .isEqualTo(CoverageReason.UNKNOWN);
    }

    @Test
    @DisplayName("T+4: a minority of SKIPPED_ERROR (one among 20 skips) is still NOT_ATTEMPTED")
    void tPlusFourMinorityErrors_isNotAttempted() {
        Object[] record = concat(new Object[] {DispositionCategory.SKIPPED_ERROR},
                repeat(DispositionCategory.SKIPPED_STABILITY, 19));

        assertThat(fold(20, 4, latest(record))).isEqualTo(CoverageReason.NOT_ATTEMPTED);
    }

    @Test
    @DisplayName("T+4: errors at half or more are UNKNOWN, not a horizon skip and not FAILED")
    void tPlusFourHalfErrors_isUnknown() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SKIPPED_ERROR,
                DispositionCategory.SKIPPED_STABILITY);

        assertThat(fold(2, 4, map)).isEqualTo(CoverageReason.UNKNOWN);
    }

    @Test
    @DisplayName("T+3 mix, stability the majority: NOT_ATTEMPTED (triage runs before Gate 4, so "
            + "a mix is the common case)")
    void mixStabilityMajority_isNotAttempted() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SKIPPED_STABILITY,
                DispositionCategory.SKIPPED_STABILITY, DispositionCategory.SKIPPED_TRIAGED);

        assertThat(fold(3, 3, map)).isEqualTo(CoverageReason.NOT_ATTEMPTED);
        assertThat(fold(3, 2, map)).isEqualTo(CoverageReason.NOT_ATTEMPTED);
    }

    @Test
    @DisplayName("T+3 mix, triage the majority: STOOD_DOWN")
    void mixTriageMajority_isStoodDown() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SKIPPED_TRIAGED,
                DispositionCategory.SKIPPED_STABILITY, DispositionCategory.SKIPPED_TRIAGED);

        assertThat(fold(3, 3, map)).isEqualTo(CoverageReason.STOOD_DOWN);
        assertThat(fold(3, 0, map)).isEqualTo(CoverageReason.STOOD_DOWN);
    }

    @Test
    @DisplayName("T+3 mix, a tie goes to NOT_ATTEMPTED: claiming poor weather needs a majority")
    void mixTie_isNotAttempted() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SKIPPED_TRIAGED,
                DispositionCategory.SKIPPED_STABILITY);

        assertThat(fold(2, 3, map)).isEqualTo(CoverageReason.NOT_ATTEMPTED);
    }

    @Test
    @DisplayName("a mix with one error among many still follows the majority rule")
    void mixWithMinorityError_followsTheMajority() {
        Object[] triageMajority = concat(new Object[] {DispositionCategory.SKIPPED_ERROR},
                concat(repeat(DispositionCategory.SKIPPED_TRIAGED, 6),
                        repeat(DispositionCategory.SKIPPED_STABILITY, 3)));
        Object[] stabilityMajority = concat(new Object[] {DispositionCategory.SKIPPED_ERROR},
                concat(repeat(DispositionCategory.SKIPPED_TRIAGED, 3),
                        repeat(DispositionCategory.SKIPPED_STABILITY, 6)));

        assertThat(fold(10, 3, latest(triageMajority))).isEqualTo(CoverageReason.STOOD_DOWN);
        assertThat(fold(10, 3, latest(stabilityMajority)))
                .isEqualTo(CoverageReason.NOT_ATTEMPTED);
    }

    @Test
    @DisplayName("T+3 errors at half or more with no submission are FAILED (errors count as "
            + "failed)")
    void halfErrorsWithNoSubmission_isFailed() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SKIPPED_ERROR,
                DispositionCategory.SKIPPED_STABILITY);

        assertThat(fold(2, 3, map)).isEqualTo(CoverageReason.FAILED);
    }

    @Test
    @DisplayName("rule 2's horizon is 4: at T+3 an EVALUATED-free record is judged by the mix "
            + "rule, not NOT_ATTEMPTED by horizon")
    void tPlusThreeIsNotTheHorizonRule() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SKIPPED_TRIAGED,
                DispositionCategory.SKIPPED_TRIAGED, DispositionCategory.SKIPPED_STABILITY);

        assertThat(fold(3, 3, map)).isEqualTo(CoverageReason.STOOD_DOWN);
    }

    @Test
    @DisplayName("rule 3: every slot stability-skipped at T+3 or earlier is NOT_ATTEMPTED")
    void allStabilitySkipped_isNotAttempted() {
        Map<String, LatestDisposition> map =
                latest(repeat(DispositionCategory.SKIPPED_STABILITY, 3));

        assertThat(fold(3, 3, map)).isEqualTo(CoverageReason.NOT_ATTEMPTED);
        assertThat(fold(3, 2, map)).isEqualTo(CoverageReason.NOT_ATTEMPTED);
    }

    @Test
    @DisplayName("rule 4: every slot triaged is STOOD_DOWN")
    void allTriaged_isStoodDown() {
        assertThat(fold(3, 1, latest(repeat(DispositionCategory.SKIPPED_TRIAGED, 3))))
                .isEqualTo(CoverageReason.STOOD_DOWN);
    }

    @Test
    @DisplayName("rule 5: half or more failed at T+3 or earlier is FAILED")
    void halfFailed_isFailed() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SUBMISSION_FAILED,
                DispositionCategory.SKIPPED_ERROR, DispositionCategory.SKIPPED_STABILITY,
                DispositionCategory.SKIPPED_STABILITY);

        assertThat(fold(4, 3, map)).isEqualTo(CoverageReason.FAILED);
    }

    @Test
    @DisplayName("one SKIPPED_ERROR among 19 stability skips at T+3 is NOT_ATTEMPTED: it was "
            + "UNKNOWN before the majority rule, but an error minority no longer hides that "
            + "stability skipped the rest")
    void oneErrorAmongNineteenStability_isNotAttempted() {
        Object[] record = concat(new Object[] {DispositionCategory.SKIPPED_ERROR},
                repeat(DispositionCategory.SKIPPED_STABILITY, 19));

        assertThat(fold(20, 3, latest(record))).isEqualTo(CoverageReason.NOT_ATTEMPTED);
    }

    @Test
    @DisplayName("one failure alone does not make a region FAILED")
    void singleFailureOfThree_isUnknown() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SUBMISSION_FAILED,
                DispositionCategory.SKIPPED_STABILITY, DispositionCategory.SKIPPED_STABILITY);

        assertThat(fold(3, 2, map)).isEqualTo(CoverageReason.UNKNOWN);
    }

    @Test
    @DisplayName("EVALUATED slots whose cycle has ended are FAILED; still going they are UNKNOWN")
    void evaluatedReadsByWhetherTheCycleEnded() {
        assertThat(fold(3, 2, latest(repeat(evaluated(true), 3))))
                .isEqualTo(CoverageReason.FAILED);
        assertThat(fold(3, 2, latest(repeat(evaluated(false), 3))))
                .isEqualTo(CoverageReason.UNKNOWN);
    }

    @Test
    @DisplayName("SUBMISSION_FAILED mixed with ended-EVALUATED slots to half or more is FAILED")
    void failedMixedWithEndedEvaluated_isFailed() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SUBMISSION_FAILED,
                evaluated(true), evaluated(false), DispositionCategory.SKIPPED_STABILITY);

        assertThat(fold(4, 1, map)).isEqualTo(CoverageReason.FAILED);
    }

    @Test
    @DisplayName("UNKNOWN: a slot with no record at T+0 to T+3")
    void missingRecord_isUnknown() {
        Map<String, LatestDisposition> map = latest(DispositionCategory.SKIPPED_TRIAGED,
                DispositionCategory.SKIPPED_TRIAGED, null);

        assertThat(fold(3, 0, map)).isEqualTo(CoverageReason.UNKNOWN);
        assertThat(fold(3, 0, Map.of())).as("a failed lookup is an empty map")
                .isEqualTo(CoverageReason.UNKNOWN);
    }

    @Test
    @DisplayName("UNKNOWN: SKIPPED_NO_REFRESH_NEEDED, and a location renamed since its record")
    void otherUnsettledRecords_areUnknown() {
        assertThat(fold(3, 1, latest(repeat(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED, 3))))
                .isEqualTo(CoverageReason.UNKNOWN);
        Map<String, LatestDisposition> renamed = new HashMap<>();
        renamed.put(EvaluationViewService.stabilitySkipKey("Old name", DATE, TYPE),
                new LatestDisposition(DispositionCategory.SKIPPED_TRIAGED, false));
        assertThat(fold(1, 1, renamed)).isEqualTo(CoverageReason.UNKNOWN);
    }

    @Test
    @DisplayName("canopy slots that cannot carry a rating are not part of the fold's input")
    void canopySlotsAreIgnoredByTheCaller() {
        // The filter hands the fold only couldCarryRating slots; the fold looks up exactly the
        // slots it is given, so a canopy slot with a contrary record cannot change the answer.
        List<BriefingSlot> scoreable = List.of(slot("Loc0"));
        Map<String, LatestDisposition> map = latest(DispositionCategory.SKIPPED_TRIAGED);
        map.put(EvaluationViewService.stabilitySkipKey("Wood", DATE, TYPE),
                new LatestDisposition(DispositionCategory.SUBMISSION_FAILED, false));

        assertThat(CoverageReasons.of(scoreable, DATE, TYPE, 1, map))
                .isEqualTo(CoverageReason.STOOD_DOWN);
        assertThat(BriefingSlot.canopySlot("Wood", LocalDateTime.of(DATE, java.time.LocalTime.NOON),
                com.gregochr.goldenhour.model.Verdict.GO, null, List.of())
                .couldCarryRating()).isFalse();
    }

    @Test
    @DisplayName("no slots, or no lookup, is UNKNOWN")
    void emptyInputs_areUnknown() {
        assertThat(CoverageReasons.of(List.of(), DATE, TYPE, 1, Map.of()))
                .isEqualTo(CoverageReason.UNKNOWN);
        assertThat(CoverageReasons.of(slots(2), DATE, TYPE, 1, null))
                .isEqualTo(CoverageReason.UNKNOWN);
    }
}
