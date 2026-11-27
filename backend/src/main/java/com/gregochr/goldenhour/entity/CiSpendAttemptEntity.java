package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/**
 * Records that one attempt of one CI workflow run is settled (V232), so later polls of
 * {@code ci_spend_poll} skip it without calling GitHub. Primary key is ({@code github_run_id}, {@code run_attempt}).
 */
@Entity
@Table(name = "ci_spend_attempt")
@IdClass(CiSpendAttemptEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CiSpendAttemptEntity {

    /** GitHub Actions workflow run id. */
    @Id
    @Column(name = "github_run_id", nullable = false)
    private Long githubRunId;

    /** The run's attempt number (1 for the first run, 2 after a re-run). */
    @Id
    @Column(name = "run_attempt", nullable = false)
    private Integer runAttempt;

    /** When the poller settled the attempt. */
    @Column(name = "settled_at", nullable = false)
    private Instant settledAt;

    /** Comma-separated suites that ran in the attempt (for example {@code sky,ask}), or empty when none did. */
    @Column(name = "suites_ran", nullable = false, length = 16)
    private String suitesRan;

    /**
     * Composite primary key of {@link CiSpendAttemptEntity}.
     *
     * @param githubRunId the workflow run id
     * @param runAttempt  the attempt number
     */
    public record Key(Long githubRunId, Integer runAttempt) implements Serializable {
    }
}
