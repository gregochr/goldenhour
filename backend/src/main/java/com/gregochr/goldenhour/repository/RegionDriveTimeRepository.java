package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.RegionDriveTimeEntity;
import com.gregochr.goldenhour.entity.RegionDriveTimeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * Spring Data repository for the shared region-base drive-time matrix.
 */
public interface RegionDriveTimeRepository
        extends JpaRepository<RegionDriveTimeEntity, RegionDriveTimeId> {

    /**
     * Deletes every drive time originating at one region (used before a refresh, and when the
     * region's base moves).
     *
     * @param regionId the region's primary key
     */
    @Modifying
    @Query("DELETE FROM RegionDriveTimeEntity rdt WHERE rdt.regionId = :regionId")
    void deleteAllByRegionId(@Param("regionId") Long regionId);

    /**
     * Deletes one region's rows for locations whose routing point changed after the roster was read, because a
     * measurement in flight routed to the old point (reach-scoped-card-plan D1a): the location then reads PENDING.
     *
     * @param regionId   the region's primary key
     * @param rosterReadAt UTC wall-clock instant the roster was read
     */
    @Modifying
    @Query("DELETE FROM RegionDriveTimeEntity rdt WHERE rdt.regionId = :regionId AND rdt.locationId IN "
            + "(SELECT l.id FROM LocationEntity l WHERE l.driveTargetChangedAt > :rosterReadAt)")
    void deleteMovedSince(@Param("regionId") Long regionId, @Param("rosterReadAt") LocalDateTime rosterReadAt);

    /**
     * Deletes every drive time to one location, from every region base. Used when the location's coordinates
     * move: each row describes a journey to the old point.
     *
     * @param locationId the location's primary key
     */
    @Modifying
    @Query("DELETE FROM RegionDriveTimeEntity rdt WHERE rdt.locationId = :locationId")
    void deleteAllByLocationId(@Param("locationId") Long locationId);
}
