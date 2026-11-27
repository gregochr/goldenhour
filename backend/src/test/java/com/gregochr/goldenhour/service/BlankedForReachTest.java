package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.EvaluationViewService.LatestDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the drive tier's blank as the SAME fold the served honesty filter runs
 * (docs/engineering/unscored-reason-card-plan.md 3.1): {@code blankedForReach} over each in-reach slot's
 * {@code disposition} stamp, with the horizon, ties, missing records, failures and fog behaving as on the served path.
 */
class BlankedForReachTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 28);
    private static final TargetType TYPE = TargetType.SUNRISE;
    private static final LocalDateTime EVENT = LocalDateTime.of(DATE, LocalTime.of(7, 0));

    private static final LatestDisposition TRIAGED = new LatestDisposition(DispositionCategory.SKIPPED_TRIAGED, false);
    private static final LatestDisposition FOG =
            new LatestDisposition(DispositionCategory.SKIPPED_TRIAGED, false, true);
    private static final LatestDisposition STABILITY =
            new LatestDisposition(DispositionCategory.SKIPPED_STABILITY, false);
    private static final LatestDisposition ERROR = new LatestDisposition(DispositionCategory.SKIPPED_ERROR, false);
    private static final LatestDisposition TRAVEL =
            new LatestDisposition(DispositionCategory.SKIPPED_TRAVEL_DAY, false);

    private static BriefingSlot slot(int id, LatestDisposition disposition) {
        return new BriefingSlot((long) id, "Place " + id, EVENT, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of()).withDisposition(disposition);
    }

    /** One slot per disposition, ids 1, 2, ...; a null disposition is a slot with no record. */
    private static List<BriefingSlot> slots(LatestDisposition... dispositions) {
        List<BriefingSlot> out = new ArrayList<>();
        for (int i = 0; i < dispositions.length; i++) {
            out.add(slot(i + 1, dispositions[i]));
        }
        return out;
    }

    private static BriefingRegion region(List<BriefingSlot> inReach) {
        return new BriefingRegion("Lakes", Verdict.GO, "s", List.of(), inReach, null, null, null, null, null,
                "detail", DisplayVerdict.STAND_DOWN, 0);
    }

    private static BriefingRegion blank(int daysAhead, LatestDisposition... dispositions) {
        List<BriefingSlot> inReach = slots(dispositions);
        return BriefingHonestyFilter.blankedForReach(region(inReach), inReach, DATE, TYPE, daysAhead);
    }

    @Test
    @DisplayName("a triage majority is a weather stand-down, worded as the served filter words it")
    void triageMajorityIsStoodDown() {
        BriefingRegion blanked = blank(2, TRIAGED, TRIAGED, TRIAGED, STABILITY);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.STOOD_DOWN);
        assertThat(blanked.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(blanked.verdictLabel()).isEqualTo(BriefingHonestyFilter.VERDICT_LABEL);
        assertThat(blanked.slots()).isEmpty();
        assertThat(blanked.scoredLocationCount()).isZero();
        assertThat(blanked.fog()).isNull();
    }

    @Test
    @DisplayName("one triaged place among three stability skips is NOT_ATTEMPTED, not 'unsettled' (the Sat sunset)")
    void oneTriagedOfFourIsNotAttempted() {
        BriefingRegion blanked = blank(3, TRIAGED, STABILITY, STABILITY, STABILITY);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.NOT_ATTEMPTED);
        assertThat(blanked.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(blanked.verdictLabel()).isEqualTo(BriefingHonestyFilter.NOT_ATTEMPTED_LABEL);
    }

    @Test
    @DisplayName("a tie between triage and stability goes to NOT_ATTEMPTED: claiming poor weather needs a majority")
    void tieIsNotAttempted() {
        BriefingRegion blanked = blank(3, TRIAGED, TRIAGED, STABILITY, STABILITY);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.NOT_ATTEMPTED);
    }

    @Test
    @DisplayName("2 triaged, 1 stability and 1 error is STOOD_DOWN, as on the served path")
    void minorityErrorsDoNotChangeTheMajority() {
        BriefingRegion blanked = blank(3, TRIAGED, TRIAGED, STABILITY, ERROR);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.STOOD_DOWN);
    }

    @Test
    @DisplayName("3 triaged with 2 places that have no record is UNKNOWN: a record that cannot say is never weather")
    void missingRecordsAreUnknown() {
        BriefingRegion blanked = blank(2, TRIAGED, TRIAGED, TRIAGED, null, null);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.UNKNOWN);
        assertThat(blanked.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(blanked.verdictLabel()).isEqualTo(BriefingHonestyFilter.UNKNOWN_LABEL);
    }

    @Test
    @DisplayName("a T+1 blank with no record is UNKNOWN, not 'not scored at this range': Gate 4 scores T+1")
    void tPlusOneWithoutRecordIsUnknown() {
        BriefingRegion blanked = blank(1, null, null);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.UNKNOWN);
    }

    @Test
    @DisplayName("from T+4 the horizon alone decides: NOT_ATTEMPTED, whatever the triage mix")
    void tPlusFourIsNotAttempted() {
        BriefingRegion blanked = blank(4, TRIAGED, TRIAGED, TRIAGED);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.NOT_ATTEMPTED);
    }

    @Test
    @DisplayName("half or more failed is FAILED")
    void failuresAreFailed() {
        BriefingRegion blanked = blank(2, ERROR, ERROR, TRIAGED);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.FAILED);
        assertThat(blanked.verdictLabel()).isEqualTo(BriefingHonestyFilter.FAILED_LABEL);
    }

    @Test
    @DisplayName("a fog majority of the stand-downs is the fog marker: 'Fog forecast', fog true")
    void fogMajorityIsFog() {
        BriefingRegion blanked = blank(2, FOG, FOG, TRIAGED);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.STOOD_DOWN);
        assertThat(blanked.verdictLabel()).isEqualTo(BriefingHonestyFilter.FOG_VERDICT_LABEL);
        assertThat(blanked.fog()).isTrue();
    }

    @Test
    @DisplayName("a fog minority keeps 'Too unsettled to forecast' and no fog marker")
    void fogMinorityIsNotFog() {
        BriefingRegion blanked = blank(2, FOG, TRIAGED, TRIAGED);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.STOOD_DOWN);
        assertThat(blanked.verdictLabel()).isEqualTo(BriefingHonestyFilter.VERDICT_LABEL);
        assertThat(blanked.fog()).isNull();
    }

    @Test
    @DisplayName("a canopy slot counts on neither side of the fold")
    void canopySlotCountsOnNeitherSide() {
        BriefingSlot wood = BriefingSlot.canopySlot(9L, "Place 9", EVENT, Verdict.GO, null, List.of())
                .withDisposition(TRIAGED);
        List<BriefingSlot> inReach = new ArrayList<>(slots(TRIAGED, STABILITY));
        inReach.add(wood);

        BriefingRegion blanked = BriefingHonestyFilter.blankedForReach(region(inReach), inReach, DATE, TYPE, 2);

        // The two sky places tie 1-1, so NOT_ATTEMPTED. Counting the triaged wood would have made it STOOD_DOWN.
        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.NOT_ATTEMPTED);
    }

    @Test
    @DisplayName("every place a travel day is AWAY")
    void travelDayIsAway() {
        BriefingRegion blanked = blank(2, TRAVEL, TRAVEL);

        assertThat(blanked.coverageReason()).isEqualTo(CoverageReason.AWAY);
        assertThat(blanked.verdictLabel()).isEqualTo(BriefingHonestyFilter.AWAY_LABEL);
    }
}
