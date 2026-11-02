package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One fire of a dynamically scheduled job (V215, scheduler-redesign-plan.md D1).
 *
 * <p>Inserted by {@code DynamicSchedulerService} as {@link SchedulerFireStatus#RUNNING} when the fire starts.
 * Every column except {@code completed_at}, {@code status}, {@code summary} and {@code pipeline_run_id} is
 * {@code updatable = false}, and those four are written only by
 * {@code SchedulerFireLogRepository#complete}, so no whole-entity {@code save()} can rewrite a fire's start
 * or overwrite its outcome with a stale copy.
 */
@Entity
@Table(name = "scheduler_fire_log")
@Getter
@Setter
@NoArgsConstructor
public class SchedulerFireLogEntity {

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The {@code scheduler_job_config.job_key} that fired. */
    @Column(name = "job_key", nullable = false, length = 50, updatable = false)
    private String jobKey;

    /** When the fire started. */
    @Column(name = "fired_at", nullable = false, updatable = false)
    private Instant firedAt;

    /** When the fire ended; null while it is running. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** How the fire ended. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private SchedulerFireStatus status;

    /** A one-line, sanitised account of the outcome (at most 200 characters), or null. */
    @Column(name = "summary", length = 200)
    private String summary;

    /** What caused the fire. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, length = 12, updatable = false)
    private SchedulerFireTrigger triggerKind;

    /** The pipeline run this fire started (forecast cycles only), or null. */
    @Column(name = "pipeline_run_id")
    private Long pipelineRunId;
}
