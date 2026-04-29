package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.service.aurora.TriggerType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sealed input type carried by {@link EvaluationService#submit} and
 * {@link EvaluationService#evaluateNow}. Each variant carries the data needed by
 * its corresponding {@link ResultHandler} and prompt-building path; the engine
 * itself does not inspect the inner fields.
 *
 * <p>Adding a new variant requires:
 * <ul>
 *   <li>A new {@code permits} entry on this interface,</li>
 *   <li>A new {@link CustomIdFactory#forXxx} method (and matching
 *       {@link ParsedCustomId} variant),</li>
 *   <li>A new {@link ResultHandler} implementation.</li>
 * </ul>
 *
 * <p>The engine dispatches by sealed-type pattern matching at exactly two seams:
 * {@link EvaluationServiceImpl#submit} (request building) and
 * {@link BatchResultProcessor} (result handling).
 */
public sealed interface EvaluationTask
        permits EvaluationTask.Forecast, EvaluationTask.Aurora {

    /**
     * Returns a stable identifier for this task — used both for diagnostic
     * logging and to assert equality in tests.
     *
     * @return the task identity string (format defined by each variant)
     */
    String taskKey();

    /**
     * Returns the {@link EvaluationModel} this task should be evaluated with.
     *
     * @return the model the call site has chosen (engine never overrides)
     */
    EvaluationModel model();

    /**
     * Forecast colour evaluation for one (location, date, target) triple.
     *
     * <p>Maps 1:1 to a single Anthropic Batch API request; the resulting
     * {@code customId} is built via {@link CustomIdFactory#forForecast}.
     *
     * @param location    target location entity
     * @param date        evaluation date
     * @param targetType  SUNRISE / SUNSET / HOURLY
     * @param model       Claude model to use
     * @param data        fully prepared atmospheric data (weather + cloud + tide + surge etc.)
     * @param writeTarget where the engine should write the parsed result
     *                    ({@link WriteTarget#NONE} → caller owns persistence;
     *                    {@link WriteTarget#BRIEFING_CACHE} → engine writes
     *                    {@code cached_evaluation} via the result handler)
     */
    record Forecast(
            LocationEntity location,
            LocalDate date,
            TargetType targetType,
            EvaluationModel model,
            AtmosphericData data,
            WriteTarget writeTarget
    ) implements EvaluationTask {

        /**
         * Engine-side persistence dispatch for forecast tasks.
         *
         * <p>Consulted by {@link ForecastResultHandler#handleSyncResult} on the sync
         * path. The batch path always treats results as {@link #BRIEFING_CACHE}
         * (region-aggregated cache writes) regardless of this field.
         */
        public enum WriteTarget {
            /** Engine returns parsed result; caller persists separately. */
            NONE,
            /** Engine writes {@code cached_evaluation} via the result handler. */
            BRIEFING_CACHE
        }

        public Forecast {
            Objects.requireNonNull(location, "location");
            if (location.getId() == null) {
                throw new IllegalArgumentException(
                        "ForecastTask requires a persisted location (non-null id)");
            }
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(writeTarget, "writeTarget");
        }

        @Override
        public String taskKey() {
            return location.getId() + "/" + date + "/" + targetType.name();
        }
    }

    /**
     * Aurora photography evaluation for one (alertLevel, date) at the given moment in
     * the geomagnetic-storm cycle.
     *
     * <p>An aurora "task" produces a single Anthropic request whose user message lists
     * every viable location — i.e. one task = one batch request, regardless of how many
     * locations are scored inside it. The {@code customId} is built via
     * {@link CustomIdFactory#forAurora}.
     *
     * @param alertLevel       current alert level (MINOR / MODERATE / STRONG; QUIET is
     *                         rejected upstream)
     * @param date             date the alert is scored against (typically {@code now()})
     * @param model            Claude model to use
     * @param viableLocations  locations that passed weather triage at submit time (must be
     *                         non-empty — empty viable lists are rejected upstream)
     * @param cloudByLocation  per-location cloud cover used to enrich the prompt (and to
     *                         re-classify rejected locations at result time)
     * @param spaceWeather     NOAA SWPC payload at submit time
     * @param triggerType      whether this is a forecast-lookahead or real-time trigger
     * @param tonightWindow    tonight's dark window (may be {@code null} for real-time
     *                         alerts where the window is implicit)
     */
    record Aurora(
            AlertLevel alertLevel,
            LocalDate date,
            EvaluationModel model,
            List<LocationEntity> viableLocations,
            Map<LocationEntity, Integer> cloudByLocation,
            SpaceWeatherData spaceWeather,
            TriggerType triggerType,
            TonightWindow tonightWindow
    ) implements EvaluationTask {

        public Aurora {
            Objects.requireNonNull(alertLevel, "alertLevel");
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(viableLocations, "viableLocations");
            if (viableLocations.isEmpty()) {
                throw new IllegalArgumentException(
                        "AuroraTask requires at least one viable location");
            }
            Objects.requireNonNull(cloudByLocation, "cloudByLocation");
            Objects.requireNonNull(spaceWeather, "spaceWeather");
            Objects.requireNonNull(triggerType, "triggerType");
            // tonightWindow is intentionally nullable — real-time triggers omit it.
        }

        @Override
        public String taskKey() {
            return "au/" + alertLevel.name() + "/" + date;
        }
    }
}
