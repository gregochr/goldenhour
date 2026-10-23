package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * The sea state at one daylight high water of a big-seas structure location (V188).
 *
 * <p>Grain is {@code (location, hw_time)}. Every daylight high water in the briefing horizon is
 * stored, qualifying or not, so the big-seas thresholds can be tuned against real numbers; whether a
 * row is "big seas" is decided at serve time and never persisted. The wave figures are the peak
 * hour inside {@code [windowStart, windowEnd]}, the high water's +-90 minute window clipped to civil
 * dawn..dusk. All times are UTC with no zone, as {@code tide_extreme.event_time} is. Rows for a
 * location are replaced wholesale each refresh ({@code MarineHighWaterWriter}).
 */
@Entity
@Table(name = "marine_high_water",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_marine_high_water",
                columnNames = {"location_id", "hw_time"}),
        indexes = @Index(name = "idx_marine_high_water_hw_time", columnList = "hw_time"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarineHighWaterEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The structure location this sample belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private LocationEntity location;

    /** UTC time of the high water. */
    @Column(name = "hw_time", nullable = false)
    private LocalDateTime hwTime;

    /** Height of the high water in metres, or null. */
    @Column(name = "hw_height_m", precision = 6, scale = 3)
    private BigDecimal hwHeightMetres;

    /** Start of the sampled window (UTC), clipped to civil dawn. */
    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    /** End of the sampled window (UTC), clipped to civil dusk. */
    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** UTC hour stamp of the highest wave inside the window. */
    @Column(name = "peak_time", nullable = false)
    private LocalDateTime peakTime;

    /** Significant wave height at the peak hour, metres. */
    @Column(name = "wave_height_m")
    private Double waveHeightMetres;

    /** Mean wave period at the peak hour, seconds. */
    @Column(name = "wave_period_s")
    private Double wavePeriodSeconds;

    /** Swell wave height at the peak hour, metres. */
    @Column(name = "swell_wave_height_m")
    private Double swellWaveHeightMetres;

    /** Swell wave period at the peak hour, seconds. */
    @Column(name = "swell_wave_period_s")
    private Double swellWavePeriodSeconds;

    /** Mean wave direction of origin at the peak hour, degrees. */
    @Column(name = "wave_direction_deg")
    private Integer waveDirectionDegrees;

    /** When the briefing cycle that produced this row ran. */
    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;
}
