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
 * @param runType            the type of forecast run
 * @param dates              the target dates to forecast
 * @param locations          the locations to process (null means all applicable)
 * @param strategy           the evaluation strategy (null for WEATHER/TIDE)
 * @param triggeredManually  whether this was triggered manually via the API
 * @param excludedSlots      (date|TARGETTYPE) keys to skip, e.g. "2026-03-20|SUNRISE"; null/empty = skip none
 */
public record ForecastCommand(
        RunType runType,
        List<LocalDate> dates,
        List<LocationEntity> locations,
        EvaluationStrategy strategy,
        boolean triggeredManually,
        Set<String> excludedSlots
) {
    /** Convenience constructor — no excluded slots. */
    public ForecastCommand(RunType runType, List<LocalDate> dates, List<LocationEntity> locations,
            EvaluationStrategy strategy, boolean triggeredManually) {
        this(runType, dates, locations, strategy, triggeredManually, Set.of());
    }
}
