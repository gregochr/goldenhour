package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Preview of the next three aurora forecast nights, used by the night selector popup.
 *
 * <p>Data is cheap to produce — it reads cached NOAA Kp forecast data and counts
 * Bortle-eligible locations without calling Claude.
 *
 * @param nights the next three nights (tonight, T+1, T+2) with Kp data
 */
public record AuroraForecastPreview(List<NightPreview> nights) {

    /** Compact constructor that defensively copies the mutable nights list. */
    public AuroraForecastPreview {
        nights = nights == null ? List.of() : List.copyOf(nights);
    }


    /**
     * Preview data for a single night.
     *
     * @param date              the calendar date (the evening of this date → early morning of next)
     * @param label             human-readable label ("Tonight — Sat 21 Mar", "Tomorrow — Sun 22 Mar", etc.)
     * @param maxKp             highest Kp value forecast within the dark window (0–9)
     * @param gScale            NOAA G-scale label ("G1"–"G5") or null if below G1 threshold (Kp &lt; 5)
     * @param recommended       true when maxKp meets the configured alert threshold (≥ 5 by default)
     * @param summary           one-line description ("Kp 6 expected 22:00–03:00" or "Quiet — Kp 2")
     * @param eligibleLocations count of Bortle-eligible locations for MODERATE threshold
     */
    public record NightPreview(
            LocalDate date,
            String label,
            double maxKp,
            String gScale,
            boolean recommended,
            String summary,
            int eligibleLocations) {}
}
