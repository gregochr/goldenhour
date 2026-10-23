package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationAliasEntity;
import com.gregochr.goldenhour.exception.NameClashException;
import com.gregochr.goldenhour.repository.LocationAliasRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Admin editing of the other names for a location ("The Heugh" for Hartlepool Headland;
 * ask-named-areas-plan §4.14).
 *
 * <p>Aliases are written only here, never through {@code PUT /api/locations/{id}}, so saving a location can
 * never rewrite them.
 */
@Service
public class LocationAliasService {

    private static final Logger LOG = LoggerFactory.getLogger(LocationAliasService.class);

    private final LocationAliasRepository aliasRepository;
    private final LocationRepository locationRepository;
    private final NameNamespaceGuard nameGuard;

    /**
     * Constructs the service.
     *
     * @param aliasRepository    the location aliases
     * @param locationRepository the locations, to check the location exists
     * @param nameGuard          the shared name rules
     */
    public LocationAliasService(LocationAliasRepository aliasRepository, LocationRepository locationRepository,
            NameNamespaceGuard nameGuard) {
        this.aliasRepository = aliasRepository;
        this.locationRepository = locationRepository;
        this.nameGuard = nameGuard;
    }

    /**
     * Returns every location's aliases, keyed by location id, each list sorted.
     *
     * @return location id to aliases; locations with none are absent
     */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> findAll() {
        return aliasRepository.findAll().stream().collect(Collectors.groupingBy(
                LocationAliasEntity::getLocationId, TreeMap::new,
                Collectors.mapping(LocationAliasEntity::getAlias,
                        Collectors.collectingAndThen(Collectors.toList(), list -> list.stream()
                                .sorted(String.CASE_INSENSITIVE_ORDER).toList()))));
    }

    /**
     * Replaces one location's aliases: delete the old rows, save the new, in one transaction.
     *
     * @param locationId the location's primary key
     * @param aliases    the aliases (null reads as none)
     * @return the saved aliases, sorted
     * @throws NoSuchElementException   if there is no such location (HTTP 404)
     * @throws IllegalArgumentException if an alias has the wrong shape (HTTP 400)
     * @throws NameClashException       if an alias clashes with another name (HTTP 409)
     */
    @Transactional
    public List<String> replace(Long locationId, List<String> aliases) {
        if (!locationRepository.existsById(locationId)) {
            throw new NoSuchElementException("No location with id " + locationId);
        }
        List<String> clean = NameNamespaceGuard.cleanAliases(aliases, 1, "A location alias");
        for (String alias : clean) {
            Optional<String> clash = nameGuard.locationAliasClash(alias, locationId);
            if (clash.isPresent()) {
                throw new NameClashException(clash.get());
            }
        }
        aliasRepository.deleteByLocationId(locationId);
        aliasRepository.flush();
        aliasRepository.saveAll(clean.stream().map(a -> new LocationAliasEntity(locationId, a)).toList());
        LOG.info("Location {} now has {} aliases", locationId, clean.size());
        return clean.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
