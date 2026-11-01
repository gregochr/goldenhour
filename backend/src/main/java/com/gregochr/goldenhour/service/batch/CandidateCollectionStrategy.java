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

    /**
     * Whether this cycle ignores the fresh-cached-evaluation skip, evaluating a region even when
     * its cached evaluation is still within its freshness window.
     *
     * @return {@code true} to bypass the CACHED gate; {@code false} (the default) honours it
     */
    default boolean bypassesCacheGate() {
        return false;
    }

    /**
     * Whether a slot this cycle would otherwise re-rate is deliberately left to a later, already
     * scheduled run, provided the location already holds a stored rating for it. {@code
     * ForecastTaskCollector} asks this only after the slot has passed weather fetch, triage and the
     * eligibility policy (so a triage stand-down still records SKIPPED_TRIAGED and retracts), and
     * only for a location with a live rating; a {@code true} answer records a no-refresh skip
     * instead of building a Claude request.
     *
     * @param date       the slot's date
     * @param targetType the slot's event type
     * @return {@code true} to leave an already-rated slot alone this cycle; {@code false} (the
     *         default) to rate it as usual
     */
    default boolean defersRatedSlot(LocalDate date, TargetType targetType) {
        return false;
    }
}
