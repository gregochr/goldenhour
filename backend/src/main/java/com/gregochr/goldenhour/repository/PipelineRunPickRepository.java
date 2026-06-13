package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data repository for {@link PipelineRunPickEntity}.
 *
 * <p>The Pipeline Runs UX queries by run, ordered by rank, to display the
 * cycle's Plan A then Plan B. The intraday refresh value-proving comparison
 * also uses this finder to fetch the morning's nightly picks and compare
 * them against the current intraday run's picks.
 */
public interface PipelineRunPickRepository
        extends JpaRepository<PipelineRunPickEntity, Long> {

    /**
     * Returns all picks for the given pipeline run, ordered by rank so the
     * caller sees Plan A first then Plan B.
     *
     * @param pipelineRunId the parent pipeline run id
     * @return picks ordered by pick_rank ascending (empty if the run
     *         persisted no picks, e.g. because the briefing failed)
     */
    List<PipelineRunPickEntity> findByPipelineRunIdOrderByPickRankAsc(Long pipelineRunId);

    /**
     * Returns fresh-enough fallback pick candidates for the fail-safe served when the current
     * run's advisor FAILED: picks whose event has not already passed (day-granular —
     * {@code event_date >= today}) and which were recorded no earlier than {@code minRecordedAt}
     * (the age ceiling). Ordered newest-recorded first then by rank, so the caller can take the
     * most recent run's set off the front of the list.
     *
     * <p>Only runs that produced picks have rows here (the orchestrator persists rows solely for
     * {@code SUCCESS_WITH_PICKS}), so a row's presence already implies it came from a successful
     * run — no status join needed.
     *
     * @param today         the current date in the display zone (London)
     * @param minRecordedAt the oldest acceptable {@code recorded_at} (now minus the age ceiling)
     * @return candidate picks, newest-recorded first then rank-ascending (possibly empty)
     */
    @Query("SELECT p FROM PipelineRunPickEntity p "
            + "WHERE p.eventDate >= :today AND p.recordedAt >= :minRecordedAt "
            + "ORDER BY p.recordedAt DESC, p.pickRank ASC")
    List<PipelineRunPickEntity> findFreshFallbackCandidates(
            @Param("today") LocalDate today, @Param("minRecordedAt") Instant minRecordedAt);
}
