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
 * One candidate's pre-evaluation atmospheric readings — the readings half of the unified
 * hot-topic read surface (V115), counterpart to {@link ForecastScoreEntity} (the scores half).
 *
 * <p>⚠️ <b>This class and its table were named {@code SurvivorAtmosphereEntity}/{@code
 * survivor_atmosphere} until V159 (2026-09-30) — "survivor" was already historical by then and the
 * rename is this migration.</b> Through 2026-09-29 a row existed only when a candidate survived
 * weather triage and the Gate 4 stability gate. The "record conditions for every place" change
 * (Phase 1, owner decision 2026-09-30) reversed that: a row is now written for every candidate
 * whose weather was fetched this cycle, whatever the later triage verdict or Gate 4 decision — a
 * place stood down for cloud can still carry a dust, snow or storm-surge reading. See {@code
 * SlotAtmosphereWriter}'s own javadoc for exactly which dispositions carry a row and which do not.
 *
 * <p>Grain is {@code (location, evaluation_date, event_type)}. The nightly pipeline
 * re-evaluates the same key across cycles, so the writer UPSERTs against
 * {@code uq_slot_atmosphere} — latest submission wins, matching {@code forecast_score} and
 * {@code cached_evaluation} semantics.
 *
 * <p>All readings are nullable — inland locations have no surge, summer has no snow. The
 * {@code humidity} reading is carried because the {@code SNOW_FRESH} detector co-reads it for
 * the {@code SNOW_MIST} variant.
 */
@Entity
@Table(name = "slot_atmosphere",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_slot_atmosphere",
                columnNames = {"location_id", "evaluation_date", "event_type"}),
        indexes = @Index(name = "idx_slot_atmosphere_date", columnList = "evaluation_date"))
