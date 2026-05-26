package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

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
}
