package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;

/**
 * Outcome of an {@link EligibilityPolicy}: either the task is eligible (with
 * the model tier to evaluate it on) or it is skipped (with a short reason
 * fragment for diagnostic logging and the {@link DispositionCategory} the
 * collector should record for it).
 *
 * <p>Exactly one of {@code model} and {@code skipReason} is non-null,
 * mirroring {@code eligible}. {@code skipDisposition} is non-null only when
 * skipped.
 *
 * <p><b>Why the skip carries its disposition category.</b> The collector used
 * to hardcode {@link DispositionCategory#SKIPPED_STABILITY} for every policy
 * skip. That is correct for nightly's Gate 4 (a candidate beyond its stability
 * window), but intraday's cost-gate skips <em>settled</em> locations for a
 * different reason — "nothing has changed, no refresh needed" — which must be
 * recorded as {@link DispositionCategory#SKIPPED_NO_REFRESH_NEEDED} so the
 * disposition taxonomy stays honest. Letting the policy name the category keeps
 * that decision where the policy lives, not hardcoded in the shared collector.
 *
 * @param eligible        whether the task should enter the batch
 * @param model           resolved Claude model when eligible; {@code null} otherwise
 * @param skipReason      short, log-friendly reason fragment when skipped;
 *                        {@code null} when eligible
 * @param skipDisposition disposition category to record for a skipped candidate;
 *                        {@code null} when eligible
 */
record EligibilityDecision(boolean eligible, EvaluationModel model, String skipReason,
        DispositionCategory skipDisposition) {

    /**
     * Builds an "include" decision with the resolved model.
     *
     * @param model the Claude model to evaluate this task with
     * @return an eligible decision
     */
    static EligibilityDecision include(EvaluationModel model) {
        return new EligibilityDecision(true, model, null, null);
    }

    /**
     * Builds a "skip" decision recorded as {@link DispositionCategory#SKIPPED_STABILITY}
     * — the default for stability-window skips (nightly's Gate 4).
     *
     * @param reason short, log-friendly skip reason (e.g. {@code "T+2 UNSETTLED"})
     * @return a non-eligible decision
     */
    static EligibilityDecision skip(String reason) {
        return skip(reason, DispositionCategory.SKIPPED_STABILITY);
    }

    /**
     * Builds a "skip" decision recorded under the given disposition category.
     *
     * @param reason      short, log-friendly skip reason
     * @param disposition disposition category the collector should record
     * @return a non-eligible decision
     */
    static EligibilityDecision skip(String reason, DispositionCategory disposition) {
        return new EligibilityDecision(false, null, reason, disposition);
    }
}
