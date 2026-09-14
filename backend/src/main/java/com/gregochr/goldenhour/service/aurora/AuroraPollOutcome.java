package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;

import java.util.Objects;

/**
 * What one aurora poll did. Every poll evaluates the state machine at most once.
 *
 * @param dark    whether tonight's dark window had opened when the poll ran — a daylight poll reads
 *                the forecast for tonight alone, a night poll the forecast and the conditions now
 * @param level   the level the poll derived, or {@code null} when NOAA could not be read and nothing
 *                was derived
 * @param action  the state machine's action; NONE when it was not consulted (NOAA unreadable, or a
 *                daylight forecast below MODERATE)
 * @param trigger which signal the level comes from: {@link TriggerType#FORECAST_LOOKAHEAD} when the
 *                forecast for the rest of tonight reaches it, {@link TriggerType#REALTIME} when only
 *                the conditions now do; {@code null} when there is no level
 */
public record AuroraPollOutcome(boolean dark, AlertLevel level, AuroraStateCache.Action action,
        TriggerType trigger) {

    /**
     * Rejects an outcome without an action, or with a level and no trigger (or the reverse).
     */
    public AuroraPollOutcome {
        Objects.requireNonNull(action, "action");
        if ((level == null) != (trigger == null)) {
            throw new IllegalArgumentException("level and trigger are both present or both absent");
        }
    }

    /**
     * A poll that could not read NOAA, so derived nothing and left the state machine alone.
     *
     * @param dark whether the poll ran after dark
     * @return the outcome
     */
    public static AuroraPollOutcome noaaUnavailable(boolean dark) {
        return new AuroraPollOutcome(dark, null, AuroraStateCache.Action.NONE, null);
    }
}
