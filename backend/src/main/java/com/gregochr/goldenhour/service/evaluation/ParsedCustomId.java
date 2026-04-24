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
     */
    record Forecast(Long locationId, LocalDate date, TargetType targetType)
            implements ParsedCustomId {
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
