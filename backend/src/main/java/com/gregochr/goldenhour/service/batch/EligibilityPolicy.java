package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;

/**
 * Per-cycle decision function: given a candidate's days-ahead and classified
 * stability, returns whether it enters the batch and, if so, which model
 * tier evaluates it.
 *
 * <p>Today nightly uses the Gate 4 horizon-depth table (T+0/T+1 all stabilities;
 * T+2 SETTLED+TRANSITIONAL; T+3 SETTLED only; T+4+ never). Intraday will
 * implement the cost-gate variant: SKIP SETTLED with a
 * {@code SKIPPED_NO_REFRESH_NEEDED} disposition, INCLUDE
 * TRANSITIONAL/UNSETTLED with the near-term model.
 *
 * <p>The orchestrator passes one of these into the collector so the SAME
 * triage loop and disposition machinery serve every cycle.
 *
 * @see NightlyEligibilityPolicy
 * @see EligibilityDecision
 */
@FunctionalInterface
public interface EligibilityPolicy {

    /**
     * Returns the eligibility decision for a single candidate.
     *
     * @param daysAhead     forecast horizon (T+0 = 0)
     * @param stability     classified stability for the candidate's grid cell
     * @param nearTermModel the resolved {@code BATCH_NEAR_TERM} model for this
     *                      run; an impl may return this, the far-term one, or
     *                      neither (skip)
     * @param farTermModel  the resolved {@code BATCH_FAR_TERM} model for this
     *                      run
     * @return include-with-model or skip-with-reason
     */
    EligibilityDecision resolve(int daysAhead, ForecastStability stability,
            EvaluationModel nearTermModel, EvaluationModel farTermModel);
}
