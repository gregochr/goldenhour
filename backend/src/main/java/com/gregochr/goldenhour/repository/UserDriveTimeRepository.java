package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data repository for per-user drive time records.
 */
public interface UserDriveTimeRepository extends JpaRepository<UserDriveTimeEntity, UserDriveTimeId> {

    /**
     * Returns all drive time records for a user.
     *
     * @param userId the user's primary key
     * @return list of drive time entities
     */
    List<UserDriveTimeEntity> findByUserId(Long userId);

    /**
     * Deletes all drive time records for a user (used before a full refresh).
     *
     * @param userId the user's primary key
     */
    @Modifying
    @Query("DELETE FROM UserDriveTimeEntity udt WHERE udt.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    /**
     * Deletes one user's rows for locations whose routing point changed after the roster was read, because a
     * measurement in flight routed to the old point (reach-scoped-card-plan D1a): the location then reads PENDING.
     *
     * @param userId       the user's primary key
     * @param rosterReadAt UTC wall-clock instant the roster was read
     */
    @Modifying
    @Query("DELETE FROM UserDriveTimeEntity udt WHERE udt.userId = :userId AND udt.locationId IN "
            + "(SELECT l.id FROM LocationEntity l WHERE l.driveTargetChangedAt > :rosterReadAt)")
    void deleteMovedSince(@Param("userId") Long userId, @Param("rosterReadAt") LocalDateTime rosterReadAt);

    /**
     * Deletes every user's drive time to one location. Used when the location's coordinates move: each row
     * describes a journey to the old point.
     *
     * @param locationId the location's primary key
     */
    @Modifying
    @Query("DELETE FROM UserDriveTimeEntity udt WHERE udt.locationId = :locationId")
    void deleteAllByLocationId(@Param("locationId") Long locationId);
}
