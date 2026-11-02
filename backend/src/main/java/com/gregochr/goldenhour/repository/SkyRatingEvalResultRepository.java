package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SkyRatingEvalResultEntity;
import com.gregochr.goldenhour.model.skyeval.SkyRatingEvalFirstSeen;
import com.gregochr.goldenhour.model.skyeval.SkyRatingEvalFixtureAggregate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link SkyRatingEvalResultEntity} — per-fixture, per-run-index eval results.
 */
public interface SkyRatingEvalResultRepository extends JpaRepository<SkyRatingEvalResultEntity, Long> {

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

    /**
     * One aggregate row per (run, fixture) over the given runs: attempts, in-band / below / above counts and the
     * rating statistics. The only read the Sky Eval overview makes over this table, so it costs one query however
     * many runs there are. A failed attempt has no rating and no direction, so it counts toward {@code n} only.
     *
     * @param runIds the run ids
     * @return the aggregates, unordered
     */
    @Query("select new com.gregochr.goldenhour.model.skyeval.SkyRatingEvalFixtureAggregate("
            + "r.runId, r.fixtureName, count(r), "
            + "sum(case when r.missDirection = com.gregochr.goldenhour.eval.MissDirection.IN_BAND then 1 else 0 end), "
            + "sum(case when r.missDirection = com.gregochr.goldenhour.eval.MissDirection.BELOW then 1 else 0 end), "
            + "sum(case when r.missDirection = com.gregochr.goldenhour.eval.MissDirection.ABOVE then 1 else 0 end), "
            + "count(r.rating), sum(r.rating), min(r.rating), max(r.rating)) "
            + "from SkyRatingEvalResultEntity r where r.runId in :runIds group by r.runId, r.fixtureName")
    List<SkyRatingEvalFixtureAggregate> aggregateByRunAndFixture(@Param("runIds") Collection<Long> runIds);

    /**
     * The first run time at which each fixture has a result row, i.e. when the fixture was added to the eval.
     *
     * @return one row per fixture that has ever been scored
     */
    @Query("select new com.gregochr.goldenhour.model.skyeval.SkyRatingEvalFirstSeen(r.fixtureName, "
            + "min(run.runTimestamp)) from SkyRatingEvalResultEntity r, SkyRatingEvalRunEntity run "
            + "where r.runId = run.id group by r.fixtureName")
    List<SkyRatingEvalFirstSeen> firstSeenByFixture();
}
