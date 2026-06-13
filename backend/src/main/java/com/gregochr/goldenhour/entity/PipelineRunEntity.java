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
