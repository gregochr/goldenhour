package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.RegionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link RegionEntity}.
 */
public interface RegionRepository extends JpaRepository<RegionEntity, Long> {

    /**
     * Sets {@code enabled} on one region and touches no other column. {@code RegionEntity.enabled} is
     * {@code updatable = false}, so this is the only writer after the insert: no whole-entity save of a stale
     * snapshot (a rename, a base change) can flip it.
     *
     * @param id      the region id
     * @param enabled the new state
     * @return rows updated (0 or 1)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RegionEntity r SET r.enabled = :enabled WHERE r.id = :id")
    int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

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
