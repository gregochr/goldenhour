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
     * <p>⚠️ <b>{@code SUBMISSION_FAILED} is deliberately NOT excluded — it is the opposite shape
     * of problem from {@code SKIPPED_CACHED}, not a sibling of it.</b> A {@code SUBMISSION_FAILED}
     * row exists only for a slot whose task reached submission at all — meaning tonight's own
     * fresh-weather triage looked at it and PASSED it (it started life as {@code EVALUATED}/
     * {@code FORCE_EVALUATED} and was rewritten after the batch failed to reach Anthropic; see
     * {@code ScheduledBatchEvaluationService#applySubmissionFailures}). That is a real, newer,
     * slot-specific fact — tonight's triage disagreed with an older {@code SKIPPED_TRIAGED} row —
     * and it must be read exactly like a bare {@code EVALUATED} row: the latest decision for the
     * slot, and NOT triaged. The first cut of this method excluded {@code SUBMISSION_FAILED} on
     * the mistaken belief that "infrastructure failed it" meant "not a decision" the same way a
     * region-level cache reuse is — but {@code SKIPPED_CACHED} never overwrites another category's
     * disposition for a specific slot, while {@code SUBMISSION_FAILED} always replaces that exact
     * slot's own {@code EVALUATED}/{@code FORCE_EVALUATED} row. Excluding it let an earlier
     * {@code SKIPPED_TRIAGED} row — up to the 30-day retention window old — resurface as "examined"
     * for a slot tonight's triage had just contradicted; in the 2026-09-29 incident this would have
     * flipped all 510 affected slots back to "examined" on stale evidence.
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
     * For the given slots (one date, one event type, a set of location names), returns
     * {@code [locationName, createdAt]} for every disposition recorded on or after
     * {@code minCreatedAt} whose category is a genuine decision AGAINST the slot —
     * {@code SKIPPED_STABILITY} or {@code SKIPPED_TRIAGED}, the explicit two-value allow-list
     * {@code SupersedingDispositionService} enumerates in full.
     *
     * <p>⚠️ <b>Round 14 replaces round 13's design entirely — it does NOT join
     * {@code forecast_batch}, and it does NOT accept {@code EVALUATED}/{@code FORCE_EVALUATED}.</b>
     * Both were found wrong against production on 2026-09-29 (pipeline run 249): every one of that
     * intraday cycle's three Anthropic batch submissions failed (HTTP 500), so
     * {@code ScheduledBatchEvaluationService#persistCycleDispositions} anchored its 589 dispositions
     * — 510 {@code EVALUATED}, 76 {@code SKIPPED_TRIAGED}, 3 {@code SKIPPED_UNKNOWN_LOCATION} — to a
     * disposition-only "anchor run" job_run with NO {@code forecast_batch} row at all. Two
     * consequences: (1) a three-entity join through {@code forecast_batch} cannot see any
     * disposition from an anchor run — invisible to the OLD query, whichever direction the bug ran;
     * (2) {@code EVALUATED} records only that a candidate was INCLUDED for submission, never that a
     * result exists or ever will — the 510 {@code EVALUATED} rows on that failed cycle produced
     * exactly zero results. Treating {@code EVALUATED}/{@code FORCE_EVALUATED} as superseding (the
     * OLD design) would have rejected a perfectly good older rating in favour of nothing, leaving
     * the slot unrated where, before this whole feature, the stale rating would have kept serving.
     *
     * <p><b>The caller resolves the correct threshold without any join at all</b> — see
     * {@code SupersedingDispositionService}'s class javadoc for the full "first pipeline run
     * triggered after the result's own {@code submittedAt}" rule and why it cannot misclassify a
     * same-cycle disposition. This query only ever receives the already-resolved instant and filters
     * on {@code created_at}, this table's own native, indexed timestamp — no cross-table timing
     * inference happens here.
     *
     * <p>One bulk query, covering every location the caller passes in one round trip — never one
     * per location.
     *
     * @param date          the slots' evaluation date
     * @param eventType     the slots' stored event type string (e.g. {@code "SUNRISE"})
     * @param locationNames the candidate location names to check
     * @param minCreatedAt  only a disposition created at or after this instant counts (the caller's
     *                      already-resolved "first later trigger" boundary — the smallest such
     *                      boundary among the results it is checking, when they differ)
     * @return {@code [locationName, createdAt]} pairs, one per qualifying disposition row (a
     *         location may appear more than once — the caller takes the latest)
     */
    @Query("SELECT d.locationName, d.createdAt FROM ForecastRunDispositionEntity d "
            + "WHERE d.disposition IN ('SKIPPED_STABILITY', 'SKIPPED_TRIAGED') "
            + "AND d.evaluationDate = :date "
            + "AND d.eventType = :eventType "
            + "AND d.locationName IN (:locationNames) "
            + "AND d.createdAt >= :minCreatedAt")
    List<Object[]> findSupersedingDispositions(
            @Param("date") LocalDate date,
            @Param("eventType") String eventType,
            @Param("locationNames") Collection<String> locationNames,
            @Param("minCreatedAt") Instant minCreatedAt);

    /**
     * Single-location existence check backing {@code SupersedingDispositionService#isSuperseded} —
     * the per-response fallback gate in front of the {@code forecast_score} dual write (round 14,
     * "correction 3"). A true hoist (deciding supersession for a whole Anthropic batch before any of
     * its responses are parsed) is not possible with the streaming Batch API result reader, which
     * only learns a batch's location set as it consumes the stream — see
     * {@code ForecastResultHandler}'s class javadoc for why the merge-level bulk check
     * ({@link #findSupersedingDispositions}) is kept as the primary mechanism and this single-row
     * check is the accepted, explained fallback for the one sink (`forecast_score`) that writes
     * per-response rather than per-merge-call.
     *
     * <p>Same two-value allow-list as {@link #findSupersedingDispositions} — see that method's own
     * javadoc for why {@code EVALUATED}/{@code FORCE_EVALUATED} must never appear here.
     *
     * @param locationName the slot's location name
     * @param date         the slot's evaluation date
     * @param eventType    the slot's stored event type string
     * @param minCreatedAt only a disposition created at or after this instant counts
     * @return {@code true} if a qualifying disposition exists for this exact slot
     */
    @Query("SELECT COUNT(d) > 0 FROM ForecastRunDispositionEntity d "
            + "WHERE d.disposition IN ('SKIPPED_STABILITY', 'SKIPPED_TRIAGED') "
            + "AND d.locationName = :locationName "
            + "AND d.evaluationDate = :date "
            + "AND d.eventType = :eventType "
            + "AND d.createdAt >= :minCreatedAt")
    boolean existsSupersedingDisposition(
            @Param("locationName") String locationName,
            @Param("date") LocalDate date,
            @Param("eventType") String eventType,
            @Param("minCreatedAt") Instant minCreatedAt);
}
