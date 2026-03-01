package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.RegionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link RegionEntity}.
 */
public interface RegionRepository extends JpaRepository<RegionEntity, Long> {

    /**
     * Checks whether a region with the given name already exists.
     *
     * @param name the region name to check
     * @return {@code true} if a region with that name exists
     */
    boolean existsByName(String name);

    /**
     * Finds a region by its exact name.
     *
     * @param name the region name to look up
     * @return the matching region, or empty
     */
    Optional<RegionEntity> findByName(String name);

    /**
     * Returns all regions ordered alphabetically by name.
     *
     * @return list of all region entities
     */
    List<RegionEntity> findAllByOrderByNameAsc();

    /**
     * Returns all enabled regions ordered alphabetically by name.
     *
     * @return list of enabled region entities
     */
    List<RegionEntity> findAllByEnabledTrueOrderByNameAsc();
}
