package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TargetType;

/**
 * Per-cycle decision function: given a candidate's days-ahead, solar event and
 * classified stability, returns whether it enters the batch and, if so, which
 * model tier evaluates it.
 *
 * <p>Nightly uses the Gate 4 horizon-depth table (T+0/T+1 all stabilities;
 * T+2 SETTLED+TRANSITIONAL; T+3 SETTLED only; T+4+ never) and ignores the
 * event. Intraday implements a narrower cost gate that does not: it skips
 * SETTLED only where a later look is already guaranteed, which is a statement
 * about a specific (horizon, event) pair rather than about stability alone.
 *
 * <p><b>Why {@code targetType} is a parameter.</b> The intraday window is
 * <em>defined</em> in terms of solar events —
 * {@link IntradayCandidateCollectionStrategy} admits sunset today and either
 * event tomorrow — so a policy gating that window cannot express its own rule
 * without seeing them. It could previously be faked, because in the intraday
 * cycle {@code daysAhead == 0} happens to imply SUNSET, but that is a
 * coincidence of the window's shape rather than a fact about the policy, and it
 * breaks the moment the window widens.
 *
 * <p>The orchestrator passes one of these into the collector so the SAME
 * triage loop and disposition machinery serve every cycle.
 *
 * @see NightlyEligibilityPolicy
 * @see IntradayEligibilityPolicy
 * @see EligibilityDecision
 */
@FunctionalInterface
public interface EligibilityPolicy {

    /**
     * Returns the eligibility decision for a single candidate.
     *
     * @param daysAhead     forecast horizon (T+0 = 0)
     * @param targetType    the candidate's solar event. Never null on any live
     *                      path; SUNRISE or SUNSET in practice, since the
     *                      briefing hierarchy emits no other kind
     * @param stability     classified stability for the candidate's grid cell
     * @param nearTermModel the resolved {@code BATCH_NEAR_TERM} model for this
     *                      run; an impl may return this, the far-term one, or
     *                      neither (skip)
     * @param farTermModel  the resolved {@code BATCH_FAR_TERM} model for this
     *                      run
     * @return include-with-model or skip-with-reason
     */
    EligibilityDecision resolve(int daysAhead, TargetType targetType, ForecastStability stability,
            EvaluationModel nearTermModel, EvaluationModel farTermModel);
}
