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

import java.time.LocalDateTime;

/**
 * One triggered sky-rating eval invocation: N runs over each fixture through the real scorer,
 * with aggregate pass-rate, direction-bucketed miss counts, token cost, and the git commit the
 * prompt was at — so a drift step-change in the graph can be attributed to the edit that caused it.
 *
 * <p>The parent of {@link SkyRatingEvalResultEntity} (one child per fixture × run index).
 */
@Entity
@Table(name = "sky_rating_eval_run")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SkyRatingEvalRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical instant the run represents (used as the x-axis for trend graphs). */
    @Column(name = "run_timestamp", nullable = false)
    private LocalDateTime runTimestamp;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "model", nullable = false, length = 10)
    private EvaluationModel model;

    @Column(name = "runs_per_fixture", nullable = false)
    private int runsPerFixture;

    @Column(name = "fixture_count", nullable = false)
    @Builder.Default
    private int fixtureCount = 0;

    @Column(name = "total_runs", nullable = false)
    @Builder.Default
    private int totalRuns = 0;

    @Column(name = "total_passes", nullable = false)
    @Builder.Default
    private int totalPasses = 0;

    @Column(name = "below_misses", nullable = false)
    @Builder.Default
    private int belowMisses = 0;

    @Column(name = "above_misses", nullable = false)
    @Builder.Default
    private int aboveMisses = 0;

    @Column(name = "pass_rate", nullable = false)
    @Builder.Default
    private double passRate = 0.0;

    @Column(name = "input_tokens", nullable = false)
    @Builder.Default
    private long inputTokens = 0;

    @Column(name = "output_tokens", nullable = false)
    @Builder.Default
    private long outputTokens = 0;

    @Column(name = "cost_micro_dollars", nullable = false)
    @Builder.Default
    private long costMicroDollars = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 20)
    private SkyRatingEvalTrigger triggerSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SkyRatingEvalStatus status;

    /**
     * The Anthropic message-batch id this run's requests were submitted under, or {@code null} for
     * synchronously-scored runs. Persisted so the batch reconciler can reload a RUNNING run after a
     * restart and finalise it once its batch has ended.
     */
    @Column(name = "batch_id", length = 255)
    private String batchId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "git_commit_hash", length = 40)
    private String gitCommitHash;

    @Column(name = "git_commit_date")
    private LocalDateTime gitCommitDate;

    @Column(name = "git_dirty")
    private Boolean gitDirty;

    @Column(name = "git_branch", length = 255)
    private String gitBranch;
}
