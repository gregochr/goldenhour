package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AuroraAlertSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Spring Data repository for {@link AuroraAlertSnapshotEntity}, the one-row standing-alert table.
 */
public interface AuroraAlertSnapshotRepository extends JpaRepository<AuroraAlertSnapshotEntity, Short> {

    /**
     * Confirms the alert for {@code night}: moves {@code night_date} forward and drops any hold. Writes
     * nothing when the row is already at that night (or later) with no hold.
     *
     * @param night the night to confirm
     * @return rows updated (0 or 1)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AuroraAlertSnapshotEntity s SET s.nightDate = :night, s.heldLevel = NULL, "
            + "s.heldTrigger = NULL WHERE s.id = 1 AND (s.nightDate < :night OR s.heldLevel IS NOT NULL)")
    int confirmNight(@Param("night") LocalDate night);

    /**
     * Records the hold a night poll makes on the standing alert.
     *
     * @param level   the held level name
     * @param trigger the held trigger name
     * @return rows updated (0 or 1)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AuroraAlertSnapshotEntity s SET s.heldLevel = :level, s.heldTrigger = :trigger "
            + "WHERE s.id = 1")
    int setHold(@Param("level") String level, @Param("trigger") String trigger);
}
