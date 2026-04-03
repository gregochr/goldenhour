package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persisted configuration for a dynamically scheduled job.
 *
 * <p>Each row represents one schedulable job. The {@link DynamicSchedulerService} reads these
 * at startup and schedules/cancels jobs accordingly.
 */
@Entity
@Table(name = "scheduler_job_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchedulerJobConfigEntity {

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique identifier for this job (e.g. "tide_refresh", "aurora_polling"). */
    @Column(name = "job_key", nullable = false, unique = true, length = 50)
    private String jobKey;

    /** Human-readable name shown in the admin UI. */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** Short description of what the job does. */
    @Column(name = "description", length = 500)
    private String description;

    /** Whether this job uses CRON or FIXED_DELAY scheduling. */
    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 20)
    private ScheduleType scheduleType;

    /** Cron expression — only applicable when scheduleType is CRON. */
    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    /** Fixed delay in milliseconds — only applicable when scheduleType is FIXED_DELAY. */
    @Column(name = "fixed_delay_ms")
    private Long fixedDelayMs;

    /** Initial delay in milliseconds before the first execution — FIXED_DELAY jobs only. */
    @Column(name = "initial_delay_ms")
    private Long initialDelayMs;

    /** Current status: ACTIVE, PAUSED, or DISABLED_BY_CONFIG. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private SchedulerJobStatus status = SchedulerJobStatus.ACTIVE;

    /** When this job last started executing. */
    @Column(name = "last_fire_time")
    private Instant lastFireTime;

    /** When this job last finished executing. */
    @Column(name = "last_completion_time")
    private Instant lastCompletionTime;

    /** Config property that controls this job (e.g. "aurora.enabled"). Null if always available. */
    @Column(name = "config_source", length = 200)
    private String configSource;

    /** When this config row was last modified. */
    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
