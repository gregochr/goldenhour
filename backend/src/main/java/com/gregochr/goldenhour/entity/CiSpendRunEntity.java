package com.gregochr.goldenhour.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One suite of one attempt of one CI prompt-regression workflow run, with its priced spend (V232).
 *
 * <p>Identity, event, PR, times and conclusion come from the GitHub API, never from the usage artifact. The
 * {@code usage_state} is a plain string ({@link #STATE_KNOWN} and friends), not an enum column, so
 * {@code LocalH2EnumWidener} is untouched. {@code costMicroDollars} is non-null only when {@code KNOWN}.
 * These rows are never mixed into {@code job_run} or {@code api_call_log}.
 */
@Entity
@Table(name = "ci_spend_run", uniqueConstraints = @UniqueConstraint(name = "uq_ci_spend_run",
        columnNames = {"github_run_id", "run_attempt", "suite"}))
@Getter
@Setter
@NoArgsConstructor
public class CiSpendRunEntity {

    /** The artifact was read and every model priced. */
    public static final String STATE_KNOWN = "KNOWN";

    /** The suite ran but no valid artifact exists (yet, or it expired). */
    public static final String STATE_UNKNOWN = "UNKNOWN";

    /** Tokens are stored but a model id is not in the price table; cost is null. */
    public static final String STATE_UNPRICED = "UNPRICED";

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** GitHub Actions workflow run id. */
    @Column(name = "github_run_id", nullable = false, updatable = false)
    private Long githubRunId;

    /** The run attempt number. */
    @Column(name = "run_attempt", nullable = false, updatable = false)
    private Integer runAttempt;

    /** {@code sky} or {@code ask}. */
    @Column(name = "suite", nullable = false, length = 16, updatable = false)
    private String suite;

    /** The workflow event ({@code pull_request}, {@code workflow_dispatch} ...). */
    @Column(name = "event", length = 32)
    private String event;

    /** The head commit SHA. */
    @Column(name = "head_sha", length = 40)
    private String headSha;

    /** The pull request number, or null for a manual run. */
    @Column(name = "pr_number")
    private Integer prNumber;

    /** The pull request title (only for {@code pull_request} events), truncated to 300 characters. */
    @Column(name = "pr_title", length = 300)
    private String prTitle;

    /** The run's GitHub URL, built server-side. */
    @Column(name = "html_url", nullable = false, length = 300)
    private String htmlUrl;

    /** When the suite's job started. */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** When the suite's job finished, if it had. */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /** The job's conclusion ({@code success}, {@code failure}, {@code cancelled} ...). */
    @Column(name = "conclusion", length = 32)
    private String conclusion;

    /** Test cases run, from the artifact; null when unknown. */
    @Column(name = "cases")
    private Integer cases;

    /** Test cases that failed or errored, from the artifact; null when unknown. */
    @Column(name = "failed")
    private Integer failed;

    /** {@link #STATE_KNOWN}, {@link #STATE_UNKNOWN} or {@link #STATE_UNPRICED}. */
    @Column(name = "usage_state", nullable = false, length = 16)
    private String usageState;

    /** Calls that threw, summed over models (a slight under-count of billed spend). */
    @Column(name = "errored_calls", nullable = false)
    private Integer erroredCalls = 0;

    /** Cost in micro-dollars, non-null only when {@code KNOWN}. */
    @Column(name = "cost_micro_dollars")
    private Long costMicroDollars;

    /** GBP per USD for {@code startedAt}'s UTC date, set when priced. */
    @Column(name = "exchange_rate_gbp_per_usd")
    private Double exchangeRateGbpPerUsd;

    /** When the poller stored or last rewrote the row. */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** The per-model token counts and rate snapshots. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "ci_spend_run_id", nullable = false)
    private List<CiSpendModelUsageEntity> models = new ArrayList<>();
}
