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
}
