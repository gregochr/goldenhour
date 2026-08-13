package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Analysed (reanalysis) cloud for one past {@link ForecastEvaluationEntity}, at both points the
 * forecast makes claims about.
 *
 * <p>The colour model is a gap and a screen: low cloud at the <strong>solar horizon</strong>
 * (113 km along the solar azimuth) decides whether the low sun gets through, and mid/high cloud
 * at the <strong>observer</strong> is the canvas that light lands on. A forecast can be wrong
 * about either independently — a correctly-called gap over a canvas that never materialised is a
 * different failure from a canvas that held over a horizon that closed — so both are verified.
 *
 * <p>Beyond the two claims, three <em>measurement-pass</em> readings capture what the forecast's
 * own persistence throws away, so the sampling geometry itself can be evaluated: the cone
 * extremes ({@code horizonLowMin}/{@code horizonLowMax} — the forecast keeps only the mean, which
 * cannot tell a uniform deck from a wall with a clear third) and the far-solar corridor reading
 * ({@code farLowCloud} at 226 km — the centre of a 4 km mid canvas's blocking corridor and only
 * the near edge of an 8 km cirrus one, whose centre is ~319 km; the 113 km gate reads neither).
 *
 * <p>Unlike {@code actual_outcome} this needs no human: it verifies the <em>cloud</em> claims the
 * scoring rules rest on, not the aesthetic judgement. It cannot tell you a sunset was beautiful —
 * only whether the gap and the canvas were really there.
 *
 * <p>The observed columns are nullable so an attempt that found no archive data is recorded rather
 * than retried indefinitely.
 */
@Entity
@Table(name = "cloud_verification")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudVerificationEntity {

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The evaluation this verifies. Unique — one verification per evaluation. */
    @Column(name = "forecast_evaluation_id", nullable = false, unique = true)
    private Long forecastEvaluationId;

    /** Latitude of the sampled solar-horizon point. */
    @Column(name = "horizon_sample_lat", nullable = false)
    private double horizonSampleLat;

    /** Longitude of the sampled solar-horizon point. */
    @Column(name = "horizon_sample_lon", nullable = false)
    private double horizonSampleLon;

    /** Analysed low cloud (%) at the solar horizon — the blocker. */
    @Column(name = "horizon_low_cloud")
    private Integer horizonLowCloud;

    /** Lowest analysed low cloud (%) across the three solar-cone bearings — the gap detector. */
    @Column(name = "horizon_low_min")
    private Integer horizonLowMin;

    /** Highest analysed low cloud (%) across the three solar-cone bearings. */
    @Column(name = "horizon_low_max")
    private Integer horizonLowMax;

    /** Analysed mid cloud (%) at the solar horizon. */
    @Column(name = "horizon_mid_cloud")
    private Integer horizonMidCloud;

    /** Analysed high cloud (%) at the solar horizon. */
    @Column(name = "horizon_high_cloud")
    private Integer horizonHighCloud;

    /**
     * Analysed low cloud (%) at the 226 km far-solar point — the centre of a 4 km mid canvas's
     * blocking corridor, and only the near edge of an 8 km cirrus one.
     */
    @Column(name = "far_low_cloud")
    private Integer farLowCloud;

    /** Analysed low cloud (%) overhead at the observer. */
    @Column(name = "observer_low_cloud")
    private Integer observerLowCloud;

    /** Analysed mid cloud (%) overhead at the observer — canvas. */
    @Column(name = "observer_mid_cloud")
    private Integer observerMidCloud;

    /** Analysed high cloud (%) overhead at the observer — canvas. */
    @Column(name = "observer_high_cloud")
    private Integer observerHighCloud;

    /** The archive hour actually sampled, or {@code null} if unavailable. */
    @Column(name = "observed_at")
    private LocalDateTime observedAt;

    /** When this verification was performed. */
    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt;
}
