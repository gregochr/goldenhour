package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One survivor's pre-evaluation atmospheric readings — the readings half of the unified
 * survivor read surface (V115), counterpart to {@link ForecastScoreEntity} (the scores half).
 *
 * <p>Grain is {@code (location, evaluation_date, event_type)}. The nightly pipeline
 * re-evaluates the same key across cycles, so the writer UPSERTs against
 * {@code uq_survivor_atmosphere} — latest submission wins, matching {@code forecast_score} and
 * {@code cached_evaluation} semantics.
 *
 * <p>Survivor-by-construction: a row exists only when a survivor is submitted (batch) or
 * evaluated (sync), so the atmospheric hot-topic detectors reading through the survivor read
 * model cannot sample the triaged rejects.
 *
 * <p>All readings are nullable — inland locations have no surge, summer has no snow. The
 * {@code humidity} reading is carried because the {@code SNOW_FRESH} detector co-reads it for
 * the {@code SNOW_MIST} variant.
 */
@Entity
@Table(name = "survivor_atmosphere",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_survivor_atmosphere",
                columnNames = {"location_id", "evaluation_date", "event_type"}),
        indexes = @Index(name = "idx_survivor_atmosphere_date", columnList = "evaluation_date"))
@Getter
@Setter
@NoArgsConstructor
public class SurvivorAtmosphereEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The survivor location this atmospheric snapshot belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private LocationEntity location;

    /** The calendar date the readings forecast. */
    @Column(name = "evaluation_date", nullable = false)
    private LocalDate evaluationDate;

    /** SUNRISE or SUNSET. HOURLY is wildlife-only and never written here. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private TargetType eventType;

    /** Aerosol optical depth, or null. Feeds the DUST proxy. */
    @Column(name = "aerosol_optical_depth", precision = 6, scale = 3)
    private BigDecimal aerosolOpticalDepth;

    /** Surface dust concentration in µg/m³, or null. Feeds the DUST proxy. */
    @Column(name = "dust", precision = 7, scale = 2)
    private BigDecimal dust;

    /** PM2.5 in µg/m³, or null. Rules smoke/haze out of the DUST proxy. */
    @Column(name = "pm2_5", precision = 7, scale = 2)
    private BigDecimal pm25;

    /** Storm surge risk classification name (coastal only), or null. Feeds the SURGE detector. */
    @Column(name = "surge_risk_level", length = 20)
    private String surgeRiskLevel;

    /** Depth of snow lying in metres, or null. Feeds the SNOW_FRESH detector. */
    @Column(name = "snow_depth_m")
    private Double snowDepthMetres;

    /** Altitude of the 0 °C isotherm in metres, or null. Feeds the SNOW_TOPS detector. */
    @Column(name = "freezing_level_m")
    private Double freezingLevelMetres;

    /** Relative humidity percent, or null. Feeds the SNOW_MIST variant of SNOW_FRESH. */
    @Column(name = "humidity")
    private Integer humidity;

    /** When the submission that produced this snapshot ran. */
    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;
}
