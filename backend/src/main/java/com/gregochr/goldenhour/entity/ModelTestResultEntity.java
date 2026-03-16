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

import java.math.BigDecimal;
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
    @Column(name = "prompt_sent", columnDefinition = "TEXT")
    private String promptSent;

    /** The raw JSON response from Claude (for debugging). */
    @Column(name = "response_json", columnDefinition = "TEXT")
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

    /** Number of standard input tokens consumed. */
    @Column(name = "input_tokens")
    private Long inputTokens;

    /** Number of output tokens generated. */
    @Column(name = "output_tokens")
    private Long outputTokens;

    /** Number of tokens written to the prompt cache. */
    @Column(name = "cache_creation_input_tokens")
    private Long cacheCreationInputTokens;

    /** Number of tokens read from the prompt cache. */
    @Column(name = "cache_read_input_tokens")
    private Long cacheReadInputTokens;

    /** Cost of this evaluation in micro-dollars (1 dollar = 1,000,000 micro-dollars). */
    @Column(name = "cost_micro_dollars")
    private Long costMicroDollars;

    /** UTC timestamp when this result was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // --- Atmospheric data (V39) ---

    /** Serialised AtmosphericData JSON for exact replay in determinism re-runs. */
    @Column(name = "atmospheric_data_json", columnDefinition = "TEXT")
    private String atmosphericDataJson;

    /** Low cloud cover percentage (0-100). */
    @Column(name = "low_cloud_percent")
    private Integer lowCloudPercent;

    /** Mid-level cloud cover percentage (0-100). */
    @Column(name = "mid_cloud_percent")
    private Integer midCloudPercent;

    /** High cloud cover percentage (0-100). */
    @Column(name = "high_cloud_percent")
    private Integer highCloudPercent;

    /** Visibility in metres. */
    @Column(name = "visibility_metres")
    private Integer visibilityMetres;

    /** Wind speed in metres per second. */
    @Column(name = "wind_speed_ms")
    private BigDecimal windSpeedMs;

    /** Wind direction in degrees (0-360). */
    @Column(name = "wind_direction_degrees")
    private Integer windDirectionDegrees;

    /** Precipitation in millimetres. */
    @Column(name = "precipitation_mm")
    private BigDecimal precipitationMm;

    /** Relative humidity percentage (0-100). */
    @Column(name = "humidity_percent")
    private Integer humidityPercent;

    /** WMO weather code. */
    @Column(name = "weather_code")
    private Integer weatherCode;

    /** PM2.5 particulate matter concentration. */
    @Column(name = "pm25")
    private BigDecimal pm25;

    /** Dust concentration in micrograms per cubic metre. */
    @Column(name = "dust_ugm3")
    private BigDecimal dustUgm3;

    /** Aerosol optical depth. */
    @Column(name = "aerosol_optical_depth")
    private BigDecimal aerosolOpticalDepth;

    /** Air temperature in degrees Celsius. */
    @Column(name = "temperature_celsius")
    private Double temperatureCelsius;

    /** Apparent (feels-like) temperature in degrees Celsius. */
    @Column(name = "apparent_temperature_celsius")
    private Double apparentTemperatureCelsius;

    /** Probability of precipitation (0-100). */
    @Column(name = "precipitation_probability")
    private Integer precipitationProbability;

    /** Tide state at the time of the solar event. */
    @Column(name = "tide_state", length = 20)
    private String tideState;

    /** Whether the tide was aligned with the location's preferred tide type. */
    @Column(name = "tide_aligned")
    private Boolean tideAligned;
}
