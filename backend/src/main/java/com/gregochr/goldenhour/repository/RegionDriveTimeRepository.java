package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.RegionDriveTimeEntity;
import com.gregochr.goldenhour.entity.RegionDriveTimeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
