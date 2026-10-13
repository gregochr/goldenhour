package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link SchedulerJobConfigEntity}.
 */
public interface SchedulerJobConfigRepository extends JpaRepository<SchedulerJobConfigEntity, Long> {

    /**
     * Finds a job config by its unique key.
     *
     * @param jobKey the job key (e.g. "tide_refresh")
     * @return the config entity, if present
     */
    Optional<SchedulerJobConfigEntity> findByJobKey(String jobKey);

    /**
     * Returns all job configs ordered by ID ascending.
     *
     * @return all configs in insertion order
     */
    List<SchedulerJobConfigEntity> findAllByOrderByIdAsc();

    /**
     * Writes the three schedule-type columns together, touching nothing else.
     *
     * <p>{@code schedule_type}, {@code solar_event} and {@code solar_offset_minutes} are
     * {@code updatable = false} on the entity, so no {@code setX()} then {@code save()} can write
     * them (a whole-entity save from a stale copy could otherwise rewrite a type switch, or
     * overwrite a value this build does not recognise with a guess). This is the only writer after
     * insert, and writing the three in one statement means they can never be stored out of step.
     * {@code clearAutomatically} drops the persistence context so the next read sees the new
     * values rather than a stale managed copy.
     *
     * @param id            the row id
     * @param scheduleType  the schedule type name
     * @param solarEvent    the solar event name, or null for a non-solar type
     * @param offsetMinutes the offset in minutes before the event, or null for a non-solar type
     * @param updatedAt     the modification instant
     * @return rows updated (0 or 1)
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SchedulerJobConfigEntity c SET c.scheduleTypeRaw = :scheduleType, "
            + "c.solarEventRaw = :solarEvent, c.solarOffsetMinutes = :offsetMinutes, "
            + "c.updatedAt = :updatedAt WHERE c.id = :id")
    int updateScheduleType(@Param("id") Long id, @Param("scheduleType") String scheduleType,
            @Param("solarEvent") String solarEvent, @Param("offsetMinutes") Integer offsetMinutes,
            @Param("updatedAt") Instant updatedAt);
}
