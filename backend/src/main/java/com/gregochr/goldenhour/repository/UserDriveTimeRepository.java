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

    /**
     * Lists which measured home has a stored drive time to which location: ids only, for the admin
     * unroutable-places read. The origins are selected by the same conditions as
     * {@code AppUserRepository.findMeasuredHomeStamps} (enabled, of the given roles, with a home and a stamp) in a
     * subquery, so no id list is bound (Postgres caps a statement at 32767 binds).
     *
     * @param roles the roles whose homes are measured (Pro and Admin)
     * @return one {@link DrivePair} per stored row of a measured home
     */
    @Query("SELECT new com.gregochr.goldenhour.repository.DrivePair(udt.userId, udt.locationId) "
            + "FROM UserDriveTimeEntity udt WHERE udt.userId IN (SELECT u.id FROM AppUserEntity u "
            + "WHERE u.enabled = true AND u.role IN :roles AND u.homeLatitude IS NOT NULL "
            + "AND u.homeLongitude IS NOT NULL AND u.driveTimesCalculatedAt IS NOT NULL)")
    List<DrivePair> findMeasuredHomePairs(@Param("roles") List<com.gregochr.goldenhour.entity.UserRole> roles);
}
