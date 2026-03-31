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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import com.gregochr.goldenhour.model.CoastalParameters;
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
 * <p>Locations are managed exclusively via the REST API and persist in the database.
 * Disabled locations are excluded from forecast runs and the map view.
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
     *
     * <p>Multiple values are supported — e.g. a location may be good for both
     * {@code SUNRISE} and {@code SUNSET}. An empty set defaults to
     * {@code [SUNRISE, SUNSET]} at creation time. Stored in the
     * {@code location_solar_event_type} join table.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "location_solar_event_type", joinColumns = @JoinColumn(name = "location_id"))
    @Column(name = "solar_event_type")
    @Builder.Default
    private Set<SolarEventType> solarEventType = new HashSet<>();

    /**
     * The photographer's tide preferences for this location.
     *
     * <p>Multiple values are supported — e.g. a location may be good at both
     * {@code LOW} and {@code MID}. An empty set means the location is inland
     * and tide data is not fetched. Stored in the {@code location_tide_type}
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

    /** Optional geographic region this location belongs to. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "region_id")
    private RegionEntity region;

    /** Whether this location is enabled for forecast runs. */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** UTC timestamp when this location was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Number of consecutive forecast failures for this location. Used for auto-disabling. */
    @Column(name = "consecutive_failures")
    @Builder.Default
    private Integer consecutiveFailures = 0;

    /** UTC timestamp of the most recent forecast failure, or null if none. */
    @Column(name = "last_failure_at")
    private LocalDateTime lastFailureAt;

    /**
     * Reason this location was disabled, or null if enabled.
     * Examples: "Auto-disabled after 3 consecutive failures", or set by admin.
     */
    @Column(name = "disabled_reason")
    private String disabledReason;

    /**
     * Estimated drive time in minutes from the last-refreshed source location
     * (typically the user's current position). Null if not yet computed.
     * Updated via {@code POST /api/locations/drive-times}.
     */
    @Column(name = "drive_duration_minutes")
    private Integer driveDurationMinutes;

    /**
     * Bortle dark-sky class at this location (1 = darkest, 8 = city sky).
     *
     * <p>Populated by {@code BortleEnrichmentService} via the lightpollutionmap.info API.
     * Null until enrichment has been run. Used by aurora scoring to filter candidates
     * and adjust the star rating.
     */
    @Column(name = "bortle_class")
    private Integer bortleClass;

    /**
     * Sky Quality Meter value at this location (magnitudes per square arcsecond).
     *
     * <p>Higher values indicate darker skies. Populated alongside {@code bortleClass}
     * by {@code BortleEnrichmentService}. More granular than Bortle for fine-grained
     * aurora scoring.
     */
    @Column(name = "sky_brightness_sqm")
    private Double skyBrightnessSqm;

    /** Compass bearing of the outward shore-normal (0–360°), seaward perpendicular. */
    @Column(name = "shore_normal_bearing_degrees")
    private Double shoreNormalBearingDegrees;

    /** Open-water fetch distance for dominant storm winds (metres). */
    @Column(name = "effective_fetch_metres")
    private Double effectiveFetchMetres;

    /** Representative water depth over the fetch (metres). */
    @Column(name = "avg_shelf_depth_metres")
    private Double avgShelfDepthMetres;

    /** Whether this location has meaningful tidal surge exposure. */
    @Column(name = "is_coastal_tidal", nullable = false)
    @Builder.Default
    private boolean coastalTidal = false;

    /**
     * Returns whether this location supports the given target type based on its solar event preferences.
     *
     * <p>A location with null, empty, or {@code ALLDAY} solar event types supports all target types.
     * Otherwise, {@code SUNRISE} and {@code SUNSET} match their respective enum values, and
     * {@code HOURLY} is always supported.
     *
     * @param targetType the target type to check
     * @return true if this location supports the given target type
     */
    public boolean supportsTargetType(TargetType targetType) {
        if (solarEventType == null || solarEventType.isEmpty()
                || solarEventType.contains(SolarEventType.ALLDAY)) {
            return true;
        }
        return switch (targetType) {
            case SUNRISE -> solarEventType.contains(SolarEventType.SUNRISE);
            case SUNSET -> solarEventType.contains(SolarEventType.SUNSET);
            case HOURLY -> true;
        };
    }

    /**
     * Builds a {@link CoastalParameters} from this entity's coastal columns.
     *
     * @return coastal parameters, or {@link CoastalParameters#NON_TIDAL} for inland locations
     */
    public CoastalParameters toCoastalParameters() {
        if (!coastalTidal) {
            return CoastalParameters.NON_TIDAL;
        }
        return new CoastalParameters(
                shoreNormalBearingDegrees != null ? shoreNormalBearingDegrees : 0,
                effectiveFetchMetres != null ? effectiveFetchMetres : 0,
                avgShelfDepthMetres != null ? avgShelfDepthMetres : 1,
                true
        );
    }
}
