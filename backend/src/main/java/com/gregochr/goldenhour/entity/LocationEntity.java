package com.gregochr.goldenhour.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

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

    /**
     * Which solar events are worth photographing here.
     * Defaults to {@link GoldenHourType#BOTH_TIMES} so all locations are evaluated for
     * both sunrise and sunset unless explicitly configured otherwise.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "golden_hour_type", nullable = false)
    @Builder.Default
    private GoldenHourType goldenHourType = GoldenHourType.BOTH_TIMES;

    /**
     * The photographer's tide preferences for this location.
     *
     * <p>Multiple values are supported — e.g. a location may be good at both
     * {@code LOW_TIDE} and {@code MID_TIDE}. An empty set means the location is
     * inland and tide data is not fetched. Stored in the {@code location_tide_type}
     * join table.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "location_tide_type", joinColumns = @JoinColumn(name = "location_id"))
    @Column(name = "tide_type")
    @Builder.Default
    private Set<TideType> tideType = new HashSet<>();

    /**
     * Photography type tags for this location (e.g. SEASCAPE, LANDSCAPE).
     *
     * <p>A location may have multiple types simultaneously. Stored in the
     * {@code location_location_type} join table. Loaded eagerly to avoid
     * lazy-initialisation issues during JSON serialisation.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "location_location_type", joinColumns = @JoinColumn(name = "location_id"))
    @Column(name = "location_type")
    @Builder.Default
    private Set<LocationType> locationType = new HashSet<>();

    /** UTC timestamp when this location was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
