package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link PipelineRunPhaseEntity}.
 */
public interface PipelineRunPhaseRepository
        extends JpaRepository<PipelineRunPhaseEntity, Long> {

    /**
     * Returns all phase rows for a given pipeline run, in their executed order.
     *
     * @param pipelineRunId the parent pipeline run id
     * @return phases ordered by sequence_order
     */
    List<PipelineRunPhaseEntity> findByPipelineRunIdOrderBySequenceOrderAsc(Long pipelineRunId);
}
