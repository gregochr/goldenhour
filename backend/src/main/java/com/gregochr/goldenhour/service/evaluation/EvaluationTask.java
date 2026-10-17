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
     * @param promptKind  which prompt evaluates this task — {@link PromptKind#SKY} (the
     *                    colour rubric) or {@link PromptKind#BLUEBELL} (the dedicated bluebell
     *                    rubric). Selects both the prompt builder at submit time and the
     *                    parser/visitor path at result time; carried on the custom id so the
     *                    async batch round-trip knows which produced a response
     * @param evalRowId   primary key of the {@code PENDING} {@code forecast_evaluation} row this
     *                    task's submission is the carrier for, or {@code null} when no pending
     *                    row was created (every non-{@code SKY} task, and every {@code SKY} task
     *                    on the synchronous path). Embedded in the batch custom id (R3) so the
     *                    result side can score the row in place (R5) without a lookup by natural
     *                    key, which goes ambiguous whenever nightly + intraday + JFDI overlap on
     *                    one slot
     * @param forced      whether this SKY task was submitted by {@code ForceEvalHeadlineSelector}
     *                    as a stability-gated far-out best-bet headline rescue, rather than an
     *                    ordinary Gate-4-eligible task. {@code false} for every non-scheduled path
     *                    (region-filtered admin batch, JFDI, force-submit, the sync engine) — only
     *                    {@code ForecastTaskCollector}'s scheduled loop ever sets it. Embedded in
     *                    the batch custom id (R3, alongside {@code evalRowId}) so the fact survives
     *                    the async Batch API round trip and reaches {@code ForecastResultHandler}
     *                    at result time, which is the ONLY place {@link
     *                    com.gregochr.goldenhour.model.BriefingEvaluationResult#forced} is ever set
     *                    true — see {@code docs/engineering/plan-verdict-consolidation-plan.md} for
     *                    why the previous timestamp-inference design (comparing a
     *                    {@code forecast_run_disposition} row's {@code created_at} against the
     *                    winning result's own evaluation instant) could grant the verdict-minimum-
     *                    sample rule's force-evaluation exemption to an unrelated, ordinary rating
     *                    that merely landed after the disposition was written
     */
    record Forecast(
            LocationEntity location,
            LocalDate date,
            TargetType targetType,
            EvaluationModel model,
            AtmosphericData data,
            WriteTarget writeTarget,
            PromptKind promptKind,
            Long evalRowId,
            boolean forced
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

        /**
         * Which prompt evaluates a forecast task. A bluebell site in season is evaluated by the
         * dedicated bluebell prompt ({@link #BLUEBELL}); everything else uses the colour
         * {@link #SKY} prompt. Open-fell sites in season produce one task of each kind.
         */
        public enum PromptKind {
            /** The standard colour (fiery-sky / golden-hour) evaluation. */
            SKY,
            /** The dedicated bluebell-conditions evaluation. */
            BLUEBELL,
            /**
             * The year-round woodland-conditions evaluation, for locations under canopy.
             *
             * <p>Mutually exclusive with {@link #BLUEBELL} for a given slot: a canopy site in
             * bluebell season is evaluated by the bluebell prompt, out of season by the woodland
             * prompt, never both. Keeping them exclusive is what keeps each batch bucket
             * homogeneous, which is what lets the system prompt stay cached.
             */
            WOODLAND
        }

        /**
         * Canonical constructor: rejects a task that could not be submitted or written back.
         *
         * <p>{@code location}, {@code date}, {@code targetType}, {@code model}, {@code data},
         * {@code writeTarget} and {@code promptKind} must all be non-null, and the location must
         * already be persisted (non-null id) because the id is embedded in the batch custom id.
         * {@code evalRowId} is deliberately nullable and {@code forced} is a primitive.
         *
         * @throws NullPointerException     if any of the seven required components is null
         * @throws IllegalArgumentException if {@code location} has a null id
         */
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
            Objects.requireNonNull(promptKind, "promptKind");
            // evalRowId is deliberately nullable — see the record component javadoc.
        }

        /**
         * Convenience constructor for the common {@link PromptKind#SKY} case with no pending
         * row — the six-arg shape every pre-Pass-3 caller uses. Bluebell/woodland tasks pass
         * {@link PromptKind#BLUEBELL}/{@link PromptKind#WOODLAND} explicitly via the seven-arg
         * constructor; a sky-lane batch submission that persisted a pending row (R4) uses the
         * eight-arg constructor directly to carry its {@code evalRowId}; the nine-arg canonical
         * constructor is reserved for {@code ForecastTaskCollector}'s scheduled loop, the only
         * caller that ever sets {@code forced}.
         *
         * @param location    target location entity
         * @param date        evaluation date
         * @param targetType  SUNRISE / SUNSET / HOURLY
         * @param model       Claude model to use
         * @param data        fully prepared atmospheric data
         * @param writeTarget where the engine should write the parsed result
         */
        public Forecast(LocationEntity location, LocalDate date, TargetType targetType,
                EvaluationModel model, AtmosphericData data, WriteTarget writeTarget) {
            this(location, date, targetType, model, data, writeTarget, PromptKind.SKY, null);
        }

        /**
         * Convenience constructor for an explicit {@link PromptKind} with no pending row — used
         * by the bluebell and woodland task builders (R8: pending rows are sky-lane only).
         *
         * @param location    target location entity
         * @param date        evaluation date
         * @param targetType  SUNRISE / SUNSET / HOURLY
         * @param model       Claude model to use
         * @param data        fully prepared atmospheric data
         * @param writeTarget where the engine should write the parsed result
         * @param promptKind  which prompt evaluates this task
         */
        public Forecast(LocationEntity location, LocalDate date, TargetType targetType,
                EvaluationModel model, AtmosphericData data, WriteTarget writeTarget,
                PromptKind promptKind) {
            this(location, date, targetType, model, data, writeTarget, promptKind, null);
        }

        /**
         * Convenience constructor for a sky-lane batch submission that persisted a pending row
         * (R4), with {@code forced} defaulted to {@code false} — every caller except {@code
         * ForecastTaskCollector}'s scheduled loop (region-filtered admin batches, JFDI,
         * force-submit) uses this shape, since none of them ever force-evaluates a stability-
         * gated headline candidate.
         *
         * @param location    target location entity
         * @param date        evaluation date
         * @param targetType  SUNRISE / SUNSET / HOURLY
         * @param model       Claude model to use
         * @param data        fully prepared atmospheric data
         * @param writeTarget where the engine should write the parsed result
         * @param promptKind  which prompt evaluates this task
         * @param evalRowId   primary key of the pending row this submission carries
         */
        public Forecast(LocationEntity location, LocalDate date, TargetType targetType,
                EvaluationModel model, AtmosphericData data, WriteTarget writeTarget,
                PromptKind promptKind, Long evalRowId) {
            this(location, date, targetType, model, data, writeTarget, promptKind, evalRowId,
                    false);
        }

        @Override
        public String taskKey() {
            // SKY keeps the historic id (no suffix) so existing keys are byte-identical; a
            // BLUEBELL task for the same slot is disambiguated by its suffix.
            String suffix = switch (promptKind) {
                case SKY -> "";
                case BLUEBELL -> "/BLUEBELL";
                case WOODLAND -> "/WOODLAND";
            };
            return location.getId() + "/" + date + "/" + targetType.name() + suffix;
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

        /**
         * Canonical constructor: rejects a task that could not produce a meaningful aurora request.
         *
         * <p>{@code alertLevel}, {@code date}, {@code model}, {@code viableLocations},
         * {@code cloudByLocation}, {@code spaceWeather} and {@code triggerType} must be non-null,
         * and {@code viableLocations} must not be empty (one task is one request listing every
         * viable location, so an empty list has nothing to score). {@code tonightWindow} is
         * deliberately nullable, since real-time triggers omit it.
         *
         * @throws NullPointerException     if any of the seven required components is null
         * @throws IllegalArgumentException if {@code viableLocations} is empty
         */
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
