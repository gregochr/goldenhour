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
 */
public record ReclassSummary(int considered, int settledSkipped, int evaluated) {

    /**
     * Tallies a disposition list into a summary.
     *
     * @param dispositions the cycle's per-candidate dispositions
     * @return the tallied summary
     */
    public static ReclassSummary from(List<CandidateDisposition> dispositions) {
        int settledSkipped = 0;
        int evaluated = 0;
        for (CandidateDisposition d : dispositions) {
            switch (d.category()) {
                case SKIPPED_NO_REFRESH_NEEDED -> settledSkipped++;
                case EVALUATED, FORCE_EVALUATED -> evaluated++;
                default -> { }
            }
        }
        return new ReclassSummary(dispositions.size(), settledSkipped, evaluated);
    }

    /**
     * @return human-readable detail for the {@code STABILITY_RECLASSIFY} phase row
     */
    public String detail() {
        return considered + " considered, " + settledSkipped + " settled-skipped, "
                + evaluated + " unsettled-evaluated";
    }
}
