package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TargetType;

/**
 * Eligibility policy for the {@code EVENING} refresh cycle: every stability, on the near-term model.
 *
 * <p>The evening exists for one decision (tomorrow's sunrise, the night before), so it spends a call
 * on every candidate the strategy admits, SETTLED included. Measured over 30 days of
 * {@code evaluation_delta_log}, a SETTLED slot re-evaluated after a day moved by a full star 63% of
 * the time, so skipping it would withhold the very look the cycle is for.
 *
 * <p><b>Why this is not {@link IntradayEligibilityPolicy}.</b> The intraday cycle now leaves an
 * already-rated tomorrow's sunrise to this cycle when this one is due tonight, so the two no longer
 * share a rule for that slot: the afternoon look at it is skipped precisely because this one re-rates
 * it. The evening is the look the reader can act on at bedtime. A change to the intraday gate must not
 * silently change this one, and this policy must never learn to skip.
 *
 * <p>Both model tiers are passed for interface symmetry; only the near-term one is used.
 *
 * <p>Stateless singleton: use {@link #INSTANCE}.
 */
public final class EveningEligibilityPolicy implements EligibilityPolicy {

    /** Shared stateless instance. */
    public static final EveningEligibilityPolicy INSTANCE = new EveningEligibilityPolicy();

    private EveningEligibilityPolicy() {
    }

    @Override
    public EligibilityDecision resolve(int daysAhead, TargetType targetType,
            ForecastStability stability,
            EvaluationModel nearTermModel, EvaluationModel farTermModel) {
        return EligibilityDecision.include(nearTermModel);
    }
}
