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

import java.time.Instant;
import java.time.LocalDate;

/**
 * A sea-state sample for one coastal location at one solar event — the shared marine carrier (V123)
 * read by the KING_TIDE, SPRING_TIDE and STORM_SURGE hot-topic pills.
 *
 * <p>Grain is {@code (location, evaluation_date, event_type)}, matching the survivor surface. Written
 * once per briefing cycle from the Open-Meteo Marine API (the hot-topic strategies never call an API,
 * they only read this table), UPSERTed against {@code uq_marine_wave}. The sea-state band is derived
 * from {@link #significantWaveHeightMetres} at render time via {@code SeaState.fromHs} and is never
 * stored, so every pill classifies the same Hs identically. All readings are nullable — a land/estuary
 * grid cell returns no wave data.
 */
@Entity
@Table(name = "marine_wave",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_marine_wave",
                columnNames = {"location_id", "evaluation_date", "event_type"}),
        indexes = @Index(name = "idx_marine_wave_date", columnList = "evaluation_date"))
@Getter
@Setter
@NoArgsConstructor
public class MarineWaveEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The coastal location this sea-state sample belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private LocationEntity location;

    /** The calendar date the sample forecasts. */
    @Column(name = "evaluation_date", nullable = false)
    private LocalDate evaluationDate;

    /** SUNRISE or SUNSET — the aligned high-water event. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private TargetType eventType;

    /** Significant wave height Hs (mean of the highest third) in metres, or null. */
    @Column(name = "significant_wave_height_m")
    private Double significantWaveHeightMetres;

    /** Swell wave height (long-period component) in metres, or null. */
    @Column(name = "swell_wave_height_m")
    private Double swellWaveHeightMetres;

    /** Mean wave direction of origin in degrees, or null. */
    @Column(name = "wave_direction_deg")
    private Integer waveDirectionDegrees;

    /** When the briefing cycle that produced this sample ran. */
    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;
}
