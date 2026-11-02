package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.model.CandidateDisposition;

import java.util.List;

/**
 * The cost-gate outcome of an intraday cycle's stability re-classification,
 * summarised for the {@code STABILITY_RECLASSIFY} phase's {@code detail} row.
 *
 * <p>This is the answer to "why did intraday evaluate few or many locations
 * today?" — derived from the per-candidate dispositions the collection step
 * produced, so it reflects the real gate decision, not a re-derivation. It
 * exists to make the cost-gate visible in the Pipeline Runs UX rather than
 * buried in {@code [BATCH DIAG]} logs.
 *
 * @param considered     decision-window candidates the cycle considered
 * @param settledSkipped candidates skipped as settled
 *                       ({@link DispositionCategory#SKIPPED_NO_REFRESH_NEEDED})
 * @param evaluated      candidates re-evaluated via Claude
 *                       ({@link DispositionCategory#EVALUATED} +
 *                       {@link DispositionCategory#FORCE_EVALUATED})
 * @param deferredToEvening already-rated T+1 sunrises left for tonight's evening refresh (also a
 *                       {@link DispositionCategory#SKIPPED_NO_REFRESH_NEEDED}, but at any stability,
 *                       so counted apart from {@code settledSkipped})
 */
public record ReclassSummary(int considered, int settledSkipped, int evaluated, int deferredToEvening) {

    /**
     * A summary with nothing deferred to the evening.
     *
     * @param considered     candidates considered
     * @param settledSkipped candidates skipped as settled
     * @param evaluated      candidates re-evaluated
     */
    public ReclassSummary(int considered, int settledSkipped, int evaluated) {
        this(considered, settledSkipped, evaluated, 0);
    }

    /**
     * Tallies a disposition list into a summary.
     *
     * @param dispositions the cycle's per-candidate dispositions
     * @return the tallied summary
     */
    public static ReclassSummary from(List<CandidateDisposition> dispositions) {
        int settledSkipped = 0;
        int evaluated = 0;
        int deferred = 0;
        for (CandidateDisposition d : dispositions) {
            switch (d.category()) {
                case SKIPPED_NO_REFRESH_NEEDED -> {
                    if (IntradayCandidateCollectionStrategy.EVENING_DEFERRAL_REASON.equals(d.detail())) {
                        deferred++;
                    } else {
                        settledSkipped++;
                    }
                }
                case EVALUATED, FORCE_EVALUATED -> evaluated++;
                default -> { }
            }
        }
        return new ReclassSummary(dispositions.size(), settledSkipped, evaluated, deferred);
    }

    /**
     * @return human-readable detail for the {@code STABILITY_RECLASSIFY} phase row
     */
    public String detail() {
        // "evaluated", not "unsettled-evaluated". Until the settled skip narrowed to T+1 sunset,
        // every evaluated candidate was TRANSITIONAL or UNSETTLED and the label was accurate; it
        // now describes a set that is mostly SETTLED on a settled afternoon. The count itself is
        // unchanged — only the claim about what is in it was wrong.
        String base = considered + " considered, " + settledSkipped + " settled-skipped, "
                + evaluated + " evaluated";
        return deferredToEvening > 0
                ? base + ", " + deferredToEvening + " left for tonight's evening refresh"
                : base;
    }
}
