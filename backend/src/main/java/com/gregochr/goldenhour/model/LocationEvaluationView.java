package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Canonical merged view of a location's evaluation state for a given date and target type.
 *
 * <p>Combines data from {@code cached_evaluation} (batch/SSE-scored results) and
 * {@code forecast_evaluation} (triage stand-down rows and legacy scored rows) with
 * clear precedence: cached evaluation wins, then scored forecast rows, then triage rows.
 *
 * @param locationId          the location primary key
 * @param locationName        the location display name
 * @param regionId            the region primary key (nullable for unregioned locations)
 * @param regionName          the region display name (nullable for unregioned locations)
 * @param date                the forecast date
 * @param targetType          SUNRISE or SUNSET
 * @param source              which data source provided this view
 * @param rating              1-5 star rating, or null if triaged/absent
 * @param summary             Claude's plain-English explanation, or null
 * @param fierySkyPotential   fiery sky score 0-100, or null
 * @param goldenHourPotential golden hour score 0-100, or null
 * @param triageReason        categorised stand-down reason, or null if scored
 * @param triageMessage       formatted stand-down explanation, or null if scored
 * @param evaluationModel     the model that produced the evaluation, or null
 * @param evaluatedAt         when the evaluation was produced, or null
 * @param displayVerdict      unified colour/label signal derived from Claude rating
 *                            (primary) or triage verdict (fallback); never null
 */
public record LocationEvaluationView(
        Long locationId,
        String locationName,
        Long regionId,
        String regionName,
        LocalDate date,
        TargetType targetType,
        Source source,
        Integer rating,
        String summary,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        TriageReason triageReason,
        String triageMessage,
        String evaluationModel,
        Instant evaluatedAt,
        DisplayVerdict displayVerdict
) {

    /**
     * Indicates which data source provided this evaluation view.
     */
    public enum Source {
        /** From the {@code cached_evaluation} table (batch or SSE). */
        CACHED_EVALUATION,
        /** Scored row from {@code forecast_evaluation}. */
        FORECAST_EVALUATION_SCORED,
        /** Triage stand-down row from {@code forecast_evaluation}. */
        FORECAST_EVALUATION_TRIAGE,
        /** No evaluation data found anywhere. */
        NONE
    }
}
