package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per orchestrated pipeline cycle.
 *
 * <p>Holds cycle identity (used to tie {@link ForecastBatchEntity} rows to the
 * cycle that created them) and the current lifecycle state, including a
 * human-readable {@code waitingOn} string surfaced in the live observability
 * UX. Per-phase timing lives in {@link PipelineRunPhaseEntity}.
 */
@Entity
@Table(name = "pipeline_run")
public class PipelineRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "cycle_type", nullable = false, length = 20)
    private CycleType cycleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PipelineRunStatus status = PipelineRunStatus.RUNNING;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_phase", length = 40)
    private PipelinePhase currentPhase;

    @Column(name = "waiting_on", length = 255)
    private String waitingOn;

    @Column(name = "trigger_time", nullable = false)
    private Instant triggerTime;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * The best-bet advisor's outcome for this cycle: {@code SUCCESS_WITH_PICKS},
     * {@code SUCCESS_NO_PICKS} (honest decline), or {@code FAILED}. Distinguishes "flat week"
     * from "advisor broke" in the run history and feeds the cross-run comparison; null on runs
     * that predate the status contract or whose briefing was served stale.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "best_bet_status", length = 20)
    private com.gregochr.goldenhour.model.BestBetStatus bestBetStatus;

    /**
     * The job run this cycle's {@code forecast_run_disposition} rows were persisted onto: the first
     * submitted batch's job run, or, for a cycle that submitted no batch, a disposition-only anchor
     * run nothing else links to the pipeline run. Read by the location auto-disable settle to find
     * the cycle's evidence, durably across a restart. Null until the cycle persists dispositions.
     *
     * <p>{@code updatable = false}: written only by {@code PipelineRunRepository
     * #recordDispositionJobRun}, so a whole-entity save of a stale copy cannot overwrite it.
     */
    @Column(name = "disposition_job_run_id", updatable = false)
    private Long dispositionJobRunId;

    /**
     * When location failures were settled for this cycle, or null while they have not been. Claimed
     * by a conditional update ({@code PipelineRunRepository#claimFailureSettle}) in the settle's own
     * transaction, so a cycle is counted exactly once and a run stopped before it settled is settled
     * on resume. Its newest settled {@code trigger_time} orders the cycles.
     *
     * <p>{@code updatable = false} for the same reason as {@link #dispositionJobRunId}.
     */
    @Column(name = "failures_settled_at", updatable = false)
    private Instant failuresSettledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Default constructor for JPA. */
    public PipelineRunEntity() {
    }

    /**
     * Convenience constructor for a newly-started cycle.
     *
     * @param cycleType   the cycle type
     * @param triggerTime when this cycle started
     */
    public PipelineRunEntity(CycleType cycleType, Instant triggerTime) {
        this.cycleType = cycleType;
        this.triggerTime = triggerTime;
        this.status = PipelineRunStatus.RUNNING;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    /**
     * Returns the job run holding this cycle's dispositions.
     *
     * @return the job run id, or null if none has been recorded
     */
    public Long getDispositionJobRunId() {
        return dispositionJobRunId;
    }

    /**
     * Returns when this cycle's location failures were settled.
     *
     * @return the settle instant, or null if not yet settled
     */
    public Instant getFailuresSettledAt() {
        return failuresSettledAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public CycleType getCycleType() {
        return cycleType;
    }

    public void setCycleType(CycleType cycleType) {
        this.cycleType = cycleType;
    }

    public PipelineRunStatus getStatus() {
        return status;
    }

    public void setStatus(PipelineRunStatus status) {
        this.status = status;
    }

    public PipelinePhase getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(PipelinePhase currentPhase) {
        this.currentPhase = currentPhase;
    }

    public String getWaitingOn() {
        return waitingOn;
    }

    public void setWaitingOn(String waitingOn) {
        this.waitingOn = waitingOn;
    }

    public Instant getTriggerTime() {
        return triggerTime;
    }

    public void setTriggerTime(Instant triggerTime) {
        this.triggerTime = triggerTime;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public com.gregochr.goldenhour.model.BestBetStatus getBestBetStatus() {
        return bestBetStatus;
    }

    public void setBestBetStatus(com.gregochr.goldenhour.model.BestBetStatus bestBetStatus) {
        this.bestBetStatus = bestBetStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
