package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TargetType;

/**
 * Eligibility policy for the {@code INTRADAY} refresh cycle: a narrow
 * <em>later-look</em> gate.
 *
 * <p>The decision-window candidates have already been narrowed to the next
 * ~36h of actionable events by {@link IntradayCandidateCollectionStrategy}.
 * TRANSITIONAL and UNSETTLED cells are always included on the near-term model —
 * the forecast may have moved since morning, which is what intraday exists to
 * catch. The only question this policy answers is what to do with SETTLED:
 *
 * <table>
 *   <caption>SETTLED disposition by slot</caption>
 *   <tr><th>slot</th><th>SETTLED</th><th>why</th></tr>
 *   <tr><td>T+0 sunset</td><td>include</td>
 *       <td>this afternoon is the last look before tonight</td></tr>
 *   <tr><td>T+1 sunrise</td><td>include</td>
 *       <td>its only later run is 01:00, which nobody is awake to read</td></tr>
 *   <tr><td>T+1 sunset</td><td>skip</td>
 *       <td>two further looks are guaranteed before it</td></tr>
 * </table>
 *
 * <p><b>This policy used to skip SETTLED everywhere, and that was wrong.</b> The
 * old rationale was that "the synoptic pattern has not moved since the morning,
 * so the nightly evaluation still holds" — the same claim the 36h cache
 * freshness threshold rested on, and the same evidence refutes it. Measured over
 * 30 days of {@code evaluation_delta_log}, a SETTLED slot re-evaluated after 24h
 * moved by a full star 63% of the time, and after 48h, 72%. A blocking high is
 * stable; the low cloud loitering on the western horizon underneath it is not,
 * and that is what the rating turns on. Synoptic persistence is not rating
 * persistence.
 *
 * <p><b>Why T+1 sunset still skips, on a different justification.</b> Not
 * because its rating will hold — it will move as much as any other — but because
 * it is the one slot in the window with later looks structurally guaranteed: the
 * 01:00 nightly includes every stability at T+0/T+1
 * ({@link NightlyEligibilityPolicy}), and tomorrow's 14:00 cycle meets it again
 * as T+0 sunset, which this policy now includes. Spending a call here buys
 * nothing. The argument is one-directional and concerns SETTLED alone: it does
 * not license withdrawing the TRANSITIONAL/UNSETTLED include, which is a
 * different claim about the most volatile cells.
 *
 * <p>Both model tiers are passed for interface symmetry with nightly; intraday
 * only ever uses the near-term one (the same model nightly's T+0/T+1 slots use).
 *
 * <p>Stateless singleton — use {@link #INSTANCE}.
 */
public final class IntradayEligibilityPolicy implements EligibilityPolicy {

    /** Shared stateless instance. */
    public static final IntradayEligibilityPolicy INSTANCE = new IntradayEligibilityPolicy();

    /** Horizon at which the event is the one happening today. */
    private static final int TODAY = 0;

    private IntradayEligibilityPolicy() {
    }

    @Override
    public EligibilityDecision resolve(int daysAhead, TargetType targetType,
            ForecastStability stability,
            EvaluationModel nearTermModel, EvaluationModel farTermModel) {
        if (stability != ForecastStability.SETTLED) {
            return EligibilityDecision.include(nearTermModel);
        }
        return hasGuaranteedLaterLook(daysAhead, targetType)
                ? EligibilityDecision.skip(
                        "settled, and two further looks are guaranteed before the event",
                        DispositionCategory.SKIPPED_NO_REFRESH_NEEDED)
                : EligibilityDecision.include(nearTermModel);
    }

    /**
     * Whether this slot is certain to be re-evaluated again before its event, which is the only
     * reason a SETTLED candidate is worth skipping.
     *
     * <p>True for exactly one slot in the intraday window: tomorrow's sunset. Tonight's sunset has
     * no later run at all, and tomorrow's sunrise has only the 01:00 one, whose output lands while
     * the reader is asleep — freshness with no chance to act on it.
     *
     * <p>{@link TargetType#HOURLY} cannot reach here — {@code BriefingHierarchyBuilder} emits only
     * SUNRISE and SUNSET, and the candidate strategy rejects anything else — but it falls to
     * {@code false} (i.e. evaluate) rather than being assumed away, because silently skipping an
     * unexpected event type is the worse failure of the two.
     *
     * @param daysAhead  forecast horizon (T+0 = 0)
     * @param targetType the candidate's solar event
     * @return true when a later evaluation of this slot is structurally guaranteed
     */
    private static boolean hasGuaranteedLaterLook(int daysAhead, TargetType targetType) {
        return daysAhead > TODAY && targetType == TargetType.SUNSET;
    }
}
