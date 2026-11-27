package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.UserFavouriteEntity;
import com.gregochr.goldenhour.model.FavouriteRow;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Reads and writes {@code user_favourite} (V226): a reader's starred places and areas.
 *
 * <p>Extends the bare {@code Repository} marker so there is no {@code save} to reach for; the favourites
 * service is the only caller of the writers, and takes the reader's row lock first. Reads are scalar
 * projections, never managed entities, so a read after a write in the same transaction always asks the
 * database.
 */
@org.springframework.stereotype.Repository
public interface UserFavouriteRepository extends Repository<UserFavouriteEntity, Long> {

    /**
     * A reader's favourites of every kind (places, areas and watched dates), oldest first (ties by id).
     *
     * @param userId the reader
     * @return one row per favourite
     */
    @Query("select new com.gregochr.goldenhour.model.FavouriteRow(f.locationId, f.areaId, f.almanacKey, "
            + "f.createdAt) from UserFavouriteEntity f where f.userId = :userId order by f.createdAt, f.id")
    List<FavouriteRow> findRowsByUserId(@Param("userId") Long userId);

    /**
     * A reader's starred places and areas only, oldest first. A watched date is not a place: Ask's reads go through
     * this, so a date row can never reach {@code rank_spots.favourites} or the location expansion.
     *
     * @param userId the reader
     * @return one row per starred place or area
     */
    @Query("select new com.gregochr.goldenhour.model.FavouriteRow(f.locationId, f.areaId, f.almanacKey, "
            + "f.createdAt) from UserFavouriteEntity f where f.userId = :userId and f.almanacKey is null "
            + "order by f.createdAt, f.id")
    List<FavouriteRow> findPlaceAndAreaRowsByUserId(@Param("userId") Long userId);

    /**
     * Stars one place. A second insert of the same place violates the partial unique index.
     *
     * @param userId     the reader
     * @param locationId the location
     * @param createdAt  when it was starred
     * @return rows inserted (1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query(value = "INSERT INTO user_favourite (user_id, location_id, created_at) "
            + "VALUES (:userId, :locationId, :createdAt)", nativeQuery = true)
    int insertPlace(@Param("userId") Long userId, @Param("locationId") Long locationId,
            @Param("createdAt") LocalDateTime createdAt);

    /**
     * Stars one area. A second insert of the same area violates the partial unique index.
     *
     * @param userId    the reader
     * @param areaId    the area
     * @param createdAt when it was starred
     * @return rows inserted (1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query(value = "INSERT INTO user_favourite (user_id, area_id, created_at) "
            + "VALUES (:userId, :areaId, :createdAt)", nativeQuery = true)
    int insertArea(@Param("userId") Long userId, @Param("areaId") Long areaId,
            @Param("createdAt") LocalDateTime createdAt);

    /**
     * Watches one almanac date. A second insert of the same key violates the partial unique index.
     *
     * @param userId     the reader
     * @param almanacKey {@code <type>:<start date>}
     * @param createdAt  when it was watched
     * @return rows inserted (1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query(value = "INSERT INTO user_favourite (user_id, almanac_key, created_at) "
            + "VALUES (:userId, :almanacKey, :createdAt)", nativeQuery = true)
    int insertDate(@Param("userId") Long userId, @Param("almanacKey") String almanacKey,
            @Param("createdAt") LocalDateTime createdAt);

    /**
     * Un-watches some of a reader's dates.
     *
     * @param userId the reader
     * @param keys   the almanac keys to drop
     * @return rows deleted
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query("delete from UserFavouriteEntity f where f.userId = :userId and f.almanacKey in :keys")
    int deleteDates(@Param("userId") Long userId, @Param("keys") Collection<String> keys);

    /**
     * Un-stars some of a reader's places.
     *
     * @param userId      the reader
     * @param locationIds the locations to drop
     * @return rows deleted
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query("delete from UserFavouriteEntity f where f.userId = :userId and f.locationId in :locationIds")
    int deletePlaces(@Param("userId") Long userId, @Param("locationIds") Collection<Long> locationIds);

    /**
     * Un-stars some of a reader's areas.
     *
     * @param userId  the reader
     * @param areaIds the areas to drop
     * @return rows deleted
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Transactional
    @Query("delete from UserFavouriteEntity f where f.userId = :userId and f.areaId in :areaIds")
    int deleteAreas(@Param("userId") Long userId, @Param("areaIds") Collection<Long> areaIds);
}
