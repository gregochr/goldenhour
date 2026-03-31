package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Tracks a single briefing model comparison test run.
 *
 * <p>Each run calls all three Claude models (Haiku, Sonnet, Opus) with the same
 * briefing rollup JSON and persists the results for side-by-side comparison.
 */
@Entity
@Table(name = "briefing_model_test_run")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BriefingModelTestRunEntity {

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

    /** Number of successful model evaluations. */
    @Column(name = "succeeded", nullable = false)
    @Builder.Default
    private Integer succeeded = 0;

    /** Number of failed model evaluations. */
    @Column(name = "failed", nullable = false)
    @Builder.Default
    private Integer failed = 0;

    /** Total cost of all evaluations in micro-dollars (1 dollar = 1,000,000 micro-dollars). */
    @Column(name = "total_cost_micro_dollars")
    private Long totalCostMicroDollars;

    /** USD-to-GBP exchange rate at the time this test ran, for historical GBP conversion. */
    @Column(name = "exchange_rate_gbp_per_usd")
    private Double exchangeRateGbpPerUsd;

    /** The rollup JSON sent to all three models as the user message. */
    @Column(name = "rollup_json", columnDefinition = "TEXT")
    private String rollupJson;

    /** UTC timestamp when the briefing that provided input data was generated. */
    @Column(name = "briefing_generated_at")
    private LocalDateTime briefingGeneratedAt;

    /** Results for this test run (one per model). */
    @OneToMany(mappedBy = "testRunId")
    private List<BriefingModelTestResultEntity> results;
}
