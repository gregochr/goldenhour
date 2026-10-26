package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.SubRegionLocationRow;

import java.util.List;
import java.util.Set;

/**
 * Which locations a reader's ratings may be served for, computed once per request by
 * {@link LitePickShaper#scopeFor} (docs/engineering/sub-regions-plan.md D8).
 *
 * <p>Two shapes only. {@link #unrestricted()}: nothing is shaped (enforcement is off, or the caller is
 * positively PRO_USER or ADMIN), every check answers yes, and the response keeps today's body and today's
 * caching. Otherwise the scope is <em>shaped</em> and allows exactly the listed locations: a location with no
 * sub-region, an unregioned location, an unknown id and a null id are all outside it, and an empty scope (a
 * reader with no picks, or a failed picks read) allows nothing.
 *
 * <p>Ids and names are both carried because the payloads differ in what they name: the briefing, the scores
 * and the by-id forecast carry a location id; the forecast list and the hot topics carry only a name, which
 * is unique per location ({@code LocationEntity.name} is unique).
 *
 * @param shaped        true when responses must be shaped by the picks
 * @param locationIds   the allowed location ids (empty when not shaped, and then ignored)
 * @param locationNames the allowed location names (empty when not shaped, and then ignored)
 */
public record PickScope(boolean shaped, Set<Long> locationIds, Set<String> locationNames) {

    private static final PickScope UNRESTRICTED = new PickScope(false, Set.of(), Set.of());

    private static final PickScope NOTHING = new PickScope(true, Set.of(), Set.of());

    /**
     * Canonical constructor: takes immutable copies, so a caller's later changes cannot widen the scope.
     */
    public PickScope {
        locationIds = Set.copyOf(locationIds);
        locationNames = Set.copyOf(locationNames);
    }

    /**
     * The scope that shapes nothing.
     *
     * @return the unrestricted scope
     */
    public static PickScope unrestricted() {
        return UNRESTRICTED;
    }

    /**
     * The scope that allows nothing: a reader with no picks, or any failure reading them (fail closed).
     *
     * @return the empty shaped scope
     */
    public static PickScope nothing() {
        return NOTHING;
    }

    /**
     * The scope that allows exactly the given locations.
     *
     * @param rows the covered locations
     * @return a shaped scope (empty rows give {@link #nothing()})
     */
    public static PickScope of(List<SubRegionLocationRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return NOTHING;
        }
        return new PickScope(true,
                Set.copyOf(rows.stream().map(SubRegionLocationRow::locationId).toList()),
                Set.copyOf(rows.stream().map(SubRegionLocationRow::name).toList()));
    }

    /**
     * Whether ratings for a location id may be served.
     *
     * @param locationId the location id, or null
     * @return true when nothing is shaped, or the id is covered; a null id is never covered
     */
    public boolean allowsId(Long locationId) {
        return !shaped || (locationId != null && locationIds.contains(locationId));
    }

    /**
     * Whether ratings for a location name may be served.
     *
     * @param locationName the location name, or null
     * @return true when nothing is shaped, or the name is covered; a null name is never covered
     */
    public boolean allowsName(String locationName) {
        return !shaped || (locationName != null && locationNames.contains(locationName));
    }

    /**
     * The allowed ids in ascending order, for the served {@code allowedLocationIds}.
     *
     * @return the sorted ids
     */
    public List<Long> sortedIds() {
        return locationIds.stream().sorted().toList();
    }
}
