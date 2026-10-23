package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.LocationAliasEntity;
import com.gregochr.goldenhour.entity.LocationAliasId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link LocationAliasEntity}.
 */
public interface LocationAliasRepository extends JpaRepository<LocationAliasEntity, LocationAliasId> {

    /**
     * Deletes every alias of one location. Call inside a transaction.
     *
     * @param locationId the location primary key
     */
    @Modifying
    @Query("DELETE FROM LocationAliasEntity a WHERE a.locationId = :locationId")
    void deleteByLocationId(@Param("locationId") Long locationId);
}
