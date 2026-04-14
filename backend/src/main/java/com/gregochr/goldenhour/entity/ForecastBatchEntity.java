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
 * Tracks an Anthropic Batch API submission for forecast or aurora evaluations.
 *
 * <p>One row per batch submitted. The polling service updates {@code status},
 * {@code succeededCount}, {@code erroredCount}, and {@code endedAt} once the
 * batch transitions to {@code ENDED}.
 */
@Entity
@Table(name = "forecast_batch")
public class ForecastBatchEntity {

    /**
     * Possible lifecycle states for a submitted batch.
     */
    public enum BatchStatus {
        /** Batch submitted, Anthropic still processing. */
        SUBMITTED,
        /** Batch completed — results fetched and written to cache. */
        COMPLETED,
        /** Batch ended with no successes or a fatal error. */
        FAILED,
        /** Batch expired before completion. */
        EXPIRED,
        /** Batch cancelled — superseded by a real-time SSE evaluation. */
        CANCELLED
    }

    /**
     * Distinguishes forecast-evaluation batches from aurora-evaluation batches.
     */
    public enum BatchType {
        /** Short-term forecast evaluation across colour locations. */
        FORECAST,
        /** Aurora photography location evaluation. */
        AURORA
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "anthropic_batch_id", nullable = false, unique = true, length = 100)
    private String anthropicBatchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "batch_type", nullable = false, length = 30)
    private BatchType batchType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BatchStatus status = BatchStatus.SUBMITTED;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "succeeded_count")
    private Integer succeededCount;

    @Column(name = "errored_count")
    private Integer erroredCount;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /**
     * ID of the {@link com.gregochr.goldenhour.entity.JobRunEntity} tracking this batch
     * in the Job Runs screen. Null for batches submitted before V85.
     */
    @Column(name = "job_run_id")
    private Long jobRunId;

    /** Default constructor for JPA. */
    public ForecastBatchEntity() {
    }

    /**
     * Convenience constructor for a newly submitted batch.
     *
     * @param anthropicBatchId the batch ID returned by the Anthropic API
     * @param batchType        FORECAST or AURORA
     * @param requestCount     number of individual requests in this batch
     * @param expiresAt        when Anthropic will expire the batch
     */
    public ForecastBatchEntity(String anthropicBatchId, BatchType batchType,
            int requestCount, Instant expiresAt) {
        this.anthropicBatchId = anthropicBatchId;
        this.batchType = batchType;
        this.requestCount = requestCount;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getAnthropicBatchId() {
        return anthropicBatchId;
    }

    public BatchType getBatchType() {
        return batchType;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public void setStatus(BatchStatus status) {
        this.status = status;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public Integer getSucceededCount() {
        return succeededCount;
    }

    public void setSucceededCount(Integer succeededCount) {
        this.succeededCount = succeededCount;
    }

    public Integer getErroredCount() {
        return erroredCount;
    }

    public void setErroredCount(Integer erroredCount) {
        this.erroredCount = erroredCount;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }

    public void setLastPolledAt(Instant lastPolledAt) {
        this.lastPolledAt = lastPolledAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getJobRunId() {
        return jobRunId;
    }

    public void setJobRunId(Long jobRunId) {
        this.jobRunId = jobRunId;
    }
}
