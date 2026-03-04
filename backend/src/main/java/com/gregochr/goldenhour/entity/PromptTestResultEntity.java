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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents one location evaluation within a prompt test run.
 */
@Entity
@Table(name = "prompt_test_result")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PromptTestResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_run_id", nullable = false)
    private Long testRunId;

    @Column(name = "location_id", nullable = false)
    private Long locationId;

    @Column(name = "location_name", nullable = false)
    private String locationName;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private TargetType targetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_model", nullable = false, length = 10)
    private EvaluationModel evaluationModel;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "fiery_sky_potential")
    private Integer fierySkyPotential;

    @Column(name = "golden_hour_potential")
    private Integer goldenHourPotential;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Lob
    @Column(name = "prompt_sent")
    private String promptSent;

    @Lob
    @Column(name = "response_json")
    private String responseJson;

    @Lob
    @Column(name = "atmospheric_data_json")
    private String atmosphericDataJson;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "cost_pence", nullable = false)
    @Builder.Default
    private Integer costPence = 0;

    @Column(name = "cost_micro_dollars")
    private Long costMicroDollars;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "cache_creation_input_tokens")
    private Long cacheCreationInputTokens;

    @Column(name = "cache_read_input_tokens")
    private Long cacheReadInputTokens;

    @Column(name = "succeeded", nullable = false)
    @Builder.Default
    private Boolean succeeded = false;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // --- Denormalised atmospheric fields ---

    @Column(name = "low_cloud_percent")
    private Integer lowCloudPercent;

    @Column(name = "mid_cloud_percent")
    private Integer midCloudPercent;

    @Column(name = "high_cloud_percent")
    private Integer highCloudPercent;

    @Column(name = "visibility_metres")
    private Integer visibilityMetres;

    @Column(name = "wind_speed_ms")
    private BigDecimal windSpeedMs;

    @Column(name = "wind_direction_degrees")
    private Integer windDirectionDegrees;

    @Column(name = "precipitation_mm")
    private BigDecimal precipitationMm;

    @Column(name = "humidity_percent")
    private Integer humidityPercent;

    @Column(name = "weather_code")
    private Integer weatherCode;

    @Column(name = "pm25")
    private BigDecimal pm25;

    @Column(name = "dust_ugm3")
    private BigDecimal dustUgm3;

    @Column(name = "aerosol_optical_depth")
    private BigDecimal aerosolOpticalDepth;

    @Column(name = "temperature_celsius")
    private Double temperatureCelsius;

    @Column(name = "apparent_temperature_celsius")
    private Double apparentTemperatureCelsius;

    @Column(name = "precipitation_probability")
    private Integer precipitationProbability;

    @Column(name = "tide_state", length = 20)
    private String tideState;

    @Column(name = "tide_aligned")
    private Boolean tideAligned;
}
