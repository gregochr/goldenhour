package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link PipelineRunEntity}.
 */
public interface PipelineRunRepository extends JpaRepository<PipelineRunEntity, Long> {

    /**
     * Returns all pipeline runs in the given status, newest first by trigger time.
     *
     * @param status the status to filter on
     * @return matching runs ordered by trigger time descending
     */
    List<PipelineRunEntity> findByStatusOrderByTriggerTimeDesc(PipelineRunStatus status);

    /**
     * Returns the most recent N pipeline runs regardless of status.
     *
     * <p>Used by the Pipeline Run list view in the Operations tab.
     *
     * @return up to 50 recent runs, newest first
     */
    List<PipelineRunEntity> findTop50ByOrderByTriggerTimeDesc();

    /**
     * Returns the most recent run of the given cycle type whose trigger time
     * falls within {@code [start, end]} — used to find an intraday run's
     * same-day NIGHTLY baseline (start = London start-of-day, end = the
     * intraday run's own trigger time, so only the morning's nightly run is
     * eligible).
     *
     * @param cycleType the cycle type to match (NIGHTLY for the baseline)
     * @param start     inclusive lower bound (London start-of-day instant)
     * @param end       inclusive upper bound (the intraday run's trigger time)
     * @return the latest matching run, or empty if none
     */
    Optional<PipelineRunEntity>
            findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                    CycleType cycleType, Instant start, Instant end);
}
