package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.eval.MissDirection;
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

/**
 * One scored run of one fixture within a {@link SkyRatingEvalRunEntity}.
 *
 * <p>Carries the 1–5 {@code rating} the eval bands against plus the 0–100 {@code fierySky} /
 * {@code goldenHour} sub-scores (the finer-grained drift signal), the fixture's expected band
 * (so a graph can plot the rating against its ground truth), and the {@link MissDirection}
 * bucket (in-band, below = too cautious, above = too generous).
 */
@Entity
@Table(name = "sky_rating_eval_result")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SkyRatingEvalResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "fixture_name", nullable = false, length = 80)
    private String fixtureName;

    /** 1-based index of this run within the fixture's N runs. */
    @Column(name = "run_index", nullable = false)
    private int runIndex;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "fiery_sky")
    private Integer fierySky;

    @Column(name = "golden_hour")
    private Integer goldenHour;

    @Column(name = "expected_min", nullable = false)
    private int expectedMin;

    @Column(name = "expected_max", nullable = false)
    private int expectedMax;

    @Enumerated(EnumType.STRING)
    @Column(name = "miss_direction", nullable = false, length = 10)
    private MissDirection missDirection;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "duration_ms")
    private Long durationMs;
}
