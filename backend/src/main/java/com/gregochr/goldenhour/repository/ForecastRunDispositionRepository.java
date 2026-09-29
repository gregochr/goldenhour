package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastRunDispositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
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
     * For every (location name, evaluation date, event type) with at least one {@code EVALUATED}
     * or {@code FORCE_EVALUATED} disposition in the range, returns the disposition AND {@code
     * created_at} of the <em>most recent</em> such row.
     *
     * <p>Backs the verdict-minimum-sample rule's force-evaluation exemption
     * (owner decision, 2026-09-29 — see {@code docs/engineering/plan-verdict-consolidation-plan.md}
     * and {@code VerdictSampleGate}): a region's minimum-sample gate is waived when at least one of
     * its rated voting slots currently reads a rating written by a force evaluation
     * ({@code ForceEvalHeadlineSelector}). "Currently" is answered by the MOST RECENT of the two
     * evaluating categories, exactly the way {@link #findLatestStabilitySkipTimestamps} answers
     * "most recent decision against a slot" — a later ordinary {@code EVALUATED} run for the same
     * slot supersedes an earlier {@code FORCE_EVALUATED} one and ends the exemption.
     *
     * <p>⚠️ <b>{@code created_at} is the row's own timestamp, not the rating's.</b> A codex review
     * of #943 (P1-B) found that {@code ScheduledBatchEvaluationService.persistCycleDispositions}
     * writes every cycle's dispositions at SUBMISSION time, before any Claude result comes back —
     * so {@code FORCE_EVALUATED} alone has only ever meant "a forced run was requested for this
     * slot", never "the rating currently cached for it came from that run". The column is exposed
     * here so {@code EvaluationViewService.loadForceEvaluatedAt} can compare it against the WINNING
     * result's own evaluation instant before granting the exemption, rather than trusting the
     * category alone.
     *
     * <p>Filtered to {@code EVALUATED} and {@code FORCE_EVALUATED} alone — the only two categories
     * that mean "Claude was actually asked about this slot"; every {@code SKIPPED_*} category never
     * reached Claude and so cannot be the disposition a rating came from.
     *
     * <p>One bulk query per serve, grouped in the database rather than fetched row-by-row, bounded
     * to the caller's own served window — never called per region or per slot. Same table, same
     * 30-day retention as {@link #findLatestStabilitySkipTimestamps} covers the served horizon.
     *
     * <p>⚠️ On an exact {@code created_at} tie between an {@code EVALUATED} and a
     * {@code FORCE_EVALUATED} row for the same slot — practically unreachable, since the two
     * categories are written by different job-run cycles — both rows satisfy the correlated
     * {@code MAX(created_at)} predicate and both are returned; the caller keeps whichever it reads
     * last. Mirrors the same accepted tie behaviour {@link #findLatestStabilitySkipTimestamps}
     * documents is not a concern for MAX-of-one-category, and is even rarer here since it needs a
     * cross-category collision rather than a same-category one.
     *
     * @param start first evaluation date to include (inclusive)
     * @param end   last evaluation date to include (inclusive)
     * @return rows of {@code [locationName (String), evaluationDate (LocalDate), eventType
     *         (String), disposition (String), createdAt (Instant)]}, one per slot with at least one
     *         evaluating disposition — the disposition and instant of whichever of
     *         EVALUATED/FORCE_EVALUATED is most recent for that slot
     */
    @Query("SELECT d.locationName, d.evaluationDate, d.eventType, d.disposition, d.createdAt "
            + "FROM ForecastRunDispositionEntity d "
            + "WHERE d.disposition IN ('EVALUATED', 'FORCE_EVALUATED') "
            + "AND d.evaluationDate BETWEEN :start AND :end "
            + "AND d.createdAt = ("
            + "    SELECT MAX(d2.createdAt) FROM ForecastRunDispositionEntity d2 "
            + "    WHERE d2.locationName = d.locationName "
            + "    AND d2.evaluationDate = d.evaluationDate "
            + "    AND d2.eventType = d.eventType "
            + "    AND d2.disposition IN ('EVALUATED', 'FORCE_EVALUATED')"
            + ")")
    List<Object[]> findLatestEvaluatingDispositions(
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
     * one slot returns both rows, the same accepted shape {@link #findLatestEvaluatingDispositions}
     * documents; {@code EvaluationViewService.loadTriagedByBatch} folds a tie to "not examined"
     * (only counts a slot where every row at the max instant agrees it is {@code SKIPPED_TRIAGED}),
     * the same safe-under-ambiguity direction the force-evaluation exemption's own tie fold takes.
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
}
