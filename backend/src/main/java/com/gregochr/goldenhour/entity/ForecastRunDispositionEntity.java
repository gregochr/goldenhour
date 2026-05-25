package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.LocalDate;

/**
 * JPA entity for a single candidate's outcome within a forecast batch cycle.
 *
 * <p>Persisted by {@code ForecastDispositionService} once the cycle's first
 * job run has been created. One row per candidate the collector considered —
 * whether it was submitted to Claude ({@code EVALUATED}) or filtered out
 * ({@code SKIPPED_*}). The whole set of rows for a given {@code job_run_id}
 * reconciles to the number of slots the cycle started with.
 *
 * <p>The {@code disposition} column is stored as a plain VARCHAR rather than an
 * {@code @Enumerated} type so a future deployment can introduce new categories
 * (notably the reserved {@code SKIPPED_NO_REFRESH_NEEDED}) without a schema
 * change. {@link DispositionCategory#fromString(String)} performs the safe
 * round-trip back to an enum when reading.
 */
@Entity
@Table(name = "forecast_run_disposition")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForecastRunDispositionEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Job run this disposition belongs to (the first job_run created in the cycle). */
    @Column(name = "job_run_id", nullable = false)
    private Long jobRunId;

    /**
     * Resolved {@code LocationEntity} id, or {@code null} for past-date, cached,
     * and unknown-location dispositions where no location lookup was performed
     * or the lookup failed.
     */
    @Column(name = "location_id")
    private Long locationId;

    /** Denormalised location name so the UI can render without joining. */
    @Column(name = "location_name", nullable = false, length = 255)
    private String locationName;

    /** Briefing slot date. */
    @Column(name = "evaluation_date", nullable = false)
    private LocalDate evaluationDate;

    /** SUNRISE or SUNSET (HOURLY does not reach the batch path). */
    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    /**
     * Days from today (Europe/London) to the slot date — negative for past-date
     * dispositions, 0+ for present/future. May be {@code null} for edge cases.
     */
    @Column(name = "days_ahead")
    private Integer daysAhead;

    /**
     * Stored as VARCHAR. See {@link DispositionCategory} for canonical values
     * and forward-compatible parsing via {@code fromString}.
     */
    @Column(name = "disposition", nullable = false, length = 40)
    private String disposition;

    /**
     * Human-readable reason for skip dispositions — e.g. the triage rejection
     * string ({@code "Solar horizon low cloud 94% — sun blocked"}), the
     * standdown reason, the stability skip reason, or the error message.
     * {@code null} for {@code EVALUATED}.
     */
    @Column(name = "detail", length = 500)
    private String detail;

    /** Set by the DB default at insert time. */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
