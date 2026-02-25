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
 * JPA entity representing a single scheduled job run (Sonnet, Haiku, Wildlife, or Tide).
 *
 * <p>Tracks overall job timing and success/failure counts. Child {@link ApiCallLogEntity}
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

    /** Name of the scheduled job that ran. */
    @Enumerated(EnumType.STRING)
    @Column(name = "job_name", nullable = false, length = 20)
    private JobName jobName;

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

    /** Child API call log entries for this job run. */
    @OneToMany(mappedBy = "jobRunId")
    private List<ApiCallLogEntity> apiCalls;
}
