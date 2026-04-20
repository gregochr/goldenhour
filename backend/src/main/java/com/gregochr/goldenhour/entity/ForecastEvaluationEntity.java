package com.gregochr.goldenhour.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gregochr.goldenhour.model.TriageReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * JPA entity representing a single forecast evaluation run for one location, date, and target type.
 *
 * <p>Multiple rows per target date are expected — one per scheduled run — enabling accuracy
 * tracking as the forecast horizon narrows from T+7 down to T+0.
 */
@Entity
@Table(name = "forecast_evaluation")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForecastEvaluationEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Latitude of the forecast location in decimal degrees. */
    @Column(name = "location_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal locationLat;

    /** Longitude of the forecast location in decimal degrees. */
    @Column(name = "location_lon", nullable = false, precision = 9, scale = 6)
    private BigDecimal locationLon;

    /** The location this forecast was evaluated for. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "location_id", nullable = false)
    @JsonIgnore
    private LocationEntity location;

    /** The calendar date this evaluation is forecasting. */
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    /** Whether this evaluation is for sunrise or sunset. */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private TargetType targetType;

    /** UTC timestamp when this evaluation was computed. */
    @Column(name = "forecast_run_at", nullable = false)
    private LocalDateTime forecastRunAt;

    /** Number of days between forecast_run_at and target_date. */
    @Column(name = "days_ahead", nullable = false)
    private Integer daysAhead;

    /** Low cloud cover percentage (0-100) in the ±30 min window. */
    @Column(name = "low_cloud")
    private Integer lowCloud;

    /** Mid cloud cover percentage (0-100) in the ±30 min window. */
    @Column(name = "mid_cloud")
    private Integer midCloud;

    /** High cloud cover percentage (0-100) in the ±30 min window. */
    @Column(name = "high_cloud")
    private Integer highCloud;

    /** Visibility in metres. */
    @Column(name = "visibility")
    private Integer visibility;

    /** Wind speed in m/s. */
    @Column(name = "wind_speed", precision = 5, scale = 2)
    private BigDecimal windSpeed;

    /** Wind direction in degrees (0-360). */
    @Column(name = "wind_direction")
    private Integer windDirection;

    /** Precipitation in mm. */
    @Column(name = "precipitation", precision = 5, scale = 2)
    private BigDecimal precipitation;

    /** Relative humidity percentage. */
    @Column(name = "humidity")
    private Integer humidity;

    /** WMO weather condition code. */
    @Column(name = "weather_code")
    private Integer weatherCode;

    /** Atmospheric boundary layer height in metres. */
    @Column(name = "boundary_layer_height")
    private Integer boundaryLayerHeight;

    /** Incoming shortwave solar radiation in W/m². */
    @Column(name = "shortwave_radiation", precision = 7, scale = 2)
    private BigDecimal shortwaveRadiation;

    /** Fine particulate matter concentration in µg/m³. */
    @Column(name = "pm2_5", precision = 7, scale = 2)
    private BigDecimal pm25;

    /** Dust concentration in µg/m³. */
    @Column(name = "dust", precision = 7, scale = 2)
    private BigDecimal dust;

    /** Aerosol optical depth (dimensionless). */
    @Column(name = "aerosol_optical_depth", precision = 5, scale = 3)
    private BigDecimal aerosolOpticalDepth;

    /** Air temperature at 2 m above ground in °C. Populated for all evaluation models. */
    @Column(name = "temperature_celsius")
    private Double temperatureCelsius;

    /** Dew point at 2 m above ground in °C. Null for historical rows. */
    @Column(name = "dew_point_celsius")
    private Double dewPointCelsius;

    /** Apparent (feels-like) temperature at 2 m above ground in °C. Populated for all models. */
    @Column(name = "apparent_temperature_celsius")
    private Double apparentTemperatureCelsius;

    /** Probability of precipitation in percent (0–100). Populated for all evaluation models. */
    @Column(name = "precipitation_probability_percent")
    private Integer precipitationProbabilityPercent;

    /** Which evaluation path produced this row — HAIKU, SONNET, or WILDLIFE (comfort-only). */
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_model", length = 10)
    private EvaluationModel evaluationModel;

    /** Colour potential rating assigned by Claude (1 = poor, 5 = exceptional). Populated by Haiku only. */
    @Column(name = "rating")
    private Integer rating;

    /** Dramatic colour potential score (0–100). Requires clouds to catch and reflect light. */
    @Column(name = "fiery_sky_potential")
    private Integer fierySkyPotential;

    /** Overall light quality score (0–100). Can score well with a clear sky. */
    @Column(name = "golden_hour_potential")
    private Integer goldenHourPotential;

    /** Claude's plain English explanation of the evaluation. */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** UTC time of the solar event (sunrise or sunset). */
    @Column(name = "solar_event_time")
    private LocalDateTime solarEventTime;

    /** Compass azimuth in degrees (clockwise from North) of the solar event. */
    @Column(name = "azimuth_deg")
    private Integer azimuthDeg;

    /** Tide state at solar event time (HIGH, LOW, MID) or null for inland locations. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tide_state", length = 10)
    private TideState tideState;

    /** UTC time of the next high tide, or null for inland locations. */
    @Column(name = "next_high_tide_time")
    private LocalDateTime nextHighTideTime;

    /** Height of the next high tide in metres, or null for inland locations. */
    @Column(name = "next_high_tide_height_m", precision = 5, scale = 2)
    private BigDecimal nextHighTideHeightMetres;

    /** UTC time of the next low tide, or null for inland locations. */
    @Column(name = "next_low_tide_time")
    private LocalDateTime nextLowTideTime;

    /** Height of the next low tide in metres, or null for inland locations. */
    @Column(name = "next_low_tide_height_m", precision = 5, scale = 2)
    private BigDecimal nextLowTideHeightMetres;

    /** True if tide state matches location preference, null for inland locations. */
    @Column(name = "tide_aligned")
    private Boolean tideAligned;

    /** Low cloud cover at the solar horizon point (113 km toward sun), or null if unavailable. */
    @Column(name = "solar_low_cloud")
    private Integer solarLowCloud;

    /** Mid cloud cover at the solar horizon point (113 km toward sun), or null if unavailable. */
    @Column(name = "solar_mid_cloud")
    private Integer solarMidCloud;

    /** High cloud cover at the solar horizon point (113 km toward sun), or null if unavailable. */
    @Column(name = "solar_high_cloud")
    private Integer solarHighCloud;

    /** Low cloud cover at the antisolar horizon point (113 km away from sun), or null if unavailable. */
    @Column(name = "antisolar_low_cloud")
    private Integer antisolarLowCloud;

    /** Mid cloud cover at the antisolar horizon point (113 km away from sun), or null if unavailable. */
    @Column(name = "antisolar_mid_cloud")
    private Integer antisolarMidCloud;

    /** High cloud cover at the antisolar horizon point (113 km away from sun), or null if unavailable. */
    @Column(name = "antisolar_high_cloud")
    private Integer antisolarHighCloud;

    /** Low cloud at 226 km along the solar azimuth, for strip vs blanket detection. Null if unavailable. */
    @Column(name = "far_solar_low_cloud")
    private Integer farSolarLowCloud;

    /** Fiery sky potential without directional data (LITE tier), or null if no directional data. */
    @Column(name = "basic_fiery_sky_potential")
    private Integer basicFierySkyPotential;

    /** Golden hour potential without directional data (LITE tier), or null if no directional data. */
    @Column(name = "basic_golden_hour_potential")
    private Integer basicGoldenHourPotential;

    /** Summary without directional data (LITE tier), or null if no directional data. */
    @Column(name = "basic_summary", columnDefinition = "TEXT")
    private String basicSummary;

    /** Low cloud % at the solar horizon at event time, from the temporal trend. */
    @Column(name = "solar_trend_event_low_cloud")
    private Integer solarTrendEventLowCloud;

    /** Low cloud % at the solar horizon at the earliest trend slot (T-3h). */
    @Column(name = "solar_trend_earliest_low_cloud")
    private Integer solarTrendEarliestLowCloud;

    /** True if the solar horizon low cloud trend is building (increase >= 20pp). */
    @Column(name = "solar_trend_building")
    private Boolean solarTrendBuilding;

    /** Low cloud % at the upwind sample point at current time. */
    @Column(name = "upwind_current_low_cloud")
    private Integer upwindCurrentLowCloud;

    /** Low cloud % at the upwind sample point at event time. */
    @Column(name = "upwind_event_low_cloud")
    private Integer upwindEventLowCloud;

    /** Distance in km to the upwind sample point. */
    @Column(name = "upwind_distance_km")
    private Integer upwindDistanceKm;

    /** Total storm surge in metres (pressure + wind components). */
    @Column(name = "surge_total_metres")
    private Double surgeTotalMetres;

    /** Pressure-driven surge component in metres (inverse barometer effect). */
    @Column(name = "surge_pressure_metres")
    private Double surgePressureMetres;

    /** Wind-driven surge component in metres (quadratic wind stress). */
    @Column(name = "surge_wind_metres")
    private Double surgeWindMetres;

    /** Storm surge risk classification (NONE, LOW, MODERATE, HIGH). */
    @Column(name = "surge_risk_level", length = 10)
    private String surgeRiskLevel;

    /** Tidal range adjusted for surge — upper bound estimate in metres. */
    @Column(name = "surge_adjusted_range_metres")
    private Double surgeAdjustedRangeMetres;

    /** Predicted astronomical tidal range in metres. */
    @Column(name = "surge_astronomical_range_metres")
    private Double surgeAstronomicalRangeMetres;

    /** Cloud inversion score returned by Claude (0–10), or null if not applicable. */
    @Column(name = "inversion_score")
    private Integer inversionScore;

    /** Cloud inversion potential classification (NONE, MODERATE, STRONG), or null. */
    @Column(name = "inversion_potential", length = 10)
    private String inversionPotential;

    /** Bluebell photography condition score (0–10), populated by Claude during bluebell season. */
    @Column(name = "bluebell_score")
    private Integer bluebellScore;

    /** Bluebell photography condition summary from Claude, or null. */
    @Column(name = "bluebell_summary", columnDefinition = "TEXT")
    private String bluebellSummary;

    /** Categorised user-facing triage reason, or null if the row was scored by Claude. */
    @Enumerated(EnumType.STRING)
    @Column(name = "triage_reason", length = 40)
    private TriageReason triageReason;

    /** Formatted explanation text for the triage stand-down (with concrete numbers), or null. */
    @Column(name = "triage_message", columnDefinition = "TEXT")
    private String triageMessage;

    /**
     * Returns the location name for JSON serialisation, preserving the API contract.
     *
     * @return the location name, or {@code null} if the location is not set
     */
    @JsonProperty("locationName")
    public String getLocationName() {
        return location != null ? location.getName() : null;
    }
}
