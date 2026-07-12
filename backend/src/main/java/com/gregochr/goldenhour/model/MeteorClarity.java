package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Cached result of the meteor overhead-clarity scan: for each shower-peak night in the forecast
 * window, how many dark-sky locations are forecast clear <em>overhead</em> (total-column cloud below
 * the clear threshold) at deep night.
 *
 * <p>Unlike the aurora/NLC "clear" signals — which sample the northern-horizon transect because those
 * phenomena sit low on the poleward horizon — this reads the sky directly above each site, the honest
 * signal for whole-sky meteor showers. Populated during the daily-briefing run by
 * {@code MeteorClarityService} and read by {@code MeteorHotTopicStrategy} to add a "clear at X of Y
 * dark-sky locations" fact. In-memory only; rebuilt each briefing run.
 *
 * @param byNight per shower-peak night, the clear/total dark-sky counts; never null, keyed by the
 *                peak date
 */
public record MeteorClarity(Map<LocalDate, NightClarity> byNight) {

    /** Defensive compact constructor. */
    public MeteorClarity {
        byNight = Map.copyOf(byNight);
    }

    /** The empty scan — no shower peak in the window, or no dark-sky locations. */
    public static final MeteorClarity EMPTY = new MeteorClarity(Map.of());

    /**
     * Returns the clear/total counts for a given peak night, if the scan covered it.
     *
     * @param date the shower peak date
     * @return the night's clarity counts, or empty when not scanned
     */
    public Optional<NightClarity> forNight(LocalDate date) {
        return Optional.ofNullable(byNight.get(date));
    }

    /**
     * Clear-vs-total dark-sky counts for a single peak night.
     *
     * @param clearLocationCount dark-sky locations forecast clear overhead that night
     * @param totalDarkSkyCount  total dark-sky locations scanned (the "of Y" denominator)
     */
    public record NightClarity(int clearLocationCount, int totalDarkSkyCount) {
    }
}
