package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Stores the result of evaluating a single location with a single Claude model
 * as part of a model comparison test run.
 *
 * <p>Captures the exact prompt sent and raw response for reproducibility, alongside
 * the parsed scores and any error information.
 */
@Entity
@Table(name = "model_test_result")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelTestResultEntity {

    /** Auto-generated primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to the parent test run. */
    @Column(name = "test_run_id", nullable = false)
    private Long testRunId;

    /** FK to the region. */
    @Column(name = "region_id", nullable = false)
    private Long regionId;

    /** Denormalised region name for easy display. */
    @Column(name = "region_name", nullable = false)
    private String regionName;

    /** FK to the location evaluated. */
    @Column(name = "location_id", nullable = false)
    private Long locationId;

    /** Denormalised location name for easy display. */
    @Column(name = "location_name", nullable = false)
    private String locationName;

    /** The date being evaluated. */
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    /** Whether this evaluated SUNRISE or SUNSET. */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private TargetType targetType;

    /** Which Claude model was used (HAIKU, SONNET, or OPUS). */
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_model", nullable = false, length = 10)
    private EvaluationModel evaluationModel;

    /** Star rating (1-5) from Claude, or null if evaluation failed. */
    @Column(name = "rating")
    private Integer rating;

    /** Fiery Sky Potential (0-100), or null if evaluation failed. */
    @Column(name = "fiery_sky_potential")
    private Integer fierySkyPotential;

    /** Golden Hour Potential (0-100), or null if evaluation failed. */
    @Column(name = "golden_hour_potential")
    private Integer goldenHourPotential;

    /** Claude's plain-English summary, or null if evaluation failed. */
    @Column(name = "summary", length = 1000)
    private String summary;

    /** The exact prompt sent to Claude (for reproducibility). */
    @Lob
    @Column(name = "prompt_sent")
    private String promptSent;

    /** The raw JSON response from Claude (for debugging). */
    @Lob
    @Column(name = "response_json")
    private String responseJson;

    /** How long the Claude API call took in milliseconds. */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** Estimated cost of this API call in pence. */
    @Column(name = "cost_pence", nullable = false)
    @Builder.Default
    private Integer costPence = 0;

    /** Whether the evaluation succeeded. */
    @Column(name = "succeeded", nullable = false)
    @Builder.Default
    private Boolean succeeded = false;

    /** Error message if the evaluation failed. */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** UTC timestamp when this result was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
