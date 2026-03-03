package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA entity representing a single scheduled job run.
 *
 * <p>Tracks the run type (what was requested), the evaluation model used (if any),
 * overall timing, and success/failure counts. Child {@link ApiCallLogEntity}
 * records detail which individual API calls occurred during this run.
 */
@Entity
@Table(name = "job_run")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobRunEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The type of forecast run (what was requested). */
    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 20)
    private RunType runType;

    /** Which Claude model was used for evaluation (null for WEATHER/TIDE). */
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_model", length = 10)
    private EvaluationModel evaluationModel;

    /** UTC timestamp when the job started. */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** UTC timestamp when the job completed (null if still running). */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Duration in milliseconds (null if still running). */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** Total number of locations processed in this run. */
    @Column(name = "locations_processed")
    private Integer locationsProcessed;

    /** Number of successful evaluations. */
    @Column(name = "succeeded")
    private Integer succeeded;

    /** Number of failed evaluations. */
    @Column(name = "failed")
    private Integer failed;

    /** Total cost of all API calls in this run, in pence. */
    @Column(name = "total_cost_pence")
    private Integer totalCostPence;

    /** Whether this run was triggered manually (via API) or by the scheduler. */
    @Column(name = "triggered_manually", nullable = false)
    private Boolean triggeredManually;

    /** Earliest target date for forecast evaluations in this run. */
    @Column(name = "min_target_date")
    private java.time.LocalDate minTargetDate;

    /** Latest target date for forecast evaluations in this run. */
    @Column(name = "max_target_date")
    private java.time.LocalDate maxTargetDate;

    /** Total cost of all API calls in micro-dollars (1 dollar = 1,000,000 micro-dollars). */
    @Column(name = "total_cost_micro_dollars")
    private Long totalCostMicroDollars;

    /** USD-to-GBP exchange rate at the time this run started, for historical GBP conversion. */
    @Column(name = "exchange_rate_gbp_per_usd")
    private Double exchangeRateGbpPerUsd;

    /** Comma-separated list of enabled optimisation strategy names at run start (audit trail). */
    @Column(name = "active_strategies")
    private String activeStrategies;

    /** Child API call log entries for this job run. */
    @OneToMany(mappedBy = "jobRunId")
    private List<ApiCallLogEntity> apiCalls;
}
