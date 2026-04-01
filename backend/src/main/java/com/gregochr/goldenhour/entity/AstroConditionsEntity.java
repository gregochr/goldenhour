package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA entity representing nightly astro observing conditions for a single location.
 *
 * <p>Template-scored (no Claude call) from cloud cover, visibility, and moonlight.
 * Available to all users, not just PRO/ADMIN. Keyed by {@code (location_id, forecast_date)}.
 */
@Entity
@Table(name = "astro_conditions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AstroConditionsEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The location this score applies to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private LocationEntity location;

    /** The night scored (evening of this date into early morning of the next). */
    @Column(name = "forecast_date", nullable = false)
    private LocalDate forecastDate;

    /** When the scoring run was executed. */
    @Column(name = "run_timestamp", nullable = false)
    private Instant runTimestamp;

    /** Overall rating, 1–5 stars. */
    @Column(name = "stars", nullable = false)
    private int stars;

    /** Cloud cover factor modifier (e.g. +1.0 for clear, −1.5 for overcast). */
    @Column(name = "cloud_modifier", nullable = false)
    private double cloudModifier;

    /** Visibility factor modifier (e.g. +0.5 for crystal clear, −1.5 for fog). */
    @Column(name = "visibility_modifier", nullable = false)
    private double visibilityModifier;

    /** Moon factor modifier (e.g. +0.5 for below horizon, −1.0 for bright gibbous). */
    @Column(name = "moon_modifier", nullable = false)
    private double moonModifier;

    /** True if persistent fog forced the score to 1 star. */
    @Column(name = "fog_capped", nullable = false)
    private boolean fogCapped;

    /** Human-readable cloud explanation, e.g. "Clear skies — excellent visibility". */
    @Column(name = "cloud_explanation", length = 200)
    private String cloudExplanation;

    /** Human-readable visibility explanation. */
    @Column(name = "visibility_explanation", length = 200)
    private String visibilityExplanation;

    /** Human-readable moon explanation. */
    @Column(name = "moon_explanation", length = 200)
    private String moonExplanation;

    /** Combined summary sentence. */
    @Column(name = "summary", length = 500)
    private String summary;

    /** Start of the scored night window (nautical dusk UTC). */
    @Column(name = "nautical_dusk_utc")
    private LocalDateTime nauticalDuskUtc;

    /** End of the scored night window (nautical dawn UTC, following morning). */
    @Column(name = "nautical_dawn_utc")
    private LocalDateTime nauticalDawnUtc;

    /** Mean cloud cover across the night window (0–100). */
    @Column(name = "mean_cloud_pct")
    private Integer meanCloudPct;

    /** Mean visibility across the night window in metres. */
    @Column(name = "mean_visibility_m")
    private Integer meanVisibilityM;

    /** Moon illumination at the night-window midpoint (0–100). */
    @Column(name = "moon_illumination_pct")
    private Double moonIlluminationPct;

    /** Moon altitude at the night-window midpoint in degrees (negative = below horizon). */
    @Column(name = "moon_altitude_deg")
    private Double moonAltitudeDeg;

    /** Lunar phase name at the night-window midpoint. */
    @Column(name = "moon_phase", length = 30)
    private String moonPhase;
}
