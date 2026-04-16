package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * Returns all enabled locations that have been enriched with a Bortle class.
     *
     * <p>Used by the astro conditions scorer to find all dark-sky-eligible locations
     * regardless of Bortle threshold.
     *
     * @return enabled locations with a non-null {@code bortle_class}
     */
    List<LocationEntity> findByBortleClassIsNotNullAndEnabledTrue();

    /**
     * Returns all enabled locations that have the {@code BLUEBELL} location type.
     *
     * <p>Used by the bluebell hot topic detector to find candidate locations
     * during bluebell season.
     *
     * @return enabled bluebell locations
     */
    @Query("SELECT l FROM LocationEntity l JOIN l.locationType lt"
            + " WHERE lt = com.gregochr.goldenhour.entity.LocationType.BLUEBELL"
            + " AND l.enabled = true")
    List<LocationEntity> findBluebellLocations();

    /**
     * Returns all enabled locations that have at least one tide type preference.
     *
     * <p>A non-empty {@code tideType} set indicates a coastal location where
     * tide data is relevant. Used by tide hot topic strategies to identify
     * which regions have coastal locations.
     *
     * @return enabled coastal locations (those with at least one tide type)
     */
    @Query("SELECT DISTINCT l FROM LocationEntity l JOIN l.tideType tt WHERE l.enabled = true")
    List<LocationEntity> findCoastalLocations();
}
