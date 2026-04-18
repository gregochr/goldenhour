package com.gregochr.goldenhour.model;

import com.gregochr.solarutils.LunarPhase;

/**
 * Moon transition data across tonight's dark viewing window.
 *
 * <p>Derived from hourly sampling of {@link com.gregochr.solarutils.LunarCalculator}
 * across the window. Replaces the old single-midpoint snapshot with a transition-aware
 * model that detects moonrise/moonset within the window.
 *
 * @param phase             lunar phase (from first sample in window)
 * @param illuminationPct   illumination percentage 0–100 (from first sample)
 * @param windowQuality     temporal moon quality across the entire dark window
 * @param moonRiseTime      ISO UTC datetime of first below→above transition,
 *                          or {@code null}
 * @param moonSetTime       ISO UTC datetime of first above→below transition,
 *                          or {@code null}
 * @param moonUpAtStart     whether the moon is above the horizon at window start
 * @param moonUpAtEnd       whether the moon is above the horizon at window end
 */
public record MoonTransitionData(
        LunarPhase phase,
        double illuminationPct,
        WindowQuality windowQuality,
        String moonRiseTime,
        String moonSetTime,
        boolean moonUpAtStart,
        boolean moonUpAtEnd) {

    /**
     * Describes how the moon's presence changes across the dark viewing window.
     */
    public enum WindowQuality {

        /** Moon below horizon for the entire window — best conditions. */
        DARK_ALL_WINDOW,

        /** Moon rises mid-window — good early window, moonlit later. */
        DARK_THEN_MOONLIT,

        /** Moon sets mid-window — moonlit early, good late window. */
        MOONLIT_THEN_DARK,

        /** Moon above horizon for the entire window. */
        MOONLIT_ALL_WINDOW
    }
}
