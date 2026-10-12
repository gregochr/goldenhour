package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.service.EvaluationViewService.LatestDisposition;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Pure fold from the pipeline's own record of what it did with a region's slots to a {@link
 * CoverageReason}. Tried in this order (docs/engineering/tide-facts-followups-plan.md section A.2):
 * <ol>
 *   <li>{@code AWAY}: every scoreable slot is {@code SKIPPED_TRAVEL_DAY};</li>
 *   <li>{@code NOT_ATTEMPTED}: T+4 or later, and no slot was submitted or errored (whatever the
 *       triage mix: from T+4 Gate 4 skips everything);</li>
 *   <li>{@code NOT_ATTEMPTED}: T+3 or earlier, every slot {@code SKIPPED_STABILITY};</li>
 *   <li>{@code STOOD_DOWN}: every slot {@code SKIPPED_TRIAGED};</li>
 *   <li>{@code FAILED}: T+3 or earlier, and at least half the slots failed ({@code
 *       SUBMISSION_FAILED}, {@code SKIPPED_ERROR}, or submitted in a cycle that has ended);</li>
 *   <li>{@code UNKNOWN}: anything else, which keeps the original wording.</li>
 * </ol>
 * A slot with no entry in the lookup (none recorded, a tie, an unparseable category, a renamed
 * location, a failed lookup) is never evidence for any rule but the horizon one.
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
        int failed = 0;
        boolean submittedOrErrored = false;
        for (BriefingSlot slot : scoreableSlots) {
            LatestDisposition latest = latestBySlot.get(
                    EvaluationViewService.stabilitySkipKey(slot.locationName(), date, type));
            if (latest == null) {
                continue;
            }
            DispositionCategory category = latest.category();
            switch (category) {
                case SKIPPED_TRAVEL_DAY -> away++;
                case SKIPPED_STABILITY -> stability++;
                case SKIPPED_TRIAGED -> triaged++;
                case EVALUATED, FORCE_EVALUATED -> {
                    submittedOrErrored = true;
                    if (latest.cycleEnded()) {
                        failed++;
                    }
                }
                case SUBMISSION_FAILED, SKIPPED_ERROR -> {
                    submittedOrErrored = true;
                    failed++;
                }
                default -> { }
            }
        }
        if (away == total) {
            return CoverageReason.AWAY;
        }
        if (daysAhead >= NEVER_SCORED_FROM_DAYS_AHEAD) {
            return submittedOrErrored ? CoverageReason.UNKNOWN : CoverageReason.NOT_ATTEMPTED;
        }
        if (stability == total) {
            return CoverageReason.NOT_ATTEMPTED;
        }
        if (triaged == total) {
            return CoverageReason.STOOD_DOWN;
        }
        if (failed * 2 >= total) {
            return CoverageReason.FAILED;
        }
        return CoverageReason.UNKNOWN;
    }
}
