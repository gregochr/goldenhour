package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;

/**
 * Eligibility policy for the {@code INTRADAY} refresh cycle: a stability
 * <em>cost-gate</em>.
 *
 * <p>The decision-window candidates have already been narrowed to the next
 * ~36h of actionable events by {@link IntradayCandidateCollectionStrategy}.
 * This policy then decides, per candidate, whether re-evaluating it is worth a
 * Claude call <em>this afternoon</em>:
 *
 * <ul>
 *   <li>{@link ForecastStability#SETTLED}: skip — the synoptic pattern has not
 *       moved since the morning, so the nightly evaluation still holds. Recorded
 *       as {@link DispositionCategory#SKIPPED_NO_REFRESH_NEEDED} (not
 *       {@code SKIPPED_STABILITY}), because the reason is "nothing changed",
 *       not "beyond the horizon". This is where intraday's cost is bounded:
 *       settled locations cost nothing.</li>
 *   <li>{@link ForecastStability#TRANSITIONAL} / {@link ForecastStability#UNSETTLED}:
 *       include on the near-term model — the forecast may have moved since
 *       morning, which is exactly the within-day change intraday exists to
 *       catch.</li>
 * </ul>
 *
 * <p>{@code daysAhead} is ignored: the candidate strategy already constrains
 * the window, and within it every event is equally actionable. Both model tiers
 * are passed for interface symmetry with nightly; intraday only ever uses the
 * near-term one (the same model nightly's T+0/T+1 slots use).
 *
 * <p>Stateless singleton — use {@link #INSTANCE}.
 */
public final class IntradayEligibilityPolicy implements EligibilityPolicy {

    /** Shared stateless instance. */
    public static final IntradayEligibilityPolicy INSTANCE = new IntradayEligibilityPolicy();

    private IntradayEligibilityPolicy() {
    }

    @Override
    public EligibilityDecision resolve(int daysAhead, ForecastStability stability,
            EvaluationModel nearTermModel, EvaluationModel farTermModel) {
        return switch (stability) {
            case SETTLED -> EligibilityDecision.skip(
                    "settled — no intraday refresh needed",
                    DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
            case TRANSITIONAL, UNSETTLED -> EligibilityDecision.include(nearTermModel);
        };
    }
}
