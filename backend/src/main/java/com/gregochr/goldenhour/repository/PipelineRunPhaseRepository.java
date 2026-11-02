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

    /**
     * One phase of several cycles. A resumed cycle can have more than one row for the phase.
     *
     * @param pipelineRunIds the cycle ids
     * @param phase          the phase
     * @return the phase rows
     */
    List<PipelineRunPhaseEntity> findByPipelineRunIdInAndPhase(
            java.util.Collection<Long> pipelineRunIds, com.gregochr.goldenhour.entity.PipelinePhase phase);

    /**
     * Rows of one phase that started in a window, for matching a briefing run to the cycle phase it ran in when
     * the run carries no cycle id.
     *
     * @param phase the phase
     * @param from  the inclusive start
     * @param to    the exclusive end
     * @return the phase rows
     */
    List<PipelineRunPhaseEntity> findByPhaseAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            com.gregochr.goldenhour.entity.PipelinePhase phase, java.time.Instant from, java.time.Instant to);
}
