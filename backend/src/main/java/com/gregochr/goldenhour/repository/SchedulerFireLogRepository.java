package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

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

    /**
     * The newest fire of every job, in one statement: the rows whose {@code fired_at} is the job's maximum, read
     * through the {@code (job_key, fired_at DESC)} index. A job with two rows at the same instant appears twice;
     * the caller keeps the higher id.
     *
     * @return at most one row per job (a job that has never fired has none)
     */
    @Query("SELECT f FROM SchedulerFireLogEntity f WHERE f.firedAt = "
            + "(SELECT MAX(g.firedAt) FROM SchedulerFireLogEntity g WHERE g.jobKey = f.jobKey)")
    List<SchedulerFireLogEntity> findNewestPerJob();

    /**
     * A job's newest completed fires with one of the given statuses, for its typical duration. Capped at 200 so a
     * once-a-minute poller does not load its fortnight of rows.
     *
     * @param jobKey   the job key
     * @param statuses the statuses to include
     * @return up to 200 fires, newest first, each with a {@code completed_at}
     */
    List<SchedulerFireLogEntity> findTop200ByJobKeyAndStatusInAndCompletedAtIsNotNullOrderByFiredAtDesc(
            String jobKey, Collection<SchedulerFireStatus> statuses);

    /**
     * One job's number of fires with one status in a window.
     */
    interface FireCount {

        /**
         * The job key.
         *
         * @return the job key
         */
        String getJobKey();

        /**
         * The status counted.
         *
         * @return the status
         */
        SchedulerFireStatus getStatus();

        /**
         * How many fires.
         *
         * @return the count
         */
        long getTotal();
    }

    /**
     * The fires of the given jobs that started in a window, oldest first, in one statement.
     *
     * @param jobKeys the jobs (never empty: an empty collection is a malformed {@code IN})
     * @param from    inclusive start
     * @param to      exclusive end
     * @return the matching fires
     */
    List<SchedulerFireLogEntity> findByJobKeyInAndFiredAtGreaterThanEqualAndFiredAtLessThanOrderByFiredAtAsc(
            Collection<String> jobKeys, Instant from, Instant to);

    /**
     * The fires of the given jobs that started in a window and ended with one of the given statuses, oldest first.
     *
     * @param jobKeys  the jobs (never empty)
     * @param statuses the statuses to include
     * @param from     inclusive start
     * @param to       exclusive end
     * @return the matching fires
     */
    List<SchedulerFireLogEntity>
            findByJobKeyInAndStatusInAndFiredAtGreaterThanEqualAndFiredAtLessThanOrderByFiredAtAsc(
                    Collection<String> jobKeys, Collection<SchedulerFireStatus> statuses, Instant from, Instant to);

    /**
     * How many fires each job had with each status in a window, in one GROUP BY.
     *
     * @param from inclusive start
     * @param to   exclusive end
     * @return one row per (job, status) that occurred
     */
    @Query("SELECT f.jobKey AS jobKey, f.status AS status, COUNT(f) AS total FROM SchedulerFireLogEntity f "
            + "WHERE f.firedAt >= :from AND f.firedAt < :to GROUP BY f.jobKey, f.status")
    List<FireCount> countByJobAndStatus(@Param("from") Instant from, @Param("to") Instant to);
}
