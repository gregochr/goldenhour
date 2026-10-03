package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
     * @return the newest settled trigger time, or null if no cycle has been settled
     */
    @Query("SELECT MAX(p.triggerTime) FROM PipelineRunEntity p WHERE p.failuresSettledAt IS NOT NULL")
    Instant findNewestSettledTriggerTime();

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
}
