package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.RegionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
     * Stamps the instant a region base's drive times were fully measured, but only while the base is still the
     * point that was routed from. The same compare-and-set {@code AppUserRepository.stampDriveTimesIfHomeIs}
     * performs for a home: a measurement reads the base, routes for seconds, then stores, and an admin can move the
     * base in that gap. The comparison and the write are one statement, so a check read first and written after
     * would reopen the gap. {@code RegionEntity.drivesMeasuredAt} is {@code updatable = false}, so this and
     * {@link #clearDrivesMeasuredAt} are its only writers.
     *
     * @param id         the region id
     * @param baseLat    the base latitude the drive times were measured from
     * @param baseLon    the base longitude the drive times were measured from
     * @param measuredAt the instant to record (taken before the roster was read)
     * @return 1 if stamped; 0 if the base has moved or been cleared since (or no region has that id)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RegionEntity r SET r.drivesMeasuredAt = :measuredAt "
            + "WHERE r.id = :id AND r.baseLat = :baseLat AND r.baseLon = :baseLon")
    int stampDrivesMeasuredAtIfBaseIs(@Param("id") Long id, @Param("baseLat") double baseLat,
            @Param("baseLon") double baseLon, @Param("measuredAt") Instant measuredAt);

    /**
     * Locks the region row if its base is still the point a measurement was taken from, without stamping: the guard
     * for a measurement that returned fewer destinations than were asked for, whose rows are stored but whose stamp
     * is not (so the destinations it missed stay PENDING). A no-op UPDATE rather than a SELECT, so it takes the row
     * lock and {@code RegionService.setBase} cannot commit between the check and the write.
     *
     * @param id      the region id
     * @param baseLat the base latitude the drive times were measured from
     * @param baseLon the base longitude the drive times were measured from
     * @return 1 if the region still has exactly that base (and is now locked); 0 otherwise
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("UPDATE RegionEntity r SET r.baseLat = r.baseLat "
            + "WHERE r.id = :id AND r.baseLat = :baseLat AND r.baseLon = :baseLon")
    int lockIfBaseIs(@Param("id") Long id, @Param("baseLat") double baseLat,
            @Param("baseLon") double baseLon);

    /**
     * Clears a region's drive-measured stamp, because its stored drive times no longer describe its base. A literal
     * {@code SET ... = NULL}, for the reason {@code AppUserRepository.clearDriveTimesCalculatedAtIfHomeIs} gives.
     * Does not clear the persistence context: {@code RegionService.setBase} calls it with the region still managed
     * and nulls the in-memory field itself.
     *
     * @param id the region id
     * @return rows updated (0 or 1)
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("UPDATE RegionEntity r SET r.drivesMeasuredAt = NULL WHERE r.id = :id")
    int clearDrivesMeasuredAt(@Param("id") Long id);

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
