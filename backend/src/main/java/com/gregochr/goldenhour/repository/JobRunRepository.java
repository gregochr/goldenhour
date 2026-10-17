package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link JobRunEntity}.
 */
public interface JobRunRepository extends JpaRepository<JobRunEntity, Long> {

    /**
     * Finds recent job runs by run type, ordered by start time descending.
     *
     * @param runType  the run type to filter by
     * @param pageable pagination configuration
     * @return list of job runs
     */
    List<JobRunEntity> findByRunTypeOrderByStartedAtDesc(RunType runType, Pageable pageable);

    /**
     * Finds all job runs that started after a given time, ordered descending.
     *
     * @param since the start time threshold
     * @return list of job runs
     */
    List<JobRunEntity> findByStartedAtAfterOrderByStartedAtDesc(LocalDateTime since);

    /**
     * Finds the newest run of a type that started at or after a moment: the day's run, when the
     * moment is the start of the UK civil day (Ask's {@code ASK} run is one per day).
     *
     * @param runType the run type
     * @param since   the earliest start, inclusive (UTC)
     * @return the newest such run, or empty
     */
    Optional<JobRunEntity> findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
            RunType runType, LocalDateTime since);

    /**
     * Counts the runs of a type, started either by a person or by the scheduler, at or after a
     * moment. Ask's Ready precompute counts its scheduled runs since UK midnight against its
     * per-day ceiling: the count is the durable record, so a restart cannot reset the ceiling.
     *
     * @param runType           the run type
     * @param triggeredManually true for runs a person started, false for scheduled ones
     * @param since             the earliest start, inclusive (UTC)
     * @return how many such runs there are
     */
    long countByRunTypeAndTriggeredManuallyAndStartedAtGreaterThanEqual(RunType runType,
            Boolean triggeredManually, LocalDateTime since);

    /**
     * Adds to a run's cost without reading or rewriting anything else on the row.
     *
     * <p>{@code JobRunService.completeRun} sums a run's cost once, at completion, and the Ask run is
     * completed when it is created, so each logged call adds its own cost here for the day's spend
     * to show in Operations. A column-scoped update, never a load-modify-save, because two
     * conversations finish at once. The persistence context is deliberately not cleared: nothing
     * reads this row back through JPA in the same session, and clearing would detach whatever else
     * the calling request holds.
     *
     * @param id    the job run id
     * @param micro the cost to add, in micro-dollars
     * @return rows updated (0 or 1)
     */
    @Modifying
    @Transactional
    @Query("UPDATE JobRunEntity j SET j.totalCostMicroDollars = COALESCE(j.totalCostMicroDollars, 0) "
            + "+ :micro WHERE j.id = :id")
    int addCostMicroDollars(@Param("id") Long id, @Param("micro") long micro);

    /**
     * Counts one finished question on a run: one more processed, and one more succeeded or failed.
     * Column-scoped for the same reason as {@link #addCostMicroDollars}.
     *
     * @param id        the job run id
     * @param succeeded 1 when the question was answered (or honestly declined), else 0
     * @param failed    1 when the engine failed, else 0
     * @return rows updated (0 or 1)
     */
    @Modifying
    @Transactional
    @Query("UPDATE JobRunEntity j SET "
            + "j.locationsProcessed = COALESCE(j.locationsProcessed, 0) + 1, "
            + "j.succeeded = COALESCE(j.succeeded, 0) + :succeeded, "
            + "j.failed = COALESCE(j.failed, 0) + :failed WHERE j.id = :id")
    int addQuestionOutcome(@Param("id") Long id, @Param("succeeded") int succeeded,
            @Param("failed") int failed);
}