@Getter
@Setter
@NoArgsConstructor
public class SlotAtmosphereEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The location this atmospheric snapshot belongs to. */
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

    /** 2 m air temperature in °C, or null. Gates the freezing-fog / hoar-frost SNOW_MIST facts. */
    @Column(name = "temperature_celsius")
    private Double temperatureCelsius;

    /**
     * Cloud inversion likelihood score (0–10, {@code InversionScoreCalculator}), or null when
     * either the location was not inversion-eligible (elevation &lt; 200 m, or does not overlook
     * water) or the calculator itself returned null for an eligible location (missing dew point or
     * surface temperature — see {@code InversionScoreCalculator.calculate}'s own null guards). V158
     * (Phase 2 of "record conditions for every place", owner decision 2026-09-30): the deterministic
     * calculator's own score, captured at the same collection-time seam as every other reading on
     * this row — unlike the Claude-echoed {@code forecast_score} INVERSION component, this is
     * populated (or set null) for a triaged-out or Gate-4-stood-down candidate too, since the
     * calculator runs before both checks. Feeds {@code InversionHotTopicStrategy}, always paired with
     * {@link #inversionScored} — a null reading alone does not mean "unscored"; see that field's
     * own javadoc. The map popup's inversion badge
     * stays on Claude's echo ({@code forecast_evaluation.inversion_score}) and does not read this
     * column.
     */
    @Column(name = "inversion_score")
    private Double inversionScore;

    /**
     * True when THIS row was produced by a writer that ran {@code InversionScoreCalculator}'s
     * eligibility check this cycle, whatever the result — false only for a row written before V158
     * added this column (default {@code FALSE} on the migration). V158 round 4 (a Codex P1 against
     * round 3's own unify-onto-one-rule fix, owner decision 2026-09-30): a fresh {@link
     * #inversionScore} of null is
     * a genuine, authoritative answer — an ineligible location, or an eligible one the calculator
     * could not score this cycle for want of weather inputs — and must NOT fall back to Claude's
     * {@code forecast_score} echo the way an absent (pre-column, {@code scored = false}) row does,
     * or a stale STRONG rating from a previous cycle would be revived after the current data no
     * longer supports it (Claude's echo is left in place indefinitely by {@code ForecastScoreWriter}
     * whenever a later evaluation carries no score of its own). {@code SlotAtmosphereWriter}
     * sets this {@code true} on every write, with a score or with a null one alike.
     */
    @Column(name = "inversion_scored", nullable = false)
    private boolean inversionScored;

    /**
     * The OLD {@code InversionScoreCalculator}'s score for a slot the v2 calculator also scored
     * (inversion-v2-plan Phase 1b, V202), or null when v2 did not compute. Written on every write, so a
     * cleared valley floor or a switched-off v2 leaves no stale value. {@code inversion_score_v1 IS NOT NULL}
     * marks a "v2 era" row per slot, which is how the {@code INVERSION_CALC} series is split per site rather
     * than at one date.
     */
    @Column(name = "inversion_score_v1")
    private Double inversionScoreV1;

    /**
     * The v2 calculator's own score before the first-winter raise cap, or null when v2 did not compute
     * (V202). The evidence for deciding whether to lift the cap; {@link #inversionScore} remains the value
     * that drives the readers.
     */
    @Column(name = "inversion_score_v2_raw")
    private Double inversionScoreV2Raw;

    /**
     * Valley mist likelihood score (0–10, {@code ValleyMistCalculator}), or null when the location
     * is not {@code mist_prone} or the calculator could not score it for want of inputs. V185
     * (valley-mist-plan M1a): captured at the same collection-time seam as every other reading, so a
     * triaged-out or stood-down candidate is recorded too. Always paired with {@link #mistScored} —
     * a null reading alone does not mean "unscored".
     */
    @Column(name = "mist_score")
    private Double mistScore;

    /**
     * True when THIS row was produced by a writer that ran the valley-mist check this cycle,
     * whatever the result — false only for a row written before V185 (default {@code FALSE}). The
     * V158 {@link #inversionScored} rule verbatim: a fresh null {@link #mistScore} is an
     * authoritative answer (an unflagged site, or one the calculator could not score) where a
     * pre-column row's null is not. {@code SlotAtmosphereWriter} sets this {@code true} on every
     * write, with a score or with a null one alike.
     */
    @Column(name = "mist_scored", nullable = false)
    private boolean mistScored;

    /**
     * The {@code MistPotential} band's enum NAME for {@link #mistScore}, or null exactly when the
     * score is null. Stored because FOGBOUND is decided by event-hour visibility, not by the score:
     * a white frame and a LIKELY morning can share a score, and a reader rebuilding the band from
     * the score alone would recommend the white frame.
     */
    @Column(name = "mist_potential", length = 10)
    private String mistPotential;

    /**
     * The coastal mist band's enum NAME ({@code NONE}/{@code LIGHT}/{@code THICK}, coastal-mist-plan Phase 1),
     * or null when the check did not run for this write (switch off, an inland site, a {@code mist_prone}
     * site). Written on every write. A plain string, as {@link #mistPotential} is: no {@code @Enumerated}, so
     * no {@code LocalH2EnumWidener} entry. Always paired with {@link #coastalMistScored}.
     */
    @Column(name = "coastal_mist_band", length = 10)
    private String coastalMistBand;

    /**
     * True when THIS row was produced by a write that ran the coastal mist check this cycle, whatever the
     * band; false for a row written before the column and for a write where the check did not run (the
     * {@link #mistScored} pattern, except that here a skipped check is false rather than an authoritative
     * null). When true, {@link #coastalMistBand} is never null.
     */
    @Column(name = "coastal_mist_scored", nullable = false)
    private boolean coastalMistScored;

    /**
     * The event-slot low cloud cover (percent) the coastal mist check read (V211, coastal-mist-plan Phase 2), or
     * null when the check did not run or the cloud data was missing. Written on every write, so a skipped check
     * leaves no stale value. Gates the Sea mist hot topic (low cloud under 50 %).
     */
    @Column(name = "coastal_mist_low_cloud_pct")
    private Integer coastalMistLowCloudPct;

    /**
     * The event-slot precipitation (mm) the coastal mist check read (V211, Phase 2), or null when the check did
     * not run or the weather was missing. Written on every write. Gates the Sea mist hot topic (a dry slot).
     */
    @Column(name = "coastal_mist_precip_mm")
    private Double coastalMistPrecipMm;

    /** Total storm surge in metres (pressure + wind), or null. Feeds the STORM_SURGE facts line. */
    @Column(name = "surge_total_m")
    private Double surgeTotalMetres;

    /** 10 m wind speed used in the surge calc, in m/s, or null. Feeds the STORM_SURGE facts line. */
    @Column(name = "surge_wind_speed_ms")
    private Double surgeWindSpeedMs;

    /** Wind direction (meteorological, degrees FROM) at surge time, or null. STORM_SURGE facts. */
    @Column(name = "surge_wind_direction_deg")
    private Double surgeWindDirectionDegrees;

    /** When the submission that produced this snapshot ran. */
    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;
}
