package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
