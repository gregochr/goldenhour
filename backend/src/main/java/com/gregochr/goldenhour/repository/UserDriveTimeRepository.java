package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
