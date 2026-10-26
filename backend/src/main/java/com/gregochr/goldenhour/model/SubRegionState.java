package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * A reader's sub-region picks and what they may do next (sub-regions-plan §4.3): the body of
 * {@code GET}/{@code PUT /api/user/settings/sub-regions}. Personal, so it is never ETag-cached.
 *
 * <p>Everything here is decided on the server; the client renders it.
 *
 * @param enforced     whether {@code photocast.sub-regions.enforce} is on (the picker is hidden unless it is)
 * @param limit        the admin-set limit on picks
 * @param picks        the reader's picks, earliest first
 * @param options      every sub-region by region, with counts and example names; empty when {@code status} is
 *                     {@code NOT_APPLICABLE}
 * @param suggested    the {@code limit} sub-region ids nearest the reader's home, when they have a home and no
 *                     picks; otherwise empty. A suggestion only: nothing is saved
 * @param status       {@code FIRST_CHOICE}, {@code FREE_UNTIL}, {@code OPEN}, {@code LOCKED} or
 *                     {@code NOT_APPLICABLE}
 * @param freeUntil    the last free-change day, while {@code status} is {@code FREE_UNTIL}
 * @param nextChangeOn the first day a swap is allowed, while {@code status} is {@code LOCKED}
 * @param overLimit    {@code true} when the reader holds more picks than the limit; absent otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubRegionState(boolean enforced, int limit, List<Pick> picks, List<RegionOptions> options,
        List<Long> suggested, String status, LocalDate freeUntil, LocalDate nextChangeOn, Boolean overLimit) {

    /** Status: the reader holds no picks yet. */
    public static final String FIRST_CHOICE = "FIRST_CHOICE";

    /** Status: inside the grace period; any change is free. */
    public static final String FREE_UNTIL = "FREE_UNTIL";

    /** Status: grace is over and no lock is running; a swap is allowed now. */
    public static final String OPEN = "OPEN";

    /** Status: a swap is refused until {@code nextChangeOn}. */
    public static final String LOCKED = "LOCKED";

    /** Status: Pro and Admin readers do not pick. */
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    /**
     * One picked sub-region.
     *
     * @param areaId     the sub-region's area id
     * @param name       its name
     * @param regionName the region it partitions
     */
    public record Pick(Long areaId, String name, String regionName) {
    }

    /**
     * The sub-regions of one region.
     *
     * @param regionId   the region's id
     * @param regionName the region's name
     * @param subRegions its sub-regions, by name
     */
    public record RegionOptions(Long regionId, String regionName, List<Option> subRegions) {
    }

    /**
     * One sub-region on offer.
     *
     * @param areaId           the sub-region's area id
     * @param name             its name
     * @param locationCount    its enabled locations
     * @param exampleLocations up to two of their names, alphabetically
     */
    public record Option(Long areaId, String name, int locationCount, List<String> exampleLocations) {
    }
}
