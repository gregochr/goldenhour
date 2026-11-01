package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.util.ForecastHorizon;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Candidate-collection strategy for the {@code INTRADAY} refresh cycle: the
 * <em>decision window</em>.
 *
 * <p>Intraday is a plan-change detector, not a generic cache refresh. The only
 * events worth re-evaluating mid-afternoon are the ones a photographer could
 * still act on tonight or tomorrow:
 *
 * <ul>
 *   <li><b>T sunset</b> — tonight's shoot, still time to go or stand down;</li>
 *   <li><b>T+1 sunrise</b> — set an alarm, or don't;</li>
 *   <li><b>T+1 sunset</b> — tomorrow evening's plan.</li>
 * </ul>
 *
 * <p>That is the next ~36h of actionable events. Everything else — T+1's
 * already-passed sunrise relative to an afternoon run is impossible; T sunrise
 * is in the past; T+2 onward is not yet actionable and is the nightly run's job
 * — is excluded. The briefing window covers ≥4 days, so all three in-scope
 * events are present to be filtered.
 *
 * <p>"Today" is resolved in {@code Europe/London} (matching the collector's own
 * {@code daysAhead} computation) from the injected {@link Clock} at construction
 * time, so a single cycle sees a stable reference date and tests can pin it.
 *
 * <p>Selection is event-window-aware by design: moving intraday to
 * event-relative scheduling later is a trigger change, not a logic rework.
 */
public final class IntradayCandidateCollectionStrategy implements CandidateCollectionStrategy {

    /** The disposition detail of a T+1 sunrise left for tonight's evening refresh. */
    public static final String EVENING_DEFERRAL_REASON =
            "tonight's evening refresh re-rates tomorrow's sunrise";

    private final LocalDate today;
    private final boolean eveningRefreshDue;

    /**
     * Constructs the strategy with no evening refresh to lean on: every slot in the window is rated.
     *
     * @param clock the cycle's clock; its instant is interpreted in {@code Europe/London}
     */
    public IntradayCandidateCollectionStrategy(Clock clock) {
        this(clock, false);
    }

    /**
     * Constructs the strategy, pinning the reference date ("today") to the
     * London-local date of the given clock.
     *
     * @param clock the cycle's clock; its instant is interpreted in
     *              {@code Europe/London} to derive the reference date
     * @param eveningRefreshDue whether tonight's evening sunrise refresh will run (resolved once per
     *                          cycle by the orchestrator); when {@code true} an already-rated T+1
     *                          sunrise is left for it
     */
    public IntradayCandidateCollectionStrategy(Clock clock, boolean eveningRefreshDue) {
        this.today = ForecastHorizon.today(clock);
        this.eveningRefreshDue = eveningRefreshDue;
    }

    @Override
    public boolean includes(LocalDate date, TargetType targetType) {
        if (date.equals(today)) {
            return targetType == TargetType.SUNSET;
        }
        if (date.equals(today.plusDays(1))) {
            return targetType == TargetType.SUNRISE || targetType == TargetType.SUNSET;
        }
        return false;
    }

    /**
     * Tomorrow's sunrise is re-rated by tonight's 21:00 evening refresh at every stability, so when
     * that refresh is due the afternoon look at a slot that already has a rating adds only noise (the
     * last 30 days' same-day re-ratings repeat the earlier stars about half the time and move two
     * stars or more about 5% of the time, whatever the gap). A slot with no rating is still rated, so
     * no region waits for 21:00 to show a score.
     *
     * <p>The accepted risk: if the evening run fails, the overnight rating (up to about 20 hours old)
     * keeps serving until the next overnight run.
     */
    @Override
    public boolean defersRatedSlot(LocalDate date, TargetType targetType) {
        return eveningRefreshDue && date.equals(today.plusDays(1)) && targetType == TargetType.SUNRISE;
    }
}
