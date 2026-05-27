package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * Per-cycle filter deciding which briefing event slots enter the candidate set.
 *
 * <p>The orchestrator passes one of these to {@link ForecastTaskCollector} so the
 * SAME briefing-iteration and triage code path serves every cycle type. Today
 * nightly accepts all future events; intraday will accept only the
 * decision-window events ({@code T sunset}, {@code T+1 sunrise},
 * {@code T+1 sunset}). The filter is applied at the
 * {@code (BriefingDay, BriefingEventSummary)} level — coarser than per-slot —
 * because event-time decisions are taken at that grain.
 *
 * <p>Implementations are pure functions of {@code (date, targetType)}; no
 * external state. The existing past-date check
 * ({@code if (date.isBefore(today))}) stays inside the collector and is
 * applied BEFORE this strategy — every cycle skips past dates the same way,
 * so it is not a per-strategy concern.
 *
 * @see NightlyCandidateCollectionStrategy
 */
@FunctionalInterface
public interface CandidateCollectionStrategy {

    /**
     * Returns {@code true} if the given event slot is in this cycle's window.
     *
     * @param date       briefing day's date (already filtered to today-or-future
     *                   by the collector; the strategy never sees past dates)
     * @param targetType the slot's event type (SUNRISE / SUNSET)
     * @return whether the cycle should evaluate this slot
     */
    boolean includes(LocalDate date, TargetType targetType);
}
