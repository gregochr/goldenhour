package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persisted stability classification for a single Open-Meteo grid cell.
 *
 * <p>Written through on every scheduled forecast run so the snapshot survives
 * container restarts. One row per grid cell, upserted via
 * {@code grid_cell_key} unique index.
 */
@Entity
@Table(name = "stability_snapshot")
@Getter
@Setter
@NoArgsConstructor
public class StabilitySnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grid_cell_key", nullable = false)
    private String gridCellKey;

    @Column(name = "grid_lat", nullable = false)
    private double gridLat;

    @Column(name = "grid_lng", nullable = false)
    private double gridLng;

    @Enumerated(EnumType.STRING)
    @Column(name = "stability_level", nullable = false)
    private ForecastStability stabilityLevel;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "evaluation_window_days", nullable = false)
    private int evaluationWindowDays;

    @Column(name = "location_names", nullable = false)
    private String locationNames;

    @Column(name = "classified_at", nullable = false)
    private Instant classifiedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
