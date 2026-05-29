package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

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

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final LocalDate today;

    /**
     * Constructs the strategy, pinning the reference date ("today") to the
     * London-local date of the given clock.
     *
     * @param clock the cycle's clock; its instant is interpreted in
     *              {@code Europe/London} to derive the reference date
     */
    public IntradayCandidateCollectionStrategy(Clock clock) {
        this.today = LocalDate.now(clock.withZone(LONDON));
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
}
