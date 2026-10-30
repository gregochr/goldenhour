package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.util.ForecastHorizon;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Candidate-collection strategy for the {@code EVENING} refresh cycle: tomorrow's sunrise, alone.
 *
 * <p>The evening exists for one decision, whether to set an alarm, so it looks at the one event that
 * decision turns on, at 21:00 UK time, hours after the afternoon cycle's look and before the overnight
 * one. Nothing else is in scope: tonight's sunset is nearly over, and every later event is the
 * afternoon's and the overnight cycle's job.
 *
 * <p>It <b>bypasses the CACHED gate</b> ({@link #bypassesCacheGate()}). The gate asks whether a
 * region's last result is fresh enough to reuse, but in summer the afternoon's batch can land within
 * the T+1 cap of the evening's start, which would make the evening skip its own slots as "cached".
 * The evening is a deliberate refresh of one event, so it does not ask.
 *
 * <p>"Today" is resolved in {@code Europe/London} from the injected {@link Clock} at construction, so
 * a single cycle sees a stable reference date and tests can pin it.
 */
public final class EveningCandidateCollectionStrategy implements CandidateCollectionStrategy {

    private final LocalDate today;

    /**
     * Constructs the strategy, pinning the reference date ("today") to the London-local date of the
     * given clock.
     *
     * @param clock the cycle's clock; its instant is interpreted in {@code Europe/London}
     */
    public EveningCandidateCollectionStrategy(Clock clock) {
        this.today = ForecastHorizon.today(clock);
    }

    @Override
    public boolean includes(LocalDate date, TargetType targetType) {
        return date.equals(today.plusDays(1)) && targetType == TargetType.SUNRISE;
    }

    @Override
    public boolean bypassesCacheGate() {
        return true;
    }
}
