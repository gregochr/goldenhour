package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Records score deltas when a cached evaluation is refreshed,
 * enabling empirical refinement of stability-driven freshness thresholds.
 */
@Entity
@Table(name = "evaluation_delta_log")
@Getter
@Setter
@NoArgsConstructor
public class EvaluationDeltaLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cache_key", nullable = false)
    private String cacheKey;

    @Column(name = "location_name", nullable = false)
    private String locationName;

    @Column(name = "evaluation_date", nullable = false)
    private LocalDate evaluationDate;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "stability_level", nullable = false)
    private String stabilityLevel;

    @Column(name = "old_evaluated_at", nullable = false)
    private Instant oldEvaluatedAt;

    /**
     * Which quantity {@link #ageHours} actually holds: {@code LOCATION} when it was measured
     * against this location's own previous write, {@code CACHE_KEY} when it fell back to the
     * region-level stamp, and {@code null} for rows written before this column existed.
     *
     * <p>⚠️ Only {@code LOCATION} rows are safe to aggregate. A region's slots span several
     * batches, so the region-level stamp is reset by whichever bucket landed last — a location
     * genuinely refreshed 24h ago can appear as ~0h old. Any staleness query must filter on this.
     */
    @Column(name = "age_basis", length = 16)
    private String ageBasis;

    @Column(name = "new_evaluated_at", nullable = false)
    private Instant newEvaluatedAt;

    @Column(name = "age_hours", nullable = false)
    private BigDecimal ageHours;

    @Column(name = "old_rating")
    private Integer oldRating;

    @Column(name = "new_rating")
    private Integer newRating;

    @Column(name = "rating_delta")
    private BigDecimal ratingDelta;

    @Column(name = "threshold_used_hours", nullable = false)
    private BigDecimal thresholdUsedHours;

    @Column(name = "logged_at", nullable = false)
    private Instant loggedAt;
}
