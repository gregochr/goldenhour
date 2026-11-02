package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link JobRunEntity}.
 */
public interface JobRunRepository extends JpaRepository<JobRunEntity, Long> {

    /**
     * The runs of a type whose {@code notes} are exactly one of the given texts: a batch run whose link to its
     * batch row was never written is found by the {@code "Anthropic batch: {id}"} its notes carry.
     *
     * @param runType the run type
     * @param notes   the exact notes to match
     * @return the matching runs
     */
    List<JobRunEntity> findByRunTypeAndNotesIn(RunType runType, Collection<String> notes);

    /**
     * Finds recent job runs by run type, ordered by start time descending. The id tie-break keeps
     * offset paging stable when runs share a start second.
     *
     * @param runType  the run type to filter by
     * @param pageable pagination configuration
     * @return list of job runs
     */
    List<JobRunEntity> findByRunTypeOrderByStartedAtDescIdDesc(RunType runType, Pageable pageable);

    /**
     * Finds job runs that started after a given time, ordered by start time descending. The id
     * tie-break keeps offset paging stable when runs share a start second.
     *
     * @param since    the start time threshold
     * @param pageable pagination configuration
     * @return list of job runs
     */
    List<JobRunEntity> findByStartedAtAfterOrderByStartedAtDescIdDesc(LocalDateTime since, Pageable pageable);

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

    /**
     * Job runs started at or after a UTC time, newest first, ties by id descending.
     *
     * @param since    the inclusive lower bound (naive UTC, like {@code started_at})
     * @param pageable the page (used as a row cap)
     * @return the runs
     */
    List<JobRunEntity> findByStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(LocalDateTime since,
            Pageable pageable);

    /**
     * Job runs with no completion time that started at or after a UTC time: the candidates for "running".
     *
     * @param since the inclusive lower bound (naive UTC)
     * @return the open runs
     */
    List<JobRunEntity> findByCompletedAtIsNullAndStartedAtGreaterThanEqual(LocalDateTime since);

    /**
     * The newest runs of a type started by the scheduler or by a person, newest first. Backs the
     * Scheduler's recent runs for {@code tide_refresh} (scheduled TIDE runs only).
     *
     * @param runType           the run type
     * @param triggeredManually true for runs a person started, false for scheduled ones
     * @param pageable          the page (used as a row cap)
     * @return the runs, newest first, ties by id descending
     */
    List<JobRunEntity> findByRunTypeAndTriggeredManuallyOrderByStartedAtDescIdDesc(RunType runType,
            Boolean triggeredManually, Pageable pageable);

    /**
     * The newest runs of a type whose Anthropic batch is of the given type, newest first. A run is matched
     * through {@code forecast_batch.job_run_id}, or, when that link was never written, through the
     * {@code "Anthropic batch: {id}"} its {@code notes} carry. Backs the Scheduler's recent runs for
     * {@code aurora_batch_evaluation}, whose runs are {@code SCHEDULED_BATCH} like the forecast batches'.
     *
     * @param runType   the run type (SCHEDULED_BATCH)
     * @param batchType the batch type (AURORA)
     * @param pageable  the page (used as a row cap)
     * @return the runs, newest first, ties by id descending
     */
    @Query("SELECT j FROM JobRunEntity j WHERE j.runType = :runType AND ("
            + "j.id IN (SELECT b.jobRunId FROM ForecastBatchEntity b "
            + "WHERE b.batchType = :batchType AND b.jobRunId IS NOT NULL) "
            + "OR j.notes IN (SELECT CONCAT('Anthropic batch: ', b2.anthropicBatchId) FROM ForecastBatchEntity b2 "
            + "WHERE b2.batchType = :batchType AND b2.jobRunId IS NULL)) "
            + "ORDER BY j.startedAt DESC, j.id DESC")
    List<JobRunEntity> findByRunTypeAndBatchType(@Param("runType") RunType runType,
            @Param("batchType") ForecastBatchEntity.BatchType batchType, Pageable pageable);

    /**
     * The exchange rates recorded on job runs, newest run first, ignoring runs that recorded none.
     * Read with a one-row page to get the latest.
     *
     * @param pageable the page to read
     * @return GBP per USD values, newest first
     */
    @Query("SELECT j.exchangeRateGbpPerUsd FROM JobRunEntity j WHERE j.exchangeRateGbpPerUsd IS NOT NULL "
            + "ORDER BY j.startedAt DESC, j.id DESC")
    List<Double> findLatestExchangeRates(Pageable pageable);
}
