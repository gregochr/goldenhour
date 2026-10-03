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
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * JPA entity representing a location for which forecasts are evaluated.
 *
 * <p>Locations are managed exclusively via the REST API and persist in the database.
 * Disabled locations are excluded from forecast runs and the map view.
 *
 * <p>⚠️ <b>{@code enabled}, {@code consecutive_failures}, {@code last_failure_at} and
 * {@code disabled_reason} are {@code updatable = false}, and are written only by column-scoped
 * updates on {@code LocationRepository}</b> ({@code updateEnabled}, {@code recordFailure},
 * {@code autoDisable}, {@code resetFailureCounts}, {@code clearFailureState}), exactly as
 * {@code AppUserEntity}'s settings columns are. A {@code setX()} then {@code save()} on an existing
 * row writes NONE of them (an insert still does, so a new location starts with the value it was
 * built with). Without this, an entity loaded before a scheduled cycle's settle and saved after it
 * (an admin's metadata edit, or a job that loads locations outside a transaction, waits on a remote
 * call and merges its detached snapshot back) rewrote the stale values and silently undid a committed
 * auto-disable or restored a reset counter. Writers of other columns that run detached likewise use
 * scoped updates ({@code updateGridCell}, {@code updateSkyBrightness}) so a stale snapshot cannot
 * rewrite what an admin has edited meanwhile. The entity is also {@code @DynamicUpdate}, so a save
 * writes only the columns that changed. The admin enable/disable toggle is a deliberate
 * last-writer-wins on {@code enabled}.
 */
