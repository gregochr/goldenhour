package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.service.EvaluationViewService.LatestDisposition;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Pure fold from the pipeline's own record of what it did with a region's slots to a {@link
 * CoverageReason}. {@code failed} below is {@code SUBMISSION_FAILED} plus {@code SKIPPED_ERROR}
 * plus {@code EVALUATED}/{@code FORCE_EVALUATED} whose cycle has ended. Tried in this order
 * (docs/engineering/tide-facts-followups-plan.md section A.2):
 * <ol>
 *   <li>{@code AWAY}: every scoreable slot is {@code SKIPPED_TRAVEL_DAY};</li>
 *   <li>{@code NOT_ATTEMPTED}: T+4 or later, no slot {@code EVALUATED}, {@code FORCE_EVALUATED} or
 *       {@code SUBMISSION_FAILED}, and collection errors under half the slots (Gate 4 never scores
 *       T+4, whatever the triage mix, and a minority of errors does not change that);</li>
 *   <li>{@code FAILED}: T+3 or earlier, and at least half the slots {@code failed};</li>
 *   <li>T+3 or earlier, every slot skipped by stability, triage or a (minority) error: decided by
 *       majority, because the collector triages BEFORE Gate 4 so an unscored window is usually a
 *       mix. Stability at least as common as triage is {@code NOT_ATTEMPTED} (a tie goes there:
 *       claiming poor weather needs a majority), otherwise {@code STOOD_DOWN};</li>
 *   <li>{@code UNKNOWN}: anything else.</li>
 * </ol>
 * A slot with no entry in the lookup (none recorded, a tie, an unparseable category, a renamed
 * location, a failed lookup) is evidence for nothing, so from T+0 to T+3 it leaves the region
 * {@code UNKNOWN}; at T+4 or later the horizon alone decides. {@code UNKNOWN} is worded AWAITING
 * ("Not scored") by {@link BriefingHonestyFilter}: a record that cannot say why is never read as
 * poor weather.
 */
final class CoverageReasons {

    /** First horizon at which Gate 4 never scores a window. */
    static final int NEVER_SCORED_FROM_DAYS_AHEAD = 4;

    private CoverageReasons() {
    }

    /**
     * Folds a region's scoreable slots to a reason.
     *
     * @param scoreableSlots the region's slots that {@link BriefingSlot#couldCarryRating} admits
     * @param date           the window's date
     * @param type           the window's event
     * @param daysAhead      the window's horizon from the UK civil date
     * @param latestBySlot   latest non-cached disposition per {@code location|date|type}
     * @return the reason; {@code UNKNOWN} for no slots or no lookup
     */
    static CoverageReason of(List<BriefingSlot> scoreableSlots, LocalDate date, TargetType type,
            int daysAhead, Map<String, LatestDisposition> latestBySlot) {
        if (scoreableSlots == null || scoreableSlots.isEmpty() || latestBySlot == null) {
            return CoverageReason.UNKNOWN;
        }
        int total = scoreableSlots.size();
        int away = 0;
        int stability = 0;
        int triaged = 0;
        int errors = 0;
        int submissionFailed = 0;
        int submitted = 0;
        int endedSubmitted = 0;
        for (BriefingSlot slot : scoreableSlots) {
            LatestDisposition latest = latestBySlot.get(
                    EvaluationViewService.stabilitySkipKey(slot.locationName(), date, type));
            if (latest == null) {
                continue;
            }
            switch (latest.category()) {
                case SKIPPED_TRAVEL_DAY -> away++;
                case SKIPPED_STABILITY -> stability++;
                case SKIPPED_TRIAGED -> triaged++;
                case SKIPPED_ERROR -> errors++;
                case SUBMISSION_FAILED -> submissionFailed++;
                case EVALUATED, FORCE_EVALUATED -> {
                    submitted++;
                    if (latest.cycleEnded()) {
                        endedSubmitted++;
                    }
                }
                default -> { }
            }
        }
        if (away == total) {
            return CoverageReason.AWAY;
        }
        boolean errorsAreAMinority = errors * 2 < total;
        if (daysAhead >= NEVER_SCORED_FROM_DAYS_AHEAD) {
            return submitted == 0 && submissionFailed == 0 && errorsAreAMinority
                    ? CoverageReason.NOT_ATTEMPTED : CoverageReason.UNKNOWN;
        }
        int failed = submissionFailed + errors + endedSubmitted;
        if (failed * 2 >= total) {
            return CoverageReason.FAILED;
        }
        // Errors are under half here: half or more would already have been FAILED above.
        if (stability + triaged + errors == total) {
            return stability >= triaged ? CoverageReason.NOT_ATTEMPTED
                    : CoverageReason.STOOD_DOWN;
        }
        return CoverageReason.UNKNOWN;
    }
}
