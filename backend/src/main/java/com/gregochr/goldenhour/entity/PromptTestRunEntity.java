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
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a single prompt regression test run across all colour locations.
 */
@Entity
@Table(name = "prompt_test_run")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PromptTestRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private TargetType targetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_model", nullable = false, length = 10)
    private EvaluationModel evaluationModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", length = 20)
    private RunType runType;

    @Column(name = "locations_count", nullable = false)
    @Builder.Default
    private Integer locationsCount = 0;

    @Column(name = "succeeded", nullable = false)
    @Builder.Default
    private Integer succeeded = 0;

    @Column(name = "failed", nullable = false)
    @Builder.Default
    private Integer failed = 0;

    @Column(name = "total_cost_pence", nullable = false)
    @Builder.Default
    private Integer totalCostPence = 0;

    @Column(name = "total_cost_micro_dollars")
    private Long totalCostMicroDollars;

    @Column(name = "exchange_rate_gbp_per_usd")
    private Double exchangeRateGbpPerUsd;

    @Column(name = "parent_run_id")
    private Long parentRunId;

    @Column(name = "git_commit_hash", length = 40)
    private String gitCommitHash;

    @Column(name = "git_commit_date")
    private LocalDateTime gitCommitDate;

    @Column(name = "git_dirty")
    private Boolean gitDirty;

    @Column(name = "git_branch")
    private String gitBranch;

    @OneToMany(mappedBy = "testRunId")
    @JsonIgnore
    private List<PromptTestResultEntity> results;
}
