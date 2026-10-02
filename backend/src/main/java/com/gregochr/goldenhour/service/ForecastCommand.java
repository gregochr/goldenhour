package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Encapsulates everything needed to execute a forecast run.
 *
 * <p>Separates *what to do* (run type, dates, locations) from *how to do it* (strategy).
 * Built by {@link ForecastCommandFactory} and executed by {@link ForecastCommandExecutor}.
 *
 * @param runType             the type of forecast run
 * @param dates               the target dates to forecast
 * @param locations           the locations to process (null means all applicable)
 * @param strategy            the evaluation strategy (null for WEATHER/TIDE)
 * @param triggeredManually   whether this was triggered manually via the API
 * @param excludedSlots       (date|TARGETTYPE) keys to skip, e.g. "2026-03-20|SUNRISE"; null/empty = skip none
 * @param excludedLocations   location names to exclude from the run (e.g. too far to drive); null/empty = skip none
 * @param slots               when non-empty, the ONLY slots a colour run evaluates: the executor builds a task
 *                            for a (location, date, event) triple only if it is in this set, so a retry
 *                            re-runs exactly what failed rather than every combination of the failed places
 *                            and dates. Null/empty = every slot the locations and dates produce. A
 *                            non-empty set also stands sentinel sampling down for the run (see
 *                            {@link ForecastCommandExecutor}). Ignored by the wildlife engine
 */
public record ForecastCommand(
        RunType runType,
        List<LocalDate> dates,
        List<LocationEntity> locations,
        EvaluationStrategy strategy,
        boolean triggeredManually,
        Set<String> excludedSlots,
        Set<String> excludedLocations,
        Set<ForecastSlot> slots
) {
    /** Convenience constructor — exclusions but no explicit slot list (every slot the places and dates make). */
    public ForecastCommand(RunType runType, List<LocalDate> dates, List<LocationEntity> locations,
            EvaluationStrategy strategy, boolean triggeredManually, Set<String> excludedSlots,
            Set<String> excludedLocations) {
        this(runType, dates, locations, strategy, triggeredManually, excludedSlots, excludedLocations,
                Set.of());
    }

    /** Convenience constructor — no excluded slots or locations. */
    public ForecastCommand(RunType runType, List<LocalDate> dates, List<LocationEntity> locations,
            EvaluationStrategy strategy, boolean triggeredManually) {
        this(runType, dates, locations, strategy, triggeredManually, Set.of(), Set.of());
    }

    /** Convenience constructor — excluded slots only, no excluded locations. */
    public ForecastCommand(RunType runType, List<LocalDate> dates, List<LocationEntity> locations,
            EvaluationStrategy strategy, boolean triggeredManually, Set<String> excludedSlots) {
        this(runType, dates, locations, strategy, triggeredManually, excludedSlots, Set.of());
    }
}
