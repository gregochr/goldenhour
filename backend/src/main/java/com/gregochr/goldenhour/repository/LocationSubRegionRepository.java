package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.LocationSubRegionEntity;
import com.gregochr.goldenhour.model.SubRegionLocationRow;
import com.gregochr.goldenhour.model.SubRegionMemberRow;
import com.gregochr.goldenhour.model.UncoveredLocationDto;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reads, and with three native statements writes, {@code location_sub_region} (V191).
 *
 * <p>It extends the bare {@code Repository} marker, not {@code JpaRepository}, so there is no {@code save} to
 * reach for: {@code SubRegionMembershipService} is the only caller of the writers below, and it is the only
 * writer of the table. The writers are native (the entity is immutable) and plain {@code UPDATE}, {@code INSERT}
 * and {@code DELETE}: the local H2 database has no {@code ON CONFLICT}, so the service does the upsert in two
 * steps and treats a lost race on the primary key as "the row exists now".
 */
@org.springframework.stereotype.Repository
public interface LocationSubRegionRepository extends Repository<LocationSubRegionEntity, Long> {

    /**
     * Every membership row as scalars. Reads are scalar projections, never managed entities, on purpose: the
     * writers below are native statements the persistence context cannot see, so a managed entity read earlier
     * in the same transaction would be stale, and clearing the context instead would detach the caller's own
     * entities. A scalar query always asks the database.
     *
     * @return all rows
     */
    @Query("select new com.gregochr.goldenhour.model.SubRegionMemberRow(m.areaId, m.locationId) "
            + "from LocationSubRegionEntity m")
    List<SubRegionMemberRow> findAllRows();

    /**
     * Every ENABLED location that belongs to a sub-region, with its name and position: what the picker shows
     * (counts, example names) and what home-distance suggestions are measured from. Disabled locations keep
     * their membership but are not offered.
     *
     * @return one row per enabled member
     */
    @Query("select new com.gregochr.goldenhour.model.SubRegionLocationRow(m.areaId, l.id, l.name, l.lat, l.lon) "
            + "from LocationSubRegionEntity m, LocationEntity l where l.id = m.locationId and l.enabled = true")
    List<SubRegionLocationRow> findEnabledMemberRows();

    /**
     * The sub-region a location belongs to, if any.
     *
     * @param locationId the location
     * @return a list of at most one area id
     */
    @Query("select m.areaId from LocationSubRegionEntity m where m.locationId = :locationId")
    List<Long> findAreaIdsByLocationId(@Param("locationId") Long locationId);

    /**
     * The members of one sub-region.
     *
     * @param areaId the sub-region's area id
     * @return its member location ids
     */
    @Query("select m.locationId from LocationSubRegionEntity m where m.areaId = :areaId")
    List<Long> findLocationIdsByAreaId(@Param("areaId") Long areaId);

    /**
     * How many locations a sub-region holds.
     *
     * @param areaId the sub-region's area id
     * @return the member count
     */
    long countByAreaId(Long areaId);

    /**
     * Enabled locations with a region and no sub-region: the ones the admin still has to place. Disabled and
     * unregioned locations are not counted as uncovered.
     *
     * @return the uncovered locations, by region then name
     */
    @Query("select new com.gregochr.goldenhour.model.UncoveredLocationDto(l.id, l.name, r.id, r.name) "
            + "from LocationEntity l join l.region r where l.enabled = true "
            + "and not exists (select 1 from LocationSubRegionEntity m where m.locationId = l.id) "
            + "order by r.name, l.name")
    List<UncoveredLocationDto> findUncovered();

    /**
     * Moves an existing membership to another sub-region.
     *
     * @param locationId the location
     * @param areaId     the new sub-region
     * @return rows changed: 1, or 0 when the location has no membership yet
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query(value = "UPDATE location_sub_region SET area_id = :areaId WHERE location_id = :locationId",
            nativeQuery = true)
    int updateArea(@Param("locationId") Long locationId, @Param("areaId") Long areaId);

    /**
     * Gives a location its first membership. A second insert for the same location violates the primary key.
     *
     * @param locationId the location
     * @param areaId     the sub-region
     * @return rows inserted (1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query(value = "INSERT INTO location_sub_region (location_id, area_id) VALUES (:locationId, :areaId)",
            nativeQuery = true)
    int insertMembership(@Param("locationId") Long locationId, @Param("areaId") Long areaId);

    /**
     * Removes a location's membership.
     *
     * @param locationId the location
     * @return rows deleted: 1, or 0 when it had none
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query(value = "DELETE FROM location_sub_region WHERE location_id = :locationId", nativeQuery = true)
    int deleteMembership(@Param("locationId") Long locationId);
}
