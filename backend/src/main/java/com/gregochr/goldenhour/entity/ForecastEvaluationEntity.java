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

    /** Human-readable name for the location (e.g. "Durham UK"). */
    @Column(name = "location_name", nullable = false)
    private String locationName;

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

    /** Colour potential rating assigned by Claude (1 = poor, 5 = exceptional). */
    @Column(name = "rating")
    private Integer rating;

    /** Claude's plain English explanation of the rating. */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** UTC time of the solar event (sunrise or sunset). */
    @Column(name = "solar_event_time")
    private LocalDateTime solarEventTime;

    /** Compass azimuth in degrees (clockwise from North) of the solar event. */
    @Column(name = "azimuth_deg")
    private Integer azimuthDeg;
}
