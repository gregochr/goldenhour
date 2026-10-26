package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserSubRegionEntity;
import com.gregochr.goldenhour.model.UserPickNameRow;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Reads and writes {@code app_user_sub_region} (V192): a Lite reader's sub-region picks.
 *
 * <p>Extends the bare {@code Repository} marker so there is no {@code save} to reach for; the picks service is
 * the only caller of the writers. Reads are scalar projections, never managed entities, so a read after a
 * write in the same transaction always asks the database.
 */
@org.springframework.stereotype.Repository
public interface AppUserSubRegionRepository extends Repository<AppUserSubRegionEntity, AppUserSubRegionEntity.Key> {

    /**
     * A reader's picks, earliest first (ties by area id).
     *
     * @param userId the reader
     * @return the picked sub-region ids
     */
    @Query("select p.areaId from AppUserSubRegionEntity p where p.userId = :userId "
            + "order by p.createdAt, p.areaId")
    List<Long> findAreaIdsByUserId(@Param("userId") Long userId);

    /**
     * Every pick as a name, for the admin user list.
     *
     * @return one row per pick, by user then name
     */
    @Query("select new com.gregochr.goldenhour.model.UserPickNameRow(p.userId, a.name) "
            + "from AppUserSubRegionEntity p, AreaEntity a where a.id = p.areaId order by p.userId, a.name")
    List<UserPickNameRow> findAllPickNames();

    /**
     * Adds one pick. A second insert of the same pick violates the primary key.
     *
     * @param userId    the reader
     * @param areaId    the sub-region
     * @param createdAt when it was picked
     * @return rows inserted (1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query(value = "INSERT INTO app_user_sub_region (user_id, area_id, created_at) "
            + "VALUES (:userId, :areaId, :createdAt)", nativeQuery = true)
    int insertPick(@Param("userId") Long userId, @Param("areaId") Long areaId,
            @Param("createdAt") LocalDateTime createdAt);

    /**
     * Removes some of a reader's picks.
     *
     * @param userId  the reader
     * @param areaIds the sub-regions to drop
     * @return rows deleted
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query("delete from AppUserSubRegionEntity p where p.userId = :userId and p.areaId in :areaIds")
    int deletePicks(@Param("userId") Long userId, @Param("areaIds") Collection<Long> areaIds);

    /**
     * Removes every pick of one sub-region (it is being deleted). The database cascade does the same; this
     * makes it true on the local H2 schema, which Hibernate builds without keys.
     *
     * @param areaId the sub-region
     * @return rows deleted
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query("delete from AppUserSubRegionEntity p where p.areaId = :areaId")
    int deleteByAreaId(@Param("areaId") Long areaId);
}
