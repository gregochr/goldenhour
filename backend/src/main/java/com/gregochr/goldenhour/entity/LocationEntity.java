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
 * JPA entity representing a location for which forecasts are evaluated.
 *
 * <p>Locations are seeded from {@code application.yml} on startup and may be added
 * at runtime via the {@code POST /api/locations} endpoint. They persist across restarts.
 */
@Entity
@Table(name = "locations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable name used as the location identifier (e.g. "Durham UK"). */
    @Column(nullable = false, unique = true)
    private String name;

    /** Latitude in decimal degrees. */
    @Column(nullable = false)
    private double lat;

    /** Longitude in decimal degrees. */
    @Column(nullable = false)
    private double lon;

    /** UTC timestamp when this location was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
