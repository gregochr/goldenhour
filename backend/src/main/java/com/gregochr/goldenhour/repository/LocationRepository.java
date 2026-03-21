package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link LocationEntity}.
 */
public interface LocationRepository extends JpaRepository<LocationEntity, Long> {

    /**
     * Returns {@code true} if a location with the given name already exists.
     *
     * @param name location name to check
     * @return {@code true} if a matching row exists
     */
    boolean existsByName(String name);

    /**
     * Finds a location by its exact name.
     *
     * @param name the location name to look up
     * @return an {@link Optional} containing the entity if found
     */
    Optional<LocationEntity> findByName(String name);

    /**
     * Returns all locations ordered alphabetically by name.
     *
     * @return locations sorted by name ascending
     */
    List<LocationEntity> findAllByOrderByNameAsc();

    /**
     * Returns all enabled locations ordered alphabetically by name.
     *
     * @return enabled locations sorted by name ascending
     */
    List<LocationEntity> findAllByEnabledTrueOrderByNameAsc();

    /**
     * Returns all locations where {@code bortle_class} has not yet been populated.
     *
     * <p>Used by the Bortle enrichment job to find locations pending enrichment.
     *
     * @return locations with a {@code null} Bortle class
     */
    List<LocationEntity> findByBortleClassIsNull();

    /**
     * Returns all enabled locations with a Bortle class at or below the given threshold.
     *
     * <p>Used by the aurora polling job to find dark-sky-eligible locations.
     * Locations with a {@code null} Bortle class are excluded.
     *
     * @param maxBortleClass the maximum Bortle class to include (inclusive)
     * @return enabled locations with {@code bortle_class <= maxBortleClass}
     */
    List<LocationEntity> findByBortleClassLessThanEqualAndEnabledTrue(int maxBortleClass);
}
