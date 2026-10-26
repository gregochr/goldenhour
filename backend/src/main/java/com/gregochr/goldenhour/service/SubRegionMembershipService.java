package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationSubRegionEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.exception.AreaConflictException;
import com.gregochr.goldenhour.model.UncoveredLocationDto;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.LocationSubRegionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * The only writer of {@code location_sub_region} (sub-regions-plan D3): which sub-region each location belongs
 * to, one per location, enforced by the table's primary key.
 *
 * <p>A location belongs only to a sub-region of its own region: every write checks that and refuses a
 * mismatch. A region change on a location deletes a membership that no longer fits
 * ({@link #reconcileRegion}). Disabled locations keep their membership. Reads for the admin API
 * ({@link #membersByArea}, {@link #findUncovered}) live here too, so nothing else touches the table.
 */
@Service
public class SubRegionMembershipService {

    private static final Logger LOG = LoggerFactory.getLogger(SubRegionMembershipService.class);

    private final LocationSubRegionRepository membership;
    private final AreaRepository areaRepository;
    private final LocationRepository locationRepository;

    /**
     * Constructs the service.
     *
     * @param membership         the {@code location_sub_region} rows
     * @param areaRepository     the areas, to check an id names a sub-region and which region it is in
     * @param locationRepository the locations
     */
    public SubRegionMembershipService(LocationSubRegionRepository membership, AreaRepository areaRepository,
            LocationRepository locationRepository) {
        this.membership = membership;
        this.areaRepository = areaRepository;
        this.locationRepository = locationRepository;
    }

    /**
     * Every sub-region's members, by the sub-region's area id, each list ascending.
     *
     * @return area id to member location ids; areas with no members are absent
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> membersByArea() {
        Map<Long, List<Long>> byArea = new LinkedHashMap<>();
        for (LocationSubRegionEntity row : membership.findAll()) {
            byArea.computeIfAbsent(row.getAreaId(), id -> new ArrayList<>()).add(row.getLocationId());
        }
        byArea.values().forEach(ids -> ids.sort(Comparator.naturalOrder()));
        return byArea;
    }

    /**
     * One sub-region's members.
     *
     * @param areaId the sub-region's area id
     * @return the member location ids, ascending
     */
    @Transactional(readOnly = true)
    public List<Long> membersOf(Long areaId) {
        return membership.findByAreaId(areaId).stream().map(LocationSubRegionEntity::getLocationId).sorted()
                .toList();
    }

    /**
     * How many locations a sub-region holds.
     *
     * @param areaId the sub-region's area id
     * @return the member count
     */
    @Transactional(readOnly = true)
    public long countMembers(Long areaId) {
        return membership.countByAreaId(areaId);
    }

    /**
     * The sub-region a location belongs to.
     *
     * @param locationId the location
     * @return the sub-region's area id, or empty when it has none
     */
    @Transactional(readOnly = true)
    public Optional<Long> subRegionOf(Long locationId) {
        return membership.findByLocationId(locationId).map(LocationSubRegionEntity::getAreaId);
    }

    /**
     * Enabled locations with a region and no sub-region. Disabled and unregioned locations are not counted.
     *
     * @return the uncovered locations, by region then name
     */
    @Transactional(readOnly = true)
    public List<UncoveredLocationDto> findUncovered() {
        return membership.findUncovered();
    }

    /**
     * Checks a sub-region can hold a location of the given region, without writing anything. Callers that
     * also change other things call this first, so a refusal happens before any of it.
     *
     * @param areaId         the sub-region's area id
     * @param locationRegion the location's region (null when it has none)
     * @return the sub-region
     * @throws NoSuchElementException   if there is no such area (HTTP 404)
     * @throws IllegalArgumentException if the area is not a sub-region, or the location's region is not the
     *                                  sub-region's (HTTP 400)
     */
    @Transactional(readOnly = true)
    public AreaEntity requireAssignable(Long areaId, RegionEntity locationRegion) {
        AreaEntity area = areaRepository.findById(areaId)
                .orElseThrow(() -> new NoSuchElementException("No named area with id " + areaId));
        if (!area.isSubRegion()) {
            throw new IllegalArgumentException("'" + area.getName() + "' is not a sub-region");
        }
        if (locationRegion == null) {
            throw new IllegalArgumentException("A location needs a region before it can join a sub-region");
        }
        RegionEntity areaRegion = area.getRegion();
        if (areaRegion == null || !Objects.equals(areaRegion.getId(), locationRegion.getId())) {
            throw new IllegalArgumentException("Sub-region '" + area.getName() + "' is in "
                    + (areaRegion == null ? "no region" : "the region '" + areaRegion.getName() + "'")
                    + ", not '" + locationRegion.getName() + "'");
        }
        return area;
    }

    /**
     * Puts a location in a sub-region, moving it if it was in another. A native upsert in two steps (the local
     * H2 database has no {@code ON CONFLICT}).
     *
     * @param location the location, with its current region
     * @param areaId   the sub-region's area id
     * @throws NoSuchElementException   if there is no such area (HTTP 404)
     * @throws IllegalArgumentException if the area is not a sub-region of the location's region (HTTP 400)
     * @throws AreaConflictException    if a concurrent write won the race (HTTP 409)
     */
    @Transactional
    public void assign(LocationEntity location, Long areaId) {
        AreaEntity area = requireAssignable(areaId, location.getRegion());
        Long previous = membership.findByLocationId(location.getId()).map(LocationSubRegionEntity::getAreaId)
                .orElse(null);
        if (Objects.equals(previous, area.getId())) {
            return;
        }
        try {
            if (membership.updateArea(location.getId(), area.getId()) == 0) {
                membership.insertMembership(location.getId(), area.getId());
            }
        } catch (DataIntegrityViolationException e) {
            throw new AreaConflictException("'" + location.getName()
                    + "' was changed by someone else at the same moment; reload and try again");
        }
        LOG.info("Location '{}' is now in sub-region '{}'", location.getName(), area.getName());
    }

    /**
     * Puts the location with the given id in a sub-region.
     *
     * @param locationId the location's primary key
     * @param areaId     the sub-region's area id
     * @throws NoSuchElementException   if there is no such location or area (HTTP 404)
     * @throws IllegalArgumentException if the area is not a sub-region of the location's region (HTTP 400)
     */
    @Transactional
    public void assign(Long locationId, Long areaId) {
        LocationEntity location = locationRepository.findById(locationId)
                .orElseThrow(() -> new NoSuchElementException("No location with id " + locationId));
        assign(location, areaId);
    }

    /**
     * Drops a membership that no longer fits the location's region, and says so in the log. Call it after a
     * location's region changes. A location with no membership is left alone.
     *
     * @param location the location, carrying its new region
     * @return true if a membership was deleted
     */
    @Transactional
    public boolean reconcileRegion(LocationEntity location) {
        Optional<LocationSubRegionEntity> current = membership.findByLocationId(location.getId());
        if (current.isEmpty()) {
            return false;
        }
        AreaEntity area = areaRepository.findById(current.get().getAreaId()).orElse(null);
        Long areaRegionId = area == null || area.getRegion() == null ? null : area.getRegion().getId();
        Long locationRegionId = location.getRegion() == null ? null : location.getRegion().getId();
        if (areaRegionId != null && areaRegionId.equals(locationRegionId)) {
            return false;
        }
        membership.deleteMembership(location.getId());
        LOG.warn("Location '{}' left sub-region '{}' because its region changed; it now has no sub-region",
                location.getName(), area == null ? current.get().getAreaId() : area.getName());
        return true;
    }

    /**
     * Removes a location's membership, if it has one.
     *
     * @param locationId the location's primary key
     */
    @Transactional
    public void clear(Long locationId) {
        membership.deleteMembership(locationId);
    }
}
