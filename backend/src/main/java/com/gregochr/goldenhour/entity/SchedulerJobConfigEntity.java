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

    /**
     * The stored {@code schedule_type} string, exactly as the database holds it.
     *
     * <p>Deliberately a plain string and {@code updatable = false}, not an {@code @Enumerated}
     * {@link ScheduleType}. A column mapped as an enum makes Hibernate throw on a value this build
     * does not know, which fails the load of the whole table (every job, and the admin list). A
     * string loads whatever it holds; {@link #getScheduleType()} reads it as a {@link ScheduleType}
     * or {@code null} when unrecognised, and the scheduler skips that one job. Because the column is
     * never part of an {@code UPDATE}, no whole-entity save (a pause, a fire timestamp, a status
     * sync) can overwrite an unrecognised value with a guess. It is written only on insert.
     * A future type switch must be a column-scoped repository update.
     */
    @Column(name = "schedule_type", nullable = false, length = 20, updatable = false)
    private String scheduleTypeRaw;

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

    /**
     * The status held before a config flag disabled this job, restored when the flag flips back
     * on. Null means the job was never config-disabled. Without this, re-enabling would promote
     * every disabled job to ACTIVE — including ones deliberately seeded PAUSED.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status_before_config_disable", length = 30)
    private SchedulerJobStatus statusBeforeConfigDisable;

    /** When this config row was last modified. */
    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * Reads the stored schedule type.
     *
     * @return the type, or {@code null} when the stored string is not one this build knows
     *         (including a null string); see {@link #getScheduleTypeRaw()} for the original
     */
    public ScheduleType getScheduleType() {
        if (scheduleTypeRaw == null) {
            return null;
        }
        for (ScheduleType type : ScheduleType.values()) {
            if (type.name().equals(scheduleTypeRaw)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Sets the schedule type on this in-memory entity. Because the column is {@code updatable =
     * false}, this takes effect in the database only for a row not yet inserted.
     *
     * @param scheduleType the type
     */
    public void setScheduleType(ScheduleType scheduleType) {
        this.scheduleTypeRaw = scheduleType == null ? null : scheduleType.name();
    }

    /** Builder additions: the builder keeps accepting a {@link ScheduleType}. */
    public static class SchedulerJobConfigEntityBuilder {

        /**
         * Sets the schedule type.
         *
         * @param scheduleType the type
         * @return this builder
         */
        public SchedulerJobConfigEntityBuilder scheduleType(ScheduleType scheduleType) {
            this.scheduleTypeRaw = scheduleType == null ? null : scheduleType.name();
            return this;
        }
    }
}
