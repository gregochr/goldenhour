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
 * @param triaged        {@code true} if heuristic triage determined conditions are unsuitable
 * @param triageReason   human-readable reason for triage, or {@code null} if not triaged
 * @param atmosphericData atmospheric data ready for evaluation, or {@code null} if triaged
 * @param location       the location entity
 * @param date           the forecast date
 * @param targetType     SUNRISE or SUNSET
 * @param eventTime      UTC time of the solar event
 * @param azimuth        solar azimuth in degrees
 * @param daysAhead      number of days from today
 * @param model          evaluation model to use
 * @param tideTypes      tide preferences for the location
 * @param taskKey        unique task key (locationName|date|targetType)
 */
public record ForecastPreEvalResult(
        boolean triaged,
        String triageReason,
        AtmosphericData atmosphericData,
        LocationEntity location,
        LocalDate date,
        TargetType targetType,
        LocalDateTime eventTime,
        Integer azimuth,
        int daysAhead,
        EvaluationModel model,
        Set<TideType> tideTypes,
        String taskKey
) {
}
