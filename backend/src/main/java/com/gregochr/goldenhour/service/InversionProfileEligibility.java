package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;

/**
 * The one test for whether a location gets a v2 inversion valley profile (inversion-v2-plan Phase 1b):
 * the old calculator's own gate (elevation of at least {@value InversionScoreCalculator#MIN_ELEVATION_METRES}
 * m and {@code overlooks_water}) AND a confirmed valley floor (height and the point it was read at).
 *
 * <p>Shared by the batch prefetch and the on-demand fetch so the two can never disagree about which sites
 * are fetched. A site that fails it keeps the old calculator unchanged.
 */
public final class InversionProfileEligibility {

    private InversionProfileEligibility() {
    }

    /**
     * Returns whether the location should have a valley profile fetched.
     *
     * @param location the location, or null
     * @return true when it is inversion-eligible and has a complete valley floor
     */
    public static boolean isEligible(LocationEntity location) {
        return location != null
                && location.getElevationMetres() != null
                && location.getElevationMetres() >= InversionScoreCalculator.MIN_ELEVATION_METRES
                && location.isOverlooksWater()
                && location.getValleyFloorMetres() != null
                && location.getValleyFloorLat() != null
                && location.getValleyFloorLng() != null;
    }

    /**
     * Returns the key that identifies the request a location needs: its valley-floor point and height.
     * Locations with the same key share one fetch.
     *
     * @param location an eligible location
     * @return the key
     */
    public static String requestKey(LocationEntity location) {
        return location.getValleyFloorLat() + "," + location.getValleyFloorLng() + "@"
                + location.getValleyFloorMetres();
    }
}
