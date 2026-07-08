package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SkyRatingEvalResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link SkyRatingEvalResultEntity} — per-fixture, per-run-index eval results.
 */
public interface SkyRatingEvalResultRepository extends JpaRepository<SkyRatingEvalResultEntity, Long> {

    /**
     * All results for one run, ordered for stable display.
     *
     * @param runId the parent run id
     * @return the run's results, ordered by fixture then run index
     */
    List<SkyRatingEvalResultEntity> findByRunIdOrderByFixtureNameAscRunIndexAsc(Long runId);

    /**
     * All results across a set of runs — one query for trend aggregation in the service.
     *
     * @param runIds the parent run ids
     * @return matching results
     */
    List<SkyRatingEvalResultEntity> findByRunIdIn(Collection<Long> runIds);

    /**
     * Deletes every result for one run — called before the batch reconciler re-persists a run's
     * results, so a reconcile that crashed after writing some rows but before finalising cannot
     * leave duplicate child rows on the retry.
     *
     * @param runId the parent run id
     */
    void deleteByRunId(Long runId);
}
