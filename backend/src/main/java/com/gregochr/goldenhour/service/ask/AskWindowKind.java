package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;

/**
 * What kind of window an Ask pick or window id names: a solar event or a night (ask-night plan §2.1).
 *
 * <p>Ask-local on purpose, and <b>never a new {@link TargetType} value</b>: {@code TargetType} is
 * persisted in {@code forecast_evaluation.target_type} ({@code @Enumerated(STRING)}, which an existing
 * local H2 file refuses to widen), and must not grow for a need that exists only in Ask. The names of
 * the two solar kinds are those of the {@code TargetType} values they stand for, so a pick's
 * {@code targetType} reads the same on the wire as it always has ({@code SUNRISE}, {@code SUNSET}) and
 * the client's {@code windowKey(date, targetType)} keeps working; the two night kinds add
 * {@code ASTRO} and {@code AURORA}.
 */
public enum AskWindowKind {

    /** A sunrise window. */
    SUNRISE,

    /** A sunset window. */
    SUNSET,

    /** A night scored for stargazing: the astro conditions forecast. */
    ASTRO,

    /** A night scored for the northern lights: the aurora forecast result. */
    AURORA;

    /**
     * The kind of a solar window.
     *
     * @param type the window's target type
     * @return {@link #SUNRISE} or {@link #SUNSET}
     * @throws IllegalArgumentException when the type is not a solar event ({@code HOURLY} is the wildlife
     *         comfort row): a window is a solar event or a night, and a snapshot never builds a solar window
     *         from anything else
     */
    public static AskWindowKind of(TargetType type) {
        return switch (type) {
            case SUNRISE -> SUNRISE;
            case SUNSET -> SUNSET;
            default -> throw new IllegalArgumentException("not a solar target type: " + type);
        };
    }

    /**
     * Whether this is a night kind.
     *
     * @return true for {@link #ASTRO} and {@link #AURORA}
     */
    public boolean isNight() {
        return this == ASTRO || this == AURORA;
    }
}
