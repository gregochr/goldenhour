package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;

import java.util.Objects;

/**
 * What one aurora poll did. Every poll evaluates the state machine at most once.
 *
 * @param dark    whether tonight's dark window had opened when the poll ran — a daylight poll reads
 *                the forecast for tonight alone, a night poll the forecast and the conditions now
 * @param level   the level the poll derived, or {@code null} when reading NOAA threw and nothing was
 *                derived. That is an unexpected error, not an outage: the client fails open, so in
 *                an outage a poll evaluates the last data cached, or empty data read as quiet
 * @param action  the state machine's action; NONE when it was not consulted (a NOAA read that threw,
 *                a daylight forecast below MODERATE, or a night poll holding an alert)
 * @param trigger which signal the level comes from: {@link TriggerType#FORECAST_LOOKAHEAD} when the
 *                forecast for the rest of tonight reaches it, {@link TriggerType#REALTIME} when only
 *                the conditions now do; {@code null} when there is no level
 * @param held    whether a night poll held an active alert rather than end it on an estimate: its
 *                {@code level} is below MODERATE, the alert stands, and the reading that will decide
 *                it is still due
 */
public record AuroraPollOutcome(boolean dark, AlertLevel level, AuroraStateCache.Action action,
        TriggerType trigger, boolean held) {

    /**
     * Rejects an outcome without an action, with a level and no trigger (or the reverse), or a hold
     * that is not a night poll's NONE at a level below MODERATE.
     */
    public AuroraPollOutcome {
        Objects.requireNonNull(action, "action");
        if ((level == null) != (trigger == null)) {
            throw new IllegalArgumentException("level and trigger are both present or both absent");
        }
        if (held && !(dark && action == AuroraStateCache.Action.NONE && level != null
                && !level.isAlertWorthy())) {
            throw new IllegalArgumentException(
                    "only a night poll with a level below MODERATE holds, and it consults nothing");
        }
    }

    /**
     * An outcome that held nothing.
     *
     * @param dark    whether the poll ran after dark
     * @param level   the level the poll derived, or {@code null}
     * @param action  the state machine's action
     * @param trigger which signal the level comes from, or {@code null}
     */
    public AuroraPollOutcome(boolean dark, AlertLevel level, AuroraStateCache.Action action,
            TriggerType trigger) {
        this(dark, level, action, trigger, false);
    }

    /**
     * A night poll that held the active alert rather than end it on an estimate.
     *
     * @param level   the level the poll derived, below MODERATE
     * @param trigger which signal the level comes from
     * @return the outcome
     */
    public static AuroraPollOutcome held(AlertLevel level, TriggerType trigger) {
        return new AuroraPollOutcome(true, level, AuroraStateCache.Action.NONE, trigger, true);
    }

    /**
     * A poll whose NOAA read threw, so it derived nothing and left the state machine alone.
     *
     * @param dark whether the poll ran after dark
     * @return the outcome
     */
    public static AuroraPollOutcome noaaReadFailed(boolean dark) {
        return new AuroraPollOutcome(dark, null, AuroraStateCache.Action.NONE, null);
    }
}
