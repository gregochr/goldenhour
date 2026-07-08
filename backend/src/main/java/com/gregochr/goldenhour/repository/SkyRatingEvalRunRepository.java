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
}
