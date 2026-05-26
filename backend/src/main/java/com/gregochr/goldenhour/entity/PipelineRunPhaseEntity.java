package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per phase within a {@link PipelineRunEntity}.
 *
 * <p>Carries per-phase status and start/complete timestamps so the Pipeline
 * Run detail view can render the full timeline (which phase took how long,
 * which is currently waiting, which failed and why).
 */
@Entity
@Table(name = "pipeline_run_phase")
public class PipelineRunPhaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_run_id", nullable = false)
    private Long pipelineRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 40)
    private PipelinePhase phase;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PipelinePhaseStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "detail", length = 500)
    private String detail;

    /** Default constructor for JPA. */
    public PipelineRunPhaseEntity() {
    }

    /**
     * Convenience constructor for a newly-started phase.
     *
     * @param pipelineRunId   parent pipeline run id
     * @param phase           which phase this row records
     * @param sequenceOrder   1-based position within the run
     * @param startedAt       when the phase started
     */
    public PipelineRunPhaseEntity(Long pipelineRunId, PipelinePhase phase,
            int sequenceOrder, Instant startedAt) {
        this.pipelineRunId = pipelineRunId;
        this.phase = phase;
        this.sequenceOrder = sequenceOrder;
        this.startedAt = startedAt;
        this.status = PipelinePhaseStatus.RUNNING;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPipelineRunId() {
        return pipelineRunId;
    }

    public void setPipelineRunId(Long pipelineRunId) {
        this.pipelineRunId = pipelineRunId;
    }

    public PipelinePhase getPhase() {
        return phase;
    }

    public void setPhase(PipelinePhase phase) {
        this.phase = phase;
    }

    public int getSequenceOrder() {
        return sequenceOrder;
    }

    public void setSequenceOrder(int sequenceOrder) {
        this.sequenceOrder = sequenceOrder;
    }

    public PipelinePhaseStatus getStatus() {
        return status;
    }

    public void setStatus(PipelinePhaseStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
