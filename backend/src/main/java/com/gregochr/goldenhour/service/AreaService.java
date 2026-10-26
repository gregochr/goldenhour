package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.exception.AreaConflictException;
import com.gregochr.goldenhour.exception.NameClashException;
import com.gregochr.goldenhour.model.AreaDto;
import com.gregochr.goldenhour.model.AreaLocationsRequest;
import com.gregochr.goldenhour.model.AreaRequest;
import com.gregochr.goldenhour.model.UncoveredLocationDto;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.util.PlaceNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Admin editing of the named areas Ask understands ("Hadrian's Wall"; ask-named-areas-plan §4.14).
 *
 * <p>An area's name and aliases are written by {@link #create} and {@link #update}; its members only by
 * {@link #setLocations}. {@code update} loads the existing row and changes it in place, because a fresh entity
 * built from the request would carry an empty member set and wipe the membership.
 *
 * <p>A {@link AreaKind#SUB_REGION} (sub-regions-plan) differs: it has a region, its aliases may be one word,
 * and its members are not set here at all but on each location, through {@link SubRegionMembershipService}.
 */
@Service
public class AreaService {

    private static final Logger LOG = LoggerFactory.getLogger(AreaService.class);

    private final AreaRepository areaRepository;
    private final LocationRepository locationRepository;
    private final RegionRepository regionRepository;
    private final NameNamespaceGuard nameGuard;
    private final SubRegionMembershipService membershipService;

    /**
     * Constructs the service.
     *
     * @param areaRepository     the areas
     * @param locationRepository the locations, to check membership ids
     * @param regionRepository   the regions, for a sub-region's region
     * @param nameGuard          the shared name rules
     * @param membershipService  the sub-region members, which this service only reads
     */
    public AreaService(AreaRepository areaRepository, LocationRepository locationRepository,
            RegionRepository regionRepository, NameNamespaceGuard nameGuard,
            SubRegionMembershipService membershipService) {
        this.areaRepository = areaRepository;
        this.locationRepository = locationRepository;
        this.regionRepository = regionRepository;
        this.nameGuard = nameGuard;
        this.membershipService = membershipService;
    }

    /**
     * Returns every area, ordered by name.
     *
     * @return the areas
     */
    @Transactional(readOnly = true)
    public List<AreaDto> findAll() {
        Map<Long, List<Long>> subRegionMembers = membershipService.membersByArea();
        return areaRepository.findAll().stream()
                .map(area -> toDto(area, subRegionMembers))
                .sorted(Comparator.comparing(AreaDto::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Returns the enabled, regioned locations that belong to no sub-region, for the admin Areas section.
     *
     * @return the uncovered locations, by region then name
     */
    @Transactional(readOnly = true)
    public List<UncoveredLocationDto> findUncoveredLocations() {
        return membershipService.findUncovered();
    }

    /**
     * Creates an area with no members. A sub-region needs a region; a named area must not have one.
     *
     * @param request the name, aliases, and for a sub-region its kind and region
     * @return the saved area
     * @throws IllegalArgumentException if a name has the wrong shape, or the kind and region disagree (HTTP 400)
     * @throws NameClashException       if a name clashes with another (HTTP 409)
     */
    @Transactional
    public AreaDto create(AreaRequest request) {
        AreaKind kind = request.kind() == null ? AreaKind.NAMED : request.kind();
        RegionEntity region = resolveRegionFor(kind, request.regionId());
        String name = NameNamespaceGuard.cleanName(request.name(), 1, "An area name");
        List<String> aliases = NameNamespaceGuard.cleanAliases(request.aliases(),
                NameNamespaceGuard.areaAliasMinWords(kind),
                "An area alias");
        checkNames(name, aliases, null, kind);
        AreaEntity area = new AreaEntity();
        area.setName(name);
        area.setKind(kind);
        area.setRegion(region);
        area.getAliases().addAll(aliases);
        AreaEntity saved = saveOrClash(area);
        LOG.info("Created {} '{}' with {} aliases", kind == AreaKind.SUB_REGION ? "sub-region" : "named area",
                name, aliases.size());
        return toDto(saved, Map.of());
    }

    /**
     * Renames an area and replaces its aliases. The members are left exactly as they are.
     *
     * @param id      the area's primary key
     * @param request the name and aliases; a kind or region, if sent, must equal the area's own
     * @return the saved area
     * @throws NoSuchElementException   if there is no such area (HTTP 404)
     * @throws IllegalArgumentException if a name has the wrong shape, or the kind or region would change
     *                                  (HTTP 400)
     * @throws NameClashException       if a name clashes with another (HTTP 409)
     */
    @Transactional
    public AreaDto update(Long id, AreaRequest request) {
        AreaEntity area = load(id);
        requireUnchangedKindAndRegion(area, request);
        String name = NameNamespaceGuard.cleanName(request.name(), 1, "An area name");
        List<String> aliases = NameNamespaceGuard.cleanAliases(request.aliases(),
                NameNamespaceGuard.areaAliasMinWords(area.getKind()),
                "An area alias");
        checkNames(name, aliases, id, area.getKind());
        area.setName(name);
        area.getAliases().clear();
        area.getAliases().addAll(aliases);
        AreaEntity saved = saveOrClash(area);
        LOG.info("Updated named area {} to '{}' with {} aliases", id, name, aliases.size());
        return toDto(saved, subRegionMembers(saved));
    }

    /**
     * Replaces a named area's membership. The only writer of an area's location ids. A sub-region is refused:
     * its members are set on each location (its sub-region field), so the one-per-location rule holds.
     *
     * @param id      the area's primary key
     * @param request the member location ids
     * @return the saved area
     * @throws NoSuchElementException   if there is no such area (HTTP 404)
     * @throws IllegalArgumentException if the area is a sub-region, or a location id does not exist (HTTP 400)
     */
    @Transactional
    public AreaDto setLocations(Long id, AreaLocationsRequest request) {
        AreaEntity area = load(id);
        if (area.isSubRegion()) {
            throw new IllegalArgumentException("'" + area.getName() + "' is a sub-region: set a location's "
                    + "sub-region on the location itself (its sub-region field), not on the area");
        }
        if (request.locationIds() == null) {
            // A missing list must not read as "no members": only an explicit [] clears the area.
            throw new IllegalArgumentException("locationIds is required (send [] to clear the members)");
        }
        Set<Long> wanted = new HashSet<>();
        for (Long locationId : request.locationIds()) {
            if (locationId == null) {
                throw new IllegalArgumentException("A location id must not be null");
            }
            wanted.add(locationId);
        }
        Set<Long> found = new HashSet<>();
        locationRepository.findAllById(wanted).forEach(l -> found.add(l.getId()));
        Set<Long> unknown = new HashSet<>(wanted);
        unknown.removeAll(found);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("No location with id " + unknown.stream().sorted().findFirst()
                    .orElseThrow());
        }
        area.getLocationIds().clear();
        area.getLocationIds().addAll(wanted);
        AreaEntity saved = saveOrClash(area);
        LOG.info("Named area '{}' now has {} members", saved.getName(), wanted.size());
        return toDto(saved, Map.of());
    }

    /**
     * Deletes an area; its aliases and membership go with it. A sub-region that still has members is refused:
     * move its locations to another sub-region first.
     *
     * @param id the area's primary key
     * @throws NoSuchElementException if there is no such area (HTTP 404)
     * @throws AreaConflictException  if the area is a sub-region with members (HTTP 409)
     */
    @Transactional
    public void delete(Long id) {
        AreaEntity area = load(id);
        if (area.isSubRegion()) {
            long members = membershipService.countMembers(id);
            if (members > 0) {
                throw new AreaConflictException("Sub-region '" + area.getName() + "' still has " + members
                        + (members == 1 ? " location" : " locations")
                        + ": move them to another sub-region before deleting it");
            }
        }
        try {
            areaRepository.delete(area);
            areaRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // A member was added between the count above and the delete: the RESTRICT key caught it.
            throw new AreaConflictException("'" + area.getName() + "' still has locations, or is still in use: "
                    + "move them to another sub-region before deleting it");
        }
        LOG.info("Deleted named area '{}'", area.getName());
    }

    /**
     * Saves and flushes inside the transaction, so a unique or primary-key violation (two admins creating the
     * same name at once) is caught here and answered as a 409, not left to surface at commit as a 500.
     */
    private AreaEntity saveOrClash(AreaEntity area) {
        try {
            return areaRepository.saveAndFlush(area);
        } catch (DataIntegrityViolationException e) {
            throw new NameClashException("That name is already taken by another named area");
        }
    }

    private AreaEntity load(Long id) {
        return areaRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No named area with id " + id));
    }

    /** Every name against the namespace, and against the others in the same request. */
    private void checkNames(String name, List<String> aliases, Long excludeAreaId, AreaKind kind) {
        List<String> all = new ArrayList<>();
        all.add(name);
        all.addAll(aliases);
        Set<List<String>> seen = new HashSet<>();
        for (String candidate : all) {
            if (!seen.add(PlaceNames.words(candidate))) {
                throw new NameClashException("'" + candidate + "' is listed twice for this named area");
            }
            Optional<String> clash = nameGuard.areaNameClash(candidate, excludeAreaId);
            if (clash.isPresent()) {
                throw new NameClashException(clash.get());
            }
            if (kind == AreaKind.SUB_REGION) {
                clash = nameGuard.subRegionNameClash(candidate, excludeAreaId);
                if (clash.isPresent()) {
                    throw new NameClashException(clash.get());
                }
            }
        }
    }

    /** The region a new area of this kind gets: required for a sub-region, refused for a named area. */
    private RegionEntity resolveRegionFor(AreaKind kind, Long regionId) {
        if (kind == AreaKind.NAMED) {
            if (regionId != null) {
                throw new IllegalArgumentException("A named area has no region; only a sub-region does");
            }
            return null;
        }
        if (regionId == null) {
            throw new IllegalArgumentException("A sub-region needs a region");
        }
        return regionRepository.findById(regionId)
                .orElseThrow(() -> new IllegalArgumentException("No region with id " + regionId));
    }

    /** An area's kind and region never change: an update that names different ones is refused. */
    private static void requireUnchangedKindAndRegion(AreaEntity area, AreaRequest request) {
        if (request.kind() != null && request.kind() != area.getKind()) {
            throw new IllegalArgumentException("An area's kind cannot be changed; create a new area instead");
        }
        if (request.regionId() != null) {
            Long current = area.getRegion() == null ? null : area.getRegion().getId();
            if (!request.regionId().equals(current)) {
                throw new IllegalArgumentException("An area's region cannot be changed; create a new area instead");
            }
        }
    }

    /** The members of a sub-region, keyed by area id; empty for any other area. */
    private Map<Long, List<Long>> subRegionMembers(AreaEntity area) {
        return area.isSubRegion() ? Map.of(area.getId(), membershipService.membersOf(area.getId())) : Map.of();
    }

    private static AreaDto toDto(AreaEntity area, Map<Long, List<Long>> subRegionMembers) {
        List<Long> members = area.isSubRegion()
                ? subRegionMembers.getOrDefault(area.getId(), List.of())
                : area.getLocationIds().stream().sorted().toList();
        RegionEntity region = area.getRegion();
        return new AreaDto(area.getId(), area.getName(),
                area.getAliases().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), members,
                area.getKind(), region == null ? null : region.getId(), region == null ? null : region.getName());
    }
}
