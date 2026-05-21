package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;

/**
 * Outcome of {@code ForecastTaskCollector#resolveEligibility}: either the
 * task is eligible (with the model tier to evaluate it on) or it is
 * skipped (with a short reason fragment for diagnostic logging).
 *
 * <p>Exactly one of {@code model} and {@code skipReason} is non-null,
 * mirroring {@code eligible}.
 *
 * @param eligible   whether the task should enter the batch
 * @param model      resolved Claude model when eligible; {@code null} otherwise
 * @param skipReason short, log-friendly reason fragment when skipped;
 *                   {@code null} when eligible
 */
record EligibilityDecision(boolean eligible, EvaluationModel model, String skipReason) {

    /**
     * Builds an "include" decision with the resolved model.
     *
     * @param model the Claude model to evaluate this task with
     * @return an eligible decision
     */
    static EligibilityDecision include(EvaluationModel model) {
        return new EligibilityDecision(true, model, null);
    }

    /**
     * Builds a "skip" decision with a short diagnostic reason fragment.
     *
     * @param reason short, log-friendly skip reason (e.g. {@code "T+2 UNSETTLED"})
     * @return a non-eligible decision
     */
    static EligibilityDecision skip(String reason) {
        return new EligibilityDecision(false, null, reason);
    }
}
