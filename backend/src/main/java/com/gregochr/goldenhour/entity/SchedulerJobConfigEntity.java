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

    /**
     * The solar event a {@link ScheduleType#SOLAR_OFFSET} job fires before, as stored
     * ({@code SUNRISE} or {@code SUNSET}); null for every other type.
     *
     * <p>A plain string and {@code updatable = false} for the same reasons as
     * {@link #scheduleTypeRaw}: a value this build does not know loads rather than failing the
     * table, and no whole-entity save can rewrite it. Written only by
     * {@code SchedulerJobConfigRepository#updateScheduleType}, together with the type and the
     * offset, so the three columns can never be saved out of step.
     */
    @Column(name = "solar_event", length = 10, updatable = false)
    private String solarEventRaw;

    /**
     * Minutes before the solar event a {@link ScheduleType#SOLAR_OFFSET} job fires (0..720); null
     * for every other type. {@code updatable = false}; see {@link #solarEventRaw}.
     */
    @Column(name = "solar_offset_minutes", updatable = false)
    private Integer solarOffsetMinutes;

    /** Cron expression — only applicable when scheduleType is CRON; the fallback for SOLAR_OFFSET. */
    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    /**
     * IANA zone id the CRON expression is evaluated in; {@code null} means UTC. Written only by
     * migrations, so never updatable from the entity.
     */
    @Column(name = "time_zone", length = 40, updatable = false)
    private String timeZone;

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

    /**
     * Reads the stored solar event.
     *
     * @return the event, or {@code null} when none is stored or the string is not one this build
     *         knows
     */
    public SolarEvent getSolarEvent() {
        if (solarEventRaw == null) {
            return null;
        }
        for (SolarEvent event : SolarEvent.values()) {
            if (event.name().equals(solarEventRaw)) {
                return event;
            }
        }
        return null;
    }

    /**
     * Sets the solar event on this in-memory entity. Because the column is {@code updatable =
     * false}, this takes effect in the database only for a row not yet inserted.
     *
     * @param solarEvent the event, or null
     */
    public void setSolarEvent(SolarEvent solarEvent) {
        this.solarEventRaw = solarEvent == null ? null : solarEvent.name();
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

        /**
         * Sets the solar event.
         *
         * @param solarEvent the event, or null
         * @return this builder
         */
        public SchedulerJobConfigEntityBuilder solarEvent(SolarEvent solarEvent) {
            this.solarEventRaw = solarEvent == null ? null : solarEvent.name();
            return this;
        }
    }
}