@Entity
@DynamicUpdate
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

    /** Whether this location is enabled for forecast runs. Not updatable through the entity. */
    @Column(nullable = false, updatable = false)
    @Builder.Default
    private boolean enabled = true;

    /** UTC timestamp when this location was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Number of consecutive forecast failures for this location. Used for auto-disabling. */
    @Column(name = "consecutive_failures", updatable = false)
    @Builder.Default
    private Integer consecutiveFailures = 0;

    /** UTC timestamp of the most recent forecast failure, or null if none. */
    @Column(name = "last_failure_at", updatable = false)
    private LocalDateTime lastFailureAt;

    /**
     * Reason this location was disabled, or null if enabled.
     * Examples: "Auto-disabled after 3 consecutive failures", or set by admin.
     */
    @Column(name = "disabled_reason", updatable = false)
    private String disabledReason;

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

    /** Elevation above sea level in metres, or null if unknown. */
    @Column(name = "elevation_m")
    private Integer elevationMetres;

    /** Whether this location overlooks water (lake, sea, reservoir) from elevation. */
    @Column(name = "overlooks_water", nullable = false)
    @Builder.Default
    private boolean overlooksWater = false;

    /** Snapped Open-Meteo grid latitude (~2 km resolution). Null until first fetch. */
    @Column(name = "grid_lat")
    private Double gridLat;

    /** Snapped Open-Meteo grid longitude (~2 km resolution). Null until first fetch. */
    @Column(name = "grid_lng")
    private Double gridLng;

    /**
     * Bluebell exposure type — WOODLAND or OPEN_FELL.
     *
     * <p>Only set for locations that have {@code BLUEBELL} in their location types.
     * Determines which weather conditions are considered ideal: WOODLAND prefers
     * soft diffused light and mist; OPEN_FELL prefers golden hour light and calm wind.</p>
     */
    @Column(name = "bluebell_exposure")
    @Enumerated(EnumType.STRING)
    private BluebellExposure bluebellExposure;

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
    /**
     * True when this location's only photographic subject sits under a canopy.
     *
     * <p>Distinct from merely carrying {@link LocationType#WOODLAND}: a wood that also has an open
     * aspect (Allen Banks) has a horizon to forecast, so it keeps the sky treatment. Only a site
     * whose <em>sole</em> subject is under canopy is woodland-only.
     *
     * <p>Lives here rather than in a service because it is a fact about the location, and two
     * callers now need the same answer — the verdict fork in {@code BriefingSlotBuilder} and the
     * event filter in {@code BriefingService}.
     *
     * @return true if WOODLAND is present and no open-sky colour type is
     */
    public boolean isWoodlandOnly() {
        if (locationType == null || !locationType.contains(LocationType.WOODLAND)) {
            return false;
        }
        return !locationType.contains(LocationType.LANDSCAPE)
                && !locationType.contains(LocationType.SEASCAPE)
                && !locationType.contains(LocationType.WATERFALL);
    }

    /**
     * True when WILDLIFE is this location's one and only type.
     *
     * <p>The rule behind the scheduled hourly comfort forecast ({@code WildlifeComfortRefreshJob}).
     * It is deliberately <em>narrower</em> than {@code ForecastCommandExecutor.isPureWildlife},
     * which admits any location carrying WILDLIFE and no colour type — and since
     * {@link #hasColourTypes()} omits WOODLAND and BLUEBELL on purpose, that older test also
     * admits WILDLIFE+WOODLAND and WILDLIFE+BLUEBELL. This one does not, for two reasons. The
     * frontend decides which popup draws the comfort table with
     * {@code types.every(t => t === 'WILDLIFE')}, so the writer and the only renderer agree on
     * which places carry the rows; and a WILDLIFE+WOODLAND place is briefed as a canopy site, a
     * sky-forecast surface in its own right, not a hide.
     *
     * <p>An empty or null type set is <em>not</em> wildlife-only: an unclassified place is
     * assumed to want colour (see {@link #hasColourTypes()}).
     *
     * @return true if the type set is non-empty and holds nothing but {@link LocationType#WILDLIFE}
     */
    public boolean isWildlifeOnly() {
        return locationType != null
                && !locationType.isEmpty()
                && locationType.stream().allMatch(t -> t == LocationType.WILDLIFE);
    }

    /**
     * Whether this location is photographed at the given kind of solar event.
     *
     * <p>A location with no solar event types configured, or one carrying {@code ALLDAY}, supports
     * every target type. Otherwise {@code SUNRISE} and {@code SUNSET} must each be listed, and
     * {@code HOURLY} (the wildlife/comfort forecast, not tied to one event) is always supported.
     *
     * @param targetType the event being asked about
     * @return true if the location should be evaluated for that event
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
     * Whether this location is photographed for sky colour — i.e. whether a colour forecast run
     * should evaluate it. True for LANDSCAPE, SEASCAPE and WATERFALL, and for a location with no
     * type set at all (an unclassified location is assumed to want colour rather than be skipped).
     *
     * <p>Lives here beside {@link #supportsTargetType} because it is a fact about the location,
     * not about any one caller. It previously existed as four copies — in
     * {@code ForecastCommandExecutor}, {@code ModelTestService}, {@code PromptTestService} and
     * inline in {@code ForceSubmitBatchService} — which had already drifted: only the inline copy
     * null-guarded the type set. That guard is kept here (it is unreachable while the field
     * defaults to an empty set, but the union of the previous behaviours is what every caller
     * keeps).
     *
     * @return true if this location is evaluated for sky colour
     */
    public boolean hasColourTypes() {
        if (locationType == null || locationType.isEmpty()) {
            return true;
        }
        // WOODLAND is deliberately ABSENT. This predicate is the shared gate for the SKY prompt —
        // ForecastCommandExecutor, ForceSubmitBatchService, ModelTestService and PromptTestService
        // all filter on it — and a location under a canopy has no sky to forecast. #347 added
        // WOODLAND here, which silently undid V132: those woods had been removed from the sky lane
        // by losing LANDSCAPE, and this put them straight back, so every engine would have sent
        // them to the fiery-sky prompt on clear mornings. That is the opposite of what #347's own
        // commit message claimed ("deliberately no rating and no model call").
        //
        // Woods reach the BRIEFING through a different predicate — BriefingService.isColourLocation
        // — which admits WOODLAND year-round on purpose. Keeping the two separate is the point:
        // "is this a candidate for the briefing" and "may this go to the sky prompt" are different
        // questions, and conflating them is what caused the leak.
        return locationType.contains(LocationType.LANDSCAPE)
                || locationType.contains(LocationType.SEASCAPE)
                || locationType.contains(LocationType.WATERFALL);
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

    /**
     * Returns true if this location's Open-Meteo grid cell coordinates are known.
     *
     * @return true if both {@code gridLat} and {@code gridLng} are non-null
     */
    public boolean hasGridCell() {
        return gridLat != null && gridLng != null;
    }

    /**
     * Canonical key for grouping locations by grid cell (4 dp avoids floating-point drift).
     *
     * @return grid cell key in the format "lat,lng" with 4 decimal places
     */
    public String gridCellKey() {
        return String.format("%.4f,%.4f", gridLat, gridLng);
    }

    /**
     * Returns a short identity string for logs and test failure messages.
     *
     * <p>Deliberately hand-written and limited to {@code id} and {@code name} rather than
     * generated by {@code @ToString}: this entity is referenced from four others, so a
     * generated version would drag {@link RegionEntity} and three eager collections into
     * every line, and any association added later would silently become a fetch (or a
     * {@code LazyInitializationException}) from a log statement.
     *
     * @return this location as {@code LocationEntity(id=…, name=…)}
     */
    @Override
    public String toString() {
        return "LocationEntity(id=" + id + ", name=" + name + ")";
    }
}
