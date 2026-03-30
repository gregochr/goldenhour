package com.gregochr.goldenhour.entity;

/**
 * Lunar-driven tide classification based on the Moon's phase and distance.
 *
 * <p>This is an astronomical classification independent of observed water heights:
 * <ul>
 *   <li>{@link #REGULAR_TIDE} — no significant lunar alignment (neap tides).</li>
 *   <li>{@link #SPRING_TIDE} — new or full moon; gravitational alignment produces larger tidal range.</li>
 *   <li>{@link #KING_TIDE} — spring tide coinciding with lunar perigee; the strongest tidal forcing.</li>
 * </ul>
 */
public enum LunarTideType {

    /** No significant lunar alignment — neap tides. */
    REGULAR_TIDE,

    /** New Moon or Full Moon — gravitational alignment produces larger tidal range. */
    SPRING_TIDE,

    /** Spring tide coinciding with lunar perigee — strongest tidal forcing. */
    KING_TIDE
}
