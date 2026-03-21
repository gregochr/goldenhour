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

/**
 * JPA entity representing a stored aurora forecast result for a single location and night.
 *
 * <p>Results are written by {@code AuroraForecastRunService} when the user manually triggers
 * an aurora forecast run from the Admin UI. Unlike {@code AuroraStateCache} (which is ephemeral
 * and tied to live NOAA alerts), these records persist across restarts and are keyed by date
 * so the map view can display historical aurora forecasts.
 */
@Entity
@Table(name = "aurora_forecast_result")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuroraForecastResultEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The location this result applies to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private LocationEntity location;

    /** The night this forecast is for (the evening of this date into the early morning of the next). */
    @Column(name = "forecast_date", nullable = false)
    private LocalDate forecastDate;

    /** When the forecast was generated. */
    @Column(name = "run_timestamp", nullable = false)
    private Instant runTimestamp;

    /** Aurora photography rating, 1–5 stars. */
    @Column(name = "stars", nullable = false)
    private int stars;

    /** One-line summary suitable for display in the map popup. */
    @Column(name = "summary", length = 500)
    private String summary;

    /**
     * Multi-line factor breakdown from Claude (bullet points with ✓/–/✗ icons),
     * or null for triage-template results.
     */
    @Column(name = "factors", columnDefinition = "TEXT")
    private String factors;

    /** True if this result was produced by the weather triage template (not Claude). */
    @Column(name = "triaged", nullable = false)
    private boolean triaged;

    /** Human-readable reason for triage rejection, or null if Claude-scored. */
    @Column(name = "triage_reason", length = 500)
    private String triageReason;

    /** Source of the score: {@code "claude"} or {@code "triage_template"}. */
    @Column(name = "source", length = 50)
    private String source;

    /** The alert level that drove scoring (e.g. MODERATE, STRONG). */
    @Column(name = "alert_level", length = 20)
    private String alertLevel;

    /** Maximum Kp value forecast for this night's dark window. */
    @Column(name = "max_kp")
    private Double maxKp;
}
