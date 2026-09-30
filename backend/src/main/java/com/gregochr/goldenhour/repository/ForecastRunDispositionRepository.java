package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastRunDispositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link ForecastRunDispositionEntity}. Queries are keyed by
 * {@code job_run_id} (the cycle's first job run), with an aggregation helper
 * for the summary counts surfaced in the Job Run detail UI and a delete hook
 * for the retention cleanup job.
 */
public interface ForecastRunDispositionRepository
        extends JpaRepository<ForecastRunDispositionEntity, Long> {

    /**
     * Returns every disposition row for the given job run, ordered by
     * disposition then location name for stable UI rendering.
     *
     * @param jobRunId the cycle's first job run id
     * @return ordered list of dispositions (possibly empty)
     */
    List<ForecastRunDispositionEntity> findByJobRunIdOrderByDispositionAscLocationNameAsc(
            Long jobRunId);

    /**
     * Returns the rows for the given job run filtered to a single disposition
     * category — backs the per-category drill-down in the UI.
     *
     * @param jobRunId    the cycle's first job run id
     * @param disposition the stored disposition string (e.g. "SKIPPED_TRIAGED")
     * @return ordered list of dispositions in that category
     */
    List<ForecastRunDispositionEntity> findByJobRunIdAndDispositionOrderByLocationNameAsc(
            Long jobRunId, String disposition);

    /**
     * Aggregated counts per disposition for a given job run — backs the
     * summary row in the Job Run detail UI without loading every row.
     *
     * <p>Returns an array per row: {@code [disposition (String), count (Long)]}.
     *
     * @param jobRunId the cycle's first job run id
     * @return list of {@code [disposition, count]} tuples
     */
    @Query("SELECT d.disposition, COUNT(d) FROM ForecastRunDispositionEntity d "
            + "WHERE d.jobRunId = :jobRunId GROUP BY d.disposition")
    List<Object[]> countByDispositionForJobRun(@Param("jobRunId") Long jobRunId);

    /**
     * Deletes disposition rows created before the given cutoff. Called by the
     * scheduled {@code disposition_cleanup} job to bound table growth.
     *
     * @param cutoff retention boundary — rows older than this are deleted
     * @return number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM ForecastRunDispositionEntity d WHERE d.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);

    /**
     * For every (location name, evaluation date, event type) with at least one nightly Gate 4
     * stability skip in the range, returns the instant of the <em>most recent</em> such skip.
     *
     * <p>Backs the stale-rating retraction rule: a cached rating or {@code forecast_evaluation} row
     * written before this instant is evidence the pipeline has since decided against — the slot
     * declined a re-look because its grid cell was too unsettled for its horizon — and must not be
     * served on its own. See {@code EvaluationViewService.isRetractedByStabilitySkip}.
     *
     * <p>Filtered to {@code SKIPPED_STABILITY} alone — the nightly Gate 4 skip
     * ({@link com.gregochr.goldenhour.entity.DispositionCategory#SKIPPED_STABILITY}) — which is the
     * only category that represents a decision <em>against</em> re-scoring a slot the pipeline could
     * otherwise reach. Every other category is excluded by construction: a region-level
     * {@code SKIPPED_CACHED} is a deliberate reuse of a fresh score, not a decision against it; a
     * past-date, travel-day, error, triage or unknown-location skip says nothing about whether a
     * rating is stale; and the intraday cycle's {@code SKIPPED_NO_REFRESH_NEEDED} means "a later
     * look is already guaranteed", the opposite of "declined to look again".
     *
     * <p>One bulk query per serve, grouped in the database rather than fetched row-by-row — the
     * table holds ~2k rows/day (30-day retention), and every caller bounds {@code start}/{@code end}
     * to its own served window rather than scanning the whole table.
     *
     * @param start first evaluation date to include (inclusive)
     * @param end   last evaluation date to include (inclusive)
     * @return rows of {@code [locationName (String), evaluationDate (LocalDate), eventType
     *         (String), lastSkippedAt (Instant)]}, one per slot with at least one stability skip
     */
    @Query("SELECT d.locationName, d.evaluationDate, d.eventType, MAX(d.createdAt) "
            + "FROM ForecastRunDispositionEntity d "
            + "WHERE d.disposition = 'SKIPPED_STABILITY' "
            + "AND d.evaluationDate BETWEEN :start AND :end "
            + "GROUP BY d.locationName, d.evaluationDate, d.eventType")
    List<Object[]> findLatestStabilitySkipTimestamps(
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * For every (location name, evaluation date, event type) with at least one disposition in the
     * range OTHER THAN {@code SKIPPED_CACHED}, returns the disposition AND {@code created_at} of
     * the <em>most recent</em> such row.
     *
     * <p>Backs the verdict-minimum-sample rule's "examined" evidence ({@code
     * VerdictSampleGate#examinedCount}, Codex review of #943, P1-A): a voting slot counts as
     * examined only when the BATCH's own most recent decision for it was {@code SKIPPED_TRIAGED}
     * — never the briefing's own weather-triage {@code Verdict}, which is computed independently
     * across the whole horizon and can disagree with what the batch actually looked at (or never
     * looked at at all, for a slot Gate 4 stability-skipped before the batch ever fetched fresh
     * weather for it).
     *
     * <p>⚠️ <b>{@code SKIPPED_CACHED} is deliberately excluded from BOTH sides of the correlated
     * subquery, not merely filtered from the outer result.</b> A region-level cache reuse
     * ({@code SKIPPED_CACHED}) is not a decision about any one slot — it means "this region's
     * existing ratings were judged fresh and reused" — so a slot triaged last night and then
     * reported {@code SKIPPED_CACHED} tonight must still read as examined via last night's triage,
     * not as un-examined because a newer, slot-blind row now sits on top of it. Excluding the
     * category from the inner {@code MAX(created_at)} scope too (not just the outer filter) is
     * what makes the triaged decision "the latest" again rather than merely visible-but-superseded.
     *
     * <p>One bulk query per serve, grouped in the database rather than fetched row-by-row, bounded
     * to the caller's own served window — never called per region or per slot. Same table, same
     * 30-day retention as {@link #findLatestStabilitySkipTimestamps} covers the served horizon.
     *
     * <p>⚠️ A tie at the same {@code created_at} between two different non-cached categories for
     * one slot returns both rows; {@code EvaluationViewService.loadTriagedByBatch} folds a tie to
     * "not examined" (only counts a slot where every row at the max instant agrees it is
     * {@code SKIPPED_TRIAGED}), the same safe-under-ambiguity direction every other tie fold in
     * this repository's callers takes.
     *
     * @param start first evaluation date to include (inclusive)
     * @param end   last evaluation date to include (inclusive)
     * @return rows of {@code [locationName (String), evaluationDate (LocalDate), eventType
     *         (String), disposition (String), createdAt (Instant)]}, one per slot with at least one
     *         non-{@code SKIPPED_CACHED} disposition — the disposition and instant of whichever
     *         such row is most recent for that slot
     */
    @Query("SELECT d.locationName, d.evaluationDate, d.eventType, d.disposition, d.createdAt "
            + "FROM ForecastRunDispositionEntity d "
            + "WHERE d.disposition <> 'SKIPPED_CACHED' "
            + "AND d.evaluationDate BETWEEN :start AND :end "
            + "AND d.createdAt = ("
            + "    SELECT MAX(d2.createdAt) FROM ForecastRunDispositionEntity d2 "
            + "    WHERE d2.locationName = d.locationName "
            + "    AND d2.evaluationDate = d.evaluationDate "
            + "    AND d2.eventType = d.eventType "
            + "    AND d2.disposition <> 'SKIPPED_CACHED'"
            + ")")
    List<Object[]> findLatestNonCachedDispositions(
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * For the given slots (one date, one event type, a set of location names), returns the OWNING
     * CYCLE'S {@code trigger_time} of every non-{@code SKIPPED_CACHED} disposition recorded by a
     * pipeline run that started strictly after {@code thresholdInstant} — Phase 2 of the round-13
     * "gap 1" fix ({@code BriefingEvaluationService}'s per-batch disposition-supersede check).
     *
     * <p>⚠️ <b>Joins through {@code forecast_batch}, and comparing the disposition's OWN
     * {@code created_at} against the threshold instead would be wrong.</b> A disposition row
     * carries no pipeline run id of its own — {@code ForecastDispositionService#persist} anchors
     * every disposition from a cycle against that cycle's FIRST job run, and exactly one
     * {@code forecast_batch} row (the first bucket submitted that cycle) shares that same
     * {@code job_run_id} and also carries the cycle's real {@code pipeline_run_id} — so this is the
     * one join that recovers "which cycle wrote this disposition" without a schema migration
     * (both columns already existed). A disposition is always written a short time AFTER its own
     * cycle starts (collection happens, then the disposition insert follows the first batch
     * submission), so every cycle's OWN {@code EVALUATED}/{@code FORCE_EVALUATED}/
     * {@code SKIPPED_STABILITY} row for a slot it just decided has a {@code created_at} strictly
     * after that SAME cycle's own trigger time — comparing {@code created_at} directly against an
     * incoming result's submission instant (which, for an orchestrated batch, IS that same trigger
     * time) would make every cycle's own disposition look like it "supersedes" the very result it
     * documents. Comparing the OWNING CYCLE's trigger time instead correctly reads a same-cycle
     * disposition as simultaneous (excluded by the caller's strict {@code isAfter} test on the
     * returned value against the result's own submission instant), never later.
     *
     * <p>Returns the raw {@code [locationName (String), cycleTriggerTime (Instant)]} pairs rather
     * than a single winner so the caller can resolve each location's own verdict independently
     * against that location's own submission instant — necessary because although every location in
     * one batch call shares the same date and event type, a caller must not assume they all share
     * one submission instant (a retry batch's recovered locations still carry their precursor
     * cycle's instant, but nothing enforces every batch that ever calls this shares exactly one).
     * Excludes {@code SKIPPED_CACHED} for the same reason {@link #findLatestNonCachedDispositions}
     * does — a region-level cache reuse is not a decision about any one slot.
     *
     * <p>One bulk query, only reached when {@code PipelineRunRepository#existsByTriggerTimeAfter}
     * has already confirmed a later cycle exists at all — never one per location.
     *
     * @param date             the slots' evaluation date
     * @param eventType        the slots' stored event type string (e.g. {@code "SUNRISE"})
     * @param locationNames    the candidate location names to check
     * @param thresholdInstant only a disposition whose owning cycle started strictly after this
     *                         counts
     * @return {@code [locationName, cycleTriggerTime]} pairs, one per qualifying disposition (a
     *         location may appear more than once if more than one later cycle wrote a disposition
     *         for it — the caller takes the latest)
     */
    @Query("SELECT d.locationName, p.triggerTime "
            + "FROM ForecastRunDispositionEntity d, ForecastBatchEntity b, PipelineRunEntity p "
            + "WHERE d.jobRunId = b.jobRunId "
            + "AND b.pipelineRunId = p.id "
            + "AND d.evaluationDate = :date "
            + "AND d.eventType = :eventType "
            + "AND d.locationName IN (:locationNames) "
            + "AND d.disposition <> 'SKIPPED_CACHED' "
            + "AND p.triggerTime > :thresholdInstant")
    List<Object[]> findSupersedingCycleTriggerTimes(
            @Param("date") LocalDate date,
            @Param("eventType") String eventType,
            @Param("locationNames") Collection<String> locationNames,
            @Param("thresholdInstant") Instant thresholdInstant);
}
