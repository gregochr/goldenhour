package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * Structured representation of a parsed Anthropic Batch custom ID.
 *
 * <p>Use pattern matching to route on the concrete subtype:
 * <pre>{@code
 * switch (CustomIdFactory.parse(customId)) {
 *     case ParsedCustomId.Forecast f -> handleForecast(f);
 *     case ParsedCustomId.Jfdi j     -> handleJfdi(j);
 *     case ParsedCustomId.ForceSubmit fs -> handleForce(fs);
 *     case ParsedCustomId.Aurora a   -> handleAurora(a);
 * }
 * }</pre>
 */
public sealed interface ParsedCustomId {

    /**
     * A forecast custom ID from the scheduled batch path.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @param evalRowId  primary key of the {@code PENDING} {@code forecast_evaluation} row this
     *                   submission is the carrier for (prompted-row persistence plan, R3), or
     *                   {@code null} when the id carries no {@code -r{rowId}} suffix — either a
     *                   pre-deploy id (backward compatibility is mandatory: batches submitted by
     *                   the previous binary are in flight at deploy) or a malformed suffix, which
     *                   {@link CustomIdFactory#parse} rejects rather than silently drops
     * @param forced     whether this task was submitted by {@code ForceEvalHeadlineSelector} as a
     *                   stability-gated far-out best-bet headline rescue — {@code false} when the
     *                   id carries no {@code -f} suffix, including every pre-deploy id
     */
    record Forecast(Long locationId, LocalDate date, TargetType targetType, Long evalRowId,
                     boolean forced) implements ParsedCustomId {

        /**
         * Convenience constructor defaulting {@code forced} to {@code false} — the four-arg shape
         * every pre-existing call site and test uses.
         *
         * @param locationId database ID of the location
         * @param date       forecast date
         * @param targetType SUNRISE, SUNSET, or HOURLY
         * @param evalRowId  primary key of the pending row, or {@code null}
         */
        public Forecast(Long locationId, LocalDate date, TargetType targetType, Long evalRowId) {
            this(locationId, date, targetType, evalRowId, false);
        }
    }

    /**
     * A bluebell custom ID from the scheduled bluebell mini-batch path. Carries the same
     * identity as {@link Forecast} but signals that the response was produced by the dedicated
     * bluebell prompt and must be parsed/combined via the bluebell path.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @param forced     whether this task was submitted by {@code ForceEvalHeadlineSelector} —
     *                   see {@link Forecast#forced}'s javadoc; {@code false} when the id carries
     *                   no {@code -f} suffix, including every pre-deploy id
     */
    record Bluebell(Long locationId, LocalDate date, TargetType targetType, boolean forced)
            implements ParsedCustomId {
        // No pre-existing three-arg convenience constructor: unlike Forecast, nothing ever
        // constructed a Bluebell directly with no forced argument — CustomIdFactory#parseBluebell
        // always supplies it, decoded from the id's optional -f suffix.
    }

    /**
     * A woodland custom ID from the year-round woodland lane. Carries the same identity as
     * {@link Forecast} but signals that the response was produced by the dedicated woodland
     * prompt. A canopy site produces a woodland ID out of bluebell season and a {@link Bluebell}
     * one in season — never both for the same slot.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @param forced     whether this task was submitted by {@code ForceEvalHeadlineSelector} —
     *                   see {@link Forecast#forced}'s javadoc; {@code false} when the id carries
     *                   no {@code -f} suffix, including every pre-deploy id
     */
    record Woodland(Long locationId, LocalDate date, TargetType targetType, boolean forced)
            implements ParsedCustomId {
        // No pre-existing three-arg convenience constructor: unlike Forecast, nothing ever
        // constructed a Woodland directly with no forced argument — CustomIdFactory#parseWoodland
        // always supplies it, decoded from the id's optional -f suffix.
    }

    /**
     * A JFDI custom ID.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     */
    record Jfdi(Long locationId, LocalDate date, TargetType targetType)
            implements ParsedCustomId {
    }

    /**
     * A force-submit custom ID.
     *
     * @param sanitisedRegion region name with all non-alphanumeric characters stripped
     * @param locationId      database ID of the location
     * @param date            forecast date
     * @param targetType      SUNRISE, SUNSET, or HOURLY
     */
    record ForceSubmit(String sanitisedRegion, Long locationId, LocalDate date,
                       TargetType targetType) implements ParsedCustomId {
    }

    /**
     * An aurora custom ID.
     *
     * @param alertLevel alert level at batch submission time
     * @param date       forecast date
     */
    record Aurora(AlertLevel alertLevel, LocalDate date) implements ParsedCustomId {
    }
}
