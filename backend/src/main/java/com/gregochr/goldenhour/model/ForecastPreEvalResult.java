package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Intermediate result carrying state between the triage phase and evaluation phase.
 *
 * <p>After weather data is fetched and triage heuristics are applied, this record holds
 * either the triaged result (with a persisted canned entity) or the atmospheric data
 * ready for Claude evaluation.
 *
 * @param triaged          {@code true} if heuristic triage determined conditions are unsuitable
 * @param triageReason     human-readable explanation of the triage, or {@code null} if not triaged
 * @param triageCategory   categorised triage reason enum, or {@code null} if not triaged
 * @param atmosphericData  atmospheric data ready for evaluation, or {@code null} if triaged
 * @param location         the location entity
 * @param date             the forecast date
 * @param targetType       SUNRISE or SUNSET
 * @param eventTime        UTC time of the solar event
 * @param azimuth          solar azimuth in degrees
 * @param daysAhead        number of days from today
 * @param model            evaluation model to use
 * @param tideTypes        tide preferences for the location
 * @param taskKey          unique task key (locationName|date|targetType)
 * @param forecastResponse raw Open-Meteo response for stability classification (nullable)
 */
public record ForecastPreEvalResult(
        boolean triaged,
        String triageReason,
        TriageReason triageCategory,
        AtmosphericData atmosphericData,
        LocationEntity location,
        LocalDate date,
        TargetType targetType,
        LocalDateTime eventTime,
        Integer azimuth,
        int daysAhead,
        EvaluationModel model,
        Set<TideType> tideTypes,
        String taskKey,
        OpenMeteoForecastResponse forecastResponse
) {
    /**
     * Compact constructor — defensive copy of tideTypes to prevent external mutation.
     */
    public ForecastPreEvalResult {
        tideTypes = tideTypes == null ? null : Set.copyOf(tideTypes);
    }

    /**
     * Convenience constructor without the triage category enum (used for non-triaged results).
     *
     * @param triaged          true if triaged
     * @param triageReason     triage reason string, or null
     * @param atmosphericData  atmospheric data, or null if triaged
     * @param location         location entity
     * @param date             forecast date
     * @param targetType       SUNRISE or SUNSET
     * @param eventTime        UTC event time
     * @param azimuth          azimuth degrees
     * @param daysAhead        days from today
     * @param model            evaluation model
     * @param tideTypes        tide preferences
     * @param taskKey          unique task key
     * @param forecastResponse raw Open-Meteo response
     */
    public ForecastPreEvalResult(boolean triaged, String triageReason,
            AtmosphericData atmosphericData, LocationEntity location, LocalDate date,
            TargetType targetType, LocalDateTime eventTime, Integer azimuth, int daysAhead,
            EvaluationModel model, Set<TideType> tideTypes, String taskKey,
            OpenMeteoForecastResponse forecastResponse) {
        this(triaged, triageReason, null, atmosphericData, location, date, targetType,
                eventTime, azimuth, daysAhead, model, tideTypes, taskKey, forecastResponse);
    }
}
