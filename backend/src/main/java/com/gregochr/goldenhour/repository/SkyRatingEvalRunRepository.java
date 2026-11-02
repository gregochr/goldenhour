package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link SkyRatingEvalRunEntity} — sky-rating eval invocations.
 */
public interface SkyRatingEvalRunRepository extends JpaRepository<SkyRatingEvalRunEntity, Long> {

    /**
     * Most recent runs first, capped — for the admin runs list.
     *
     * @return up to 100 runs, newest first
     */
    List<SkyRatingEvalRunEntity> findTop100ByOrderByRunTimestampDesc();

    /**
     * Runs with the given status, oldest first — for building the chronological trend series.
     *
     * @param status the lifecycle status to filter on
     * @return matching runs, oldest first
     */
    List<SkyRatingEvalRunEntity> findByStatusOrderByRunTimestampAsc(SkyRatingEvalStatus status);

    /**
     * Runs with the given status — for the batch reconciler to reload in-flight runs each tick.
     *
     * @param status the lifecycle status to filter on
     * @return matching runs
     */
    List<SkyRatingEvalRunEntity> findByStatus(SkyRatingEvalStatus status);

    /**
     * Runs whose logical timestamp falls in a UTC window, oldest first, by the indexed {@code run_timestamp}.
     *
     * @param from the inclusive start (naive UTC)
     * @param to   the exclusive end (naive UTC)
     * @return the runs
     */
    List<SkyRatingEvalRunEntity> findByRunTimestampGreaterThanEqualAndRunTimestampLessThanOrderByRunTimestampAscIdAsc(
            java.time.LocalDateTime from, java.time.LocalDateTime to);

    /**
     * The newest runs with a status, before a moment, newest first. The sample the Runs medians are taken over.
     *
     * @param status   the status (COMPLETED)
     * @param before   the exclusive upper bound on the timestamp (naive UTC)
     * @param pageable the page (the sample size)
     * @return the runs, newest first
     */
    List<SkyRatingEvalRunEntity> findByStatusAndRunTimestampLessThanOrderByRunTimestampDescIdDesc(
            SkyRatingEvalStatus status, java.time.LocalDateTime before,
            org.springframework.data.domain.Pageable pageable);

    /**
     * The timestamp of the earliest eval run, for the earliest day the Runs view offers.
     *
     * @return the earliest timestamp (naive UTC), or null when there are none
     */
    @org.springframework.data.jpa.repository.Query("SELECT MIN(r.runTimestamp) FROM SkyRatingEvalRunEntity r")
    java.time.LocalDateTime findEarliestRunTimestamp();
}
