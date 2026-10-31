package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link PipelineRunEntity}.
 */
public interface PipelineRunRepository extends JpaRepository<PipelineRunEntity, Long> {

    /**
     * Returns all pipeline runs in the given status, newest first by trigger time.
     *
     * @param status the status to filter on
     * @return matching runs ordered by trigger time descending
     */
    List<PipelineRunEntity> findByStatusOrderByTriggerTimeDesc(PipelineRunStatus status);

    /**
     * Returns the most recent N pipeline runs regardless of status.
     *
     * <p>Used by the Pipeline Run list view in the Operations tab.
     *
     * @return up to 50 recent runs, newest first
     */
    List<PipelineRunEntity> findTop50ByOrderByTriggerTimeDesc();

    /**
     * Returns the most recent run of the given cycle type whose trigger time
     * falls within {@code [start, end]} — used to find an intraday run's
     * same-day NIGHTLY baseline (start = London start-of-day, end = the
     * intraday run's own trigger time, so only the morning's nightly run is
     * eligible).
     *
     * @param cycleType the cycle type to match (NIGHTLY for the baseline)
     * @param start     inclusive lower bound (London start-of-day instant)
     * @param end       inclusive upper bound (the intraday run's trigger time)
     * @return the latest matching run, or empty if none
     */
    Optional<PipelineRunEntity>
            findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                    CycleType cycleType, Instant start, Instant end);

    /**
     * The cycle whose {@code trigger_time} is exactly the given instant — how a served result's
     * {@code submittedAt} (the cycle's own trigger time, stamped on every batch result the cycle
     * submitted) is turned back into a cycle id for the outcome rating's provenance. An ad-hoc batch
     * or a synchronous run carries an instant no cycle has, and matches nothing.
     *
     * <p>{@code trigger_time} is not unique, so the lowest id answers rather than the lookup failing
     * on a coincidence.
     *
     * @param triggerTime the exact trigger instant
     * @return the earliest-created run with that trigger time, or empty
     */
    Optional<PipelineRunEntity> findFirstByTriggerTimeOrderByIdAsc(Instant triggerTime);

    /**
     * Returns every run of a cycle type triggered at or after an instant, oldest first. The
     * forecast-cycle watchdog reads it to see whether a cycle started and whether it is still
     * running; it changes nothing.
     *
     * @param cycleType the cycle type
     * @param since     inclusive lower bound on the trigger time
     * @return the matching runs, oldest first
     */
    List<PipelineRunEntity> findByCycleTypeAndTriggerTimeGreaterThanEqualOrderByTriggerTimeAsc(
            CycleType cycleType, Instant since);

    /**
     * Counts the runs of a cycle type, triggered at or after an instant, that count as "this cycle
     * already ran" for the solar catch-up: a run that got past submission.
     *
     * <p>A COMPLETED or DEGRADED run always counts (it ran every phase; completion clears the
     * phase). Otherwise a run counts only if it has a recorded phase outside {@code preSubmission}
     * — a run that reached WAIT, RETRY_FAILED or BRIEFING has batches that exist and are polled,
     * whether it is still RUNNING or later FAILED (e.g. the safety timeout). A RUNNING or FAILED
     * run in a pre-submission phase, or with no phase, does not count whatever its status: the
     * catch-up runs only at startup, where any such RUNNING run belongs to the dead JVM and is
     * about to be failed by the resume, so this is correct whichever startup listener runs first.
     *
     * @param cycleType     the cycle type
     * @param threshold     the inclusive lower bound on {@code trigger_time}
     * @param preSubmission the phases that mean nothing was submitted
     * @return how many such runs exist
     */
    @Query("SELECT COUNT(p) FROM PipelineRunEntity p WHERE p.cycleType = :cycleType "
            + "AND p.triggerTime >= :threshold AND (p.status IN "
            + "(com.gregochr.goldenhour.entity.PipelineRunStatus.COMPLETED, "
            + "com.gregochr.goldenhour.entity.PipelineRunStatus.DEGRADED) "
            + "OR (p.currentPhase IS NOT NULL AND p.currentPhase NOT IN :preSubmission))")
    long countRunsPastSubmission(@Param("cycleType") CycleType cycleType,
            @Param("threshold") Instant threshold,
            @Param("preSubmission") java.util.Collection<PipelinePhase> preSubmission);

    /**
     * The 14 most recent runs of a cycle type in the given status, newest first — the sample the
     * solar preview's median cycle duration is taken over.
     *
     * @param cycleType the cycle type
     * @param status    the status (COMPLETED)
     * @return up to 14 runs, newest trigger first
     */
    List<PipelineRunEntity> findTop14ByCycleTypeAndStatusOrderByTriggerTimeDesc(
            CycleType cycleType, PipelineRunStatus status);

    /**
     * Every pipeline run's {@code trigger_time} strictly after the given instant, ascending.
     *
     * <p>Round 14 replaces round 13's disposition-owning-cycle join entirely: a disposition
     * supersedes a result when the disposition's {@code created_at} is at or after the trigger time
     * of the FIRST pipeline run triggered after the result's own {@code submittedAt} — see
     * {@code SupersedingDispositionService}'s class javadoc for the full rule and why it needs no
     * {@code forecast_batch} join at all (a round-13 defect: a disposition-only "anchor run", the
     * shape every failed batch submission produces, has no {@code forecast_batch} row to join
     * through, so the round-13 join could not see it).
     *
     * <p>This table alone answers "is there a later cycle at all, and when did it start" — it never
     * depends on whether that cycle ever produced a batch, a result, or anything else. The common
     * case — no cycle has started after the given instant, because the incoming result is not
     * actually delayed — returns an empty list and callers stop there, at one cheap indexed query.
     * An ascending, ALL-later-triggers list (not merely the first) is returned so one caller can
     * resolve several different results' own "next trigger" from the same load, when those results
     * carry different {@code submittedAt} values in one batch/merge call.
     *
     * @param threshold the instant to compare against — an incoming result's own submission instant
     * @return every later trigger time, oldest first; empty when none
     */
    @Query("SELECT p.triggerTime FROM PipelineRunEntity p WHERE p.triggerTime > :threshold "
            + "ORDER BY p.triggerTime ASC")
    List<Instant> findTriggerTimesAfter(@Param("threshold") Instant threshold);

    /**
     * Records the job run a cycle's dispositions were persisted onto, touching no other column.
     *
     * @param id       the pipeline run id
     * @param jobRunId the job run holding the cycle's dispositions
     * @return rows updated (0 or 1)
     */
    @Transactional
    @Modifying
    @Query("UPDATE PipelineRunEntity p SET p.dispositionJobRunId = :jobRunId WHERE p.id = :id")
    int recordDispositionJobRun(@Param("id") Long id, @Param("jobRunId") Long jobRunId);

    /**
     * Reads the job run a cycle's dispositions were persisted onto, straight from the database (a
     * scalar projection, never a cached entity).
     *
     * @param id the pipeline run id
     * @return the job run id, or empty if none was recorded or the run does not exist
     */
    @Query("SELECT p.dispositionJobRunId FROM PipelineRunEntity p WHERE p.id = :id")
    Optional<Long> findDispositionJobRunId(@Param("id") Long id);

    /**
     * Claims a cycle for its location-failure settle: stamps {@code failures_settled_at} only if it
     * is still null. A returned 1 means this caller claimed it; 0 means it was already claimed (or
     * the run does not exist). The conditional update is the exactly-once guard, durable across a
     * restart.
     *
     * @param id  the pipeline run id
     * @param now the settle instant
     * @return rows updated (0 or 1)
     */
    @Transactional
    @Modifying
    @Query("UPDATE PipelineRunEntity p SET p.failuresSettledAt = :now "
            + "WHERE p.id = :id AND p.failuresSettledAt IS NULL")
    int claimFailureSettle(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Returns the trigger time of the newest cycle whose failures have been settled, used to refuse
     * an older cycle after a newer one.
     *
     * @param cycleTypes the cycle types whose settles count (those that give failure evidence); a
     *                   settled cycle of any other type does not move the result
     * @return the newest settled trigger time, or null if no such cycle has been settled
     */
    @Query("SELECT MAX(p.triggerTime) FROM PipelineRunEntity p WHERE p.failuresSettledAt IS NOT NULL "
            + "AND p.cycleType IN :cycleTypes")
    Instant findNewestSettledTriggerTime(@Param("cycleTypes") Collection<CycleType> cycleTypes);

    /**
     * Returns the pipeline runs whose location-failure settle has not been claimed, triggered at or
     * after {@code since} and not in the excluded status, oldest trigger first. This is the
     * location auto-disable's durable retry: a cycle whose settle failed, or that was never settled
     * (a restart, a timeout, a failure before the briefing), is found here by the next sweep.
     *
     * @param since    inclusive lower bound on the trigger time
     * @param excluded the status to leave out (RUNNING: a run still in flight settles at its tail)
     * @return the unsettled runs in trigger order
     */
    @Query("SELECT p FROM PipelineRunEntity p WHERE p.failuresSettledAt IS NULL "
            + "AND p.triggerTime >= :since AND p.status <> :excluded ORDER BY p.triggerTime ASC")
    List<PipelineRunEntity> findUnsettledSince(@Param("since") Instant since,
            @Param("excluded") PipelineRunStatus excluded);

    /**
     * Returns the pipeline runs in exactly the given status whose location-failure settle has not
     * been claimed, triggered at or after {@code since}, oldest trigger first. The auto-disable
     * uses it to see the RUNNING runs the sweep may not settle but that still block a newer
     * cycle's tail from counting failures.
     *
     * @param since  inclusive lower bound on the trigger time
     * @param status the one status to return
     * @return the unsettled runs in that status, in trigger order
     */
    @Query("SELECT p FROM PipelineRunEntity p WHERE p.failuresSettledAt IS NULL "
            + "AND p.triggerTime >= :since AND p.status = :status ORDER BY p.triggerTime ASC")
    List<PipelineRunEntity> findUnsettledSinceWithStatus(@Param("since") Instant since,
            @Param("status") PipelineRunStatus status);

    /**
     * Of the given disposition job-run ids, those whose cycle has ended: a pipeline run whose
     * {@code disposition_job_run_id} is the id, that is not {@code RUNNING}, and that has no
     * {@code FORECAST} batch outside {@code terminal} (the rule {@code LocationFailureService} uses
     * to defer a cycle whose batches are still polling; a retry batch shares the run id, so a
     * cycle whose retry is still polling has not ended). An id with no pipeline run is absent.
     *
     * @param ids       disposition job-run ids to test
     * @param running   the status that means still in flight
     * @param batchType the batch type that counts ({@code FORECAST})
     * @param terminal  the batch statuses a poller has finished with
     * @return the ids whose cycle has ended
     */
    @Query("SELECT p.dispositionJobRunId FROM PipelineRunEntity p "
            + "WHERE p.dispositionJobRunId IN :ids AND p.status <> :running "
            + "AND NOT EXISTS (SELECT 1 FROM ForecastBatchEntity b WHERE b.pipelineRunId = p.id "
            + "AND b.batchType = :batchType AND b.status NOT IN :terminal)")
    List<Long> findEndedDispositionJobRunIds(@Param("ids") Collection<Long> ids,
            @Param("running") PipelineRunStatus running,
            @Param("batchType") ForecastBatchEntity.BatchType batchType,
            @Param("terminal") Collection<ForecastBatchEntity.BatchStatus> terminal);

    /**
     * Of the given disposition job-run ids, those whose cycle has ended; see {@link
     * #findEndedDispositionJobRunIds(Collection, PipelineRunStatus, ForecastBatchEntity.BatchType,
     * Collection)} for the rule.
     *
     * @param ids disposition job-run ids to test
     * @return the ids whose cycle has ended
     */
    default List<Long> endedDispositionJobRunIds(Collection<Long> ids) {
        return findEndedDispositionJobRunIds(ids, PipelineRunStatus.RUNNING,
                ForecastBatchEntity.BatchType.FORECAST, ForecastBatchEntity.BatchStatus.TERMINAL);
    }

    /**
     * Pipeline runs triggered at or after an instant, newest first, ties by id descending.
     *
     * @param since the inclusive lower bound
     * @return the cycles
     */
    List<PipelineRunEntity> findByTriggerTimeGreaterThanEqualOrderByTriggerTimeDescIdDesc(Instant since);

    /**
     * The job run ids that anchor a cycle's dispositions, from a minimum id upwards. The Runs list hides
     * these from its top level.
     *
     * @param minId the smallest job run id of interest
     * @return the anchor job run ids
     */
    @Query("SELECT p.dispositionJobRunId FROM PipelineRunEntity p "
            + "WHERE p.dispositionJobRunId IS NOT NULL AND p.dispositionJobRunId >= :minId")
    List<Long> findDispositionJobRunIdsFrom(@Param("minId") long minId);
}
