package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Spring Data repository for {@link SchedulerFireLogEntity}.
 *
 * <p>A fire's outcome is written only by {@link #complete}: the entity maps every other column
 * {@code updatable = false}, so a whole-entity save cannot rewrite a fire's start or key.
 */
public interface SchedulerFireLogRepository extends JpaRepository<SchedulerFireLogEntity, Long> {

    /**
     * Writes the four outcome columns of one fire, touching nothing else.
     *
     * @param id            the fire-log row id
     * @param completedAt   when the fire ended
     * @param status        how it ended
     * @param summary       the sanitised one-line summary, or null
     * @param pipelineRunId the pipeline run the fire started, or null
     * @return rows updated (0 or 1)
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SchedulerFireLogEntity f SET f.completedAt = :completedAt, f.status = :status, "
            + "f.summary = :summary, f.pipelineRunId = :pipelineRunId WHERE f.id = :id")
    int complete(@Param("id") Long id, @Param("completedAt") Instant completedAt,
            @Param("status") SchedulerFireStatus status, @Param("summary") String summary,
            @Param("pipelineRunId") Long pipelineRunId);

    /**
     * Deletes every fire that started before the cutoff, in one statement.
     *
     * @param cutoff rows with {@code fired_at} strictly before this are deleted
     * @return rows deleted
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM SchedulerFireLogEntity f WHERE f.firedAt < :cutoff")
    int deleteByFiredAtBefore(@Param("cutoff") Instant cutoff);

    /**
     * Marks the {@code RUNNING} fires that started before {@code before} as {@code FAILED}: the process
     * that was running them is gone.
     *
     * @param before  RUNNING rows with {@code fired_at} strictly before this are marked
     * @param summary the summary to record on each
     * @return rows marked
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SchedulerFireLogEntity f SET "
            + "f.status = com.gregochr.goldenhour.entity.SchedulerFireStatus.FAILED, f.summary = :summary "
            + "WHERE f.status = com.gregochr.goldenhour.entity.SchedulerFireStatus.RUNNING "
            + "AND f.firedAt < :before")
    int markOrphans(@Param("before") Instant before, @Param("summary") String summary);
}
