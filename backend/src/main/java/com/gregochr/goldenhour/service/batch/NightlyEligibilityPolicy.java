package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;

/**
 * Nightly cycle's Gate 4 horizon-depth eligibility policy. Verbatim move of
 * the prior {@code ForecastTaskCollector#resolveEligibility} table so nightly
 * behaviour is preserved bit-for-bit by the extraction.
 *
 * <table>
 *   <caption>Eligibility table</caption>
 *   <tr><th>daysAhead</th><th>Eligibility</th><th>Model tier</th></tr>
 *   <tr><td>T+0, T+1</td><td>all stabilities</td>
 *       <td>{@code BATCH_NEAR_TERM}</td></tr>
 *   <tr><td>T+2</td><td>SETTLED or TRANSITIONAL</td>
 *       <td>{@code BATCH_FAR_TERM}</td></tr>
 *   <tr><td>T+3</td><td>SETTLED only</td>
 *       <td>{@code BATCH_FAR_TERM}</td></tr>
 *   <tr><td>T+4 and beyond</td><td>never eligible</td><td>—</td></tr>
 * </table>
 *
 * <p>UNSETTLED cells from T+1 onward are not evaluated by the batch — they
 * remain triage-only. The policy is intentionally independent of
 * {@code ForecastStability.evaluationWindowDays()}, which is now a
 * display-only depth hint for the admin UI.
 */
public final class NightlyEligibilityPolicy implements EligibilityPolicy {

    /**
     * Shared instance — the policy is stateless so a singleton is correct.
     */
    public static final NightlyEligibilityPolicy INSTANCE = new NightlyEligibilityPolicy();

    /**
     * Private constructor — the policy is stateless, so all callers share
     * {@link #INSTANCE}.
     */
    private NightlyEligibilityPolicy() {
    }

    @Override
    public EligibilityDecision resolve(int daysAhead, ForecastStability stability,
            EvaluationModel nearTermModel, EvaluationModel farTermModel) {
        return switch (daysAhead) {
            case 0, 1 -> EligibilityDecision.include(nearTermModel);
            case 2 -> (stability == ForecastStability.SETTLED
                       || stability == ForecastStability.TRANSITIONAL)
                    ? EligibilityDecision.include(farTermModel)
                    : EligibilityDecision.skip("T+2 " + stability);
            case 3 -> stability == ForecastStability.SETTLED
                    ? EligibilityDecision.include(farTermModel)
                    : EligibilityDecision.skip("T+3 " + stability);
            default -> EligibilityDecision.skip("T+" + daysAhead + " beyond horizon");
        };
    }

    /**
     * Boolean view of {@link #resolve} for callers that need only the include/skip
     * horizon-depth decision, not the model tier — the synchronous path's stability
     * filter in {@code ForecastCommandExecutor}. Delegates to {@code resolve} so the
     * Gate 4 table has exactly one home and the two forecast engines cannot drift.
     *
     * @param daysAhead forecast horizon (T+0 = 0)
     * @param stability classified stability for the candidate's grid cell
     * @return whether the candidate is within the Gate 4 horizon-depth table
     */
    public boolean permitsHorizon(int daysAhead, ForecastStability stability) {
        // Model tiers are irrelevant to the boolean decision — the sync path picks
        // its model from the command, so null placeholders are never read.
        return resolve(daysAhead, stability, null, null).eligible();
    }
}
