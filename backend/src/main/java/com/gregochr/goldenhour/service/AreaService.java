package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.exception.NameClashException;
import com.gregochr.goldenhour.model.AreaDto;
import com.gregochr.goldenhour.model.AreaLocationsRequest;
import com.gregochr.goldenhour.model.AreaRequest;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Admin editing of the named areas Ask understands ("Hadrian's Wall"; ask-named-areas-plan §4.14).
 *
 * <p>An area's name and aliases are written by {@link #create} and {@link #update}; its members only by
 * {@link #setLocations}. {@code update} loads the existing row and changes it in place, because a fresh entity
 * built from the request would carry an empty member set and wipe the membership.
 */
@Service
public class AreaService {

    private static final Logger LOG = LoggerFactory.getLogger(AreaService.class);

    private final AreaRepository areaRepository;
    private final LocationRepository locationRepository;
    private final NameNamespaceGuard nameGuard;

    /**
     * Constructs the service.
     *
     * @param areaRepository     the areas
     * @param locationRepository the locations, to check membership ids
     * @param nameGuard          the shared name rules
     */
    public AreaService(AreaRepository areaRepository, LocationRepository locationRepository,
            NameNamespaceGuard nameGuard) {
        this.areaRepository = areaRepository;
        this.locationRepository = locationRepository;
        this.nameGuard = nameGuard;
    }

    /**
     * Returns every area, ordered by name.
     *
     * @return the areas
     */
    @Transactional(readOnly = true)
    public List<AreaDto> findAll() {
        return areaRepository.findAll().stream()
                .map(AreaService::toDto)
                .sorted(Comparator.comparing(AreaDto::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Creates an area with no members.
     *
     * @param request the name and aliases
     * @return the saved area
     * @throws IllegalArgumentException if a name has the wrong shape (HTTP 400)
     * @throws NameClashException       if a name clashes with another (HTTP 409)
     */
    @Transactional
    public AreaDto create(AreaRequest request) {
        String name = NameNamespaceGuard.cleanName(request.name(), 1, "An area name");
        List<String> aliases = NameNamespaceGuard.cleanAliases(request.aliases(), 2, "An area alias");
        checkNames(name, aliases, null);
        AreaEntity area = new AreaEntity();
        area.setName(name);
        area.getAliases().addAll(aliases);
        AreaEntity saved = saveOrClash(area);
        LOG.info("Created named area '{}' with {} aliases", name, aliases.size());
        return toDto(saved);
    }

    /**
     * Renames an area and replaces its aliases. The members are left exactly as they are.
     *
     * @param id      the area's primary key
     * @param request the name and aliases
     * @return the saved area
     * @throws NoSuchElementException   if there is no such area (HTTP 404)
     * @throws IllegalArgumentException if a name has the wrong shape (HTTP 400)
     * @throws NameClashException       if a name clashes with another (HTTP 409)
     */
    @Transactional
    public AreaDto update(Long id, AreaRequest request) {
        AreaEntity area = load(id);
        String name = NameNamespaceGuard.cleanName(request.name(), 1, "An area name");
        List<String> aliases = NameNamespaceGuard.cleanAliases(request.aliases(), 2, "An area alias");
        checkNames(name, aliases, id);
        area.setName(name);
        area.getAliases().clear();
        area.getAliases().addAll(aliases);
        AreaEntity saved = saveOrClash(area);
        LOG.info("Updated named area {} to '{}' with {} aliases", id, name, aliases.size());
        return toDto(saved);
    }

    /**
     * Replaces an area's membership. The only writer of an area's location ids.
     *
     * @param id      the area's primary key
     * @param request the member location ids
     * @return the saved area
     * @throws NoSuchElementException   if there is no such area (HTTP 404)
     * @throws IllegalArgumentException if a location id does not exist (HTTP 400)
     */
    @Transactional
    public AreaDto setLocations(Long id, AreaLocationsRequest request) {
        AreaEntity area = load(id);
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
        return toDto(saved);
    }

    /**
     * Deletes an area; its aliases and membership go with it.
     *
     * @param id the area's primary key
     * @throws NoSuchElementException if there is no such area (HTTP 404)
     */
    @Transactional
    public void delete(Long id) {
        AreaEntity area = load(id);
        areaRepository.delete(area);
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
    private void checkNames(String name, List<String> aliases, Long excludeAreaId) {
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
        }
    }

    private static AreaDto toDto(AreaEntity area) {
        return new AreaDto(area.getId(), area.getName(),
                area.getAliases().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                area.getLocationIds().stream().sorted().toList());
    }
}
