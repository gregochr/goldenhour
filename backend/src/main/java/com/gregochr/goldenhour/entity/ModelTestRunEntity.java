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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Tracks a single model comparison test run across regions.
 *
 * <p>Each run evaluates one location per enabled region using all three Claude models
 * (Haiku, Sonnet, Opus) against identical atmospheric data for side-by-side comparison.
 */
@Entity
@Table(name = "model_test_run")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelTestRunEntity {

    /** Auto-generated primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UTC timestamp when the test run started. */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** UTC timestamp when the test run completed. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Total duration of the test run in milliseconds. */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** The date being evaluated (sunrise or sunset date). */
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    /** Whether this test targets SUNRISE or SUNSET. */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private TargetType targetType;

    /** Number of regions included in the test. */
    @Column(name = "regions_count", nullable = false)
    @Builder.Default
    private Integer regionsCount = 0;

    /** Number of successful model evaluations. */
    @Column(name = "succeeded", nullable = false)
    @Builder.Default
    private Integer succeeded = 0;

    /** Number of failed model evaluations. */
    @Column(name = "failed", nullable = false)
    @Builder.Default
    private Integer failed = 0;

    /** Total estimated cost of all API calls in pence. */
    @Column(name = "total_cost_pence", nullable = false)
    @Builder.Default
    private Integer totalCostPence = 0;

    /** Total cost of all evaluations in micro-dollars (1 dollar = 1,000,000 micro-dollars). */
    @Column(name = "total_cost_micro_dollars")
    private Long totalCostMicroDollars;

    /** USD-to-GBP exchange rate at the time this test ran, for historical GBP conversion. */
    @Column(name = "exchange_rate_gbp_per_usd")
    private Double exchangeRateGbpPerUsd;

    /** Results for this test run (one per region/model combination). */
    @OneToMany(mappedBy = "testRunId")
    private List<ModelTestResultEntity> results;
}
