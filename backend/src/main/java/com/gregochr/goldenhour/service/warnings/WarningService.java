package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.config.WarningsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.model.SimulateWarningRequest;
import com.gregochr.goldenhour.model.WarningDto;
import com.gregochr.goldenhour.model.WarningsAdminStatus;
import com.gregochr.goldenhour.model.WarningsResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import com.gregochr.goldenhour.service.ServedBriefingEpoch;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles what the warnings endpoints serve and inserts admin-simulated warnings.
 *
 * <p>The shared view ({@link #served()}) never contains a simulated row, for anyone, and only warnings that cover at
 * least one enabled location (Q3 scope: the rest are stored but not surfaced). The admin view
 * ({@link #adminStatus()}) includes simulated rows. "Now" is the injected {@link Clock}, which is the rewound
 * instant for a rewound admin GET.
 */
@Service
public class WarningService {

    /** Half the side, in degrees, of the square a simulated warning covers around its centre. */
    static final double SIMULATED_HALF_SIDE_DEGREES = 0.25;

    /** The default and bounds for a simulated warning's validity, in hours. */
    static final int DEFAULT_SIMULATED_HOURS = 6;
    static final int MAX_SIMULATED_HOURS = 72;

    private static final BigDecimal INITIAL_VERSION = new BigDecimal("1.00");
    private static final String DEFAULT_LEVEL = "AMBER";
    private static final String DEFAULT_TYPE = "WIND";
    private static final String DEFAULT_HEADLINE = "Simulated warning (test only)";
    private static final String SIMULATED_STATUS = "SIMULATED";
    private static final int SIMULATED_ID_LENGTH = 8;
    private static final double MAX_LATITUDE = 90.0;
    private static final double MAX_LONGITUDE = 180.0;

    private final WeatherWarningVersionRepository repository;
    private final WarningTimeline timeline;
    private final WarningCoverage coverage;
    private final LocationRepository locations;
    private final WarningPollStatus status;
    private final WarningsProperties properties;
    private final ServedBriefingEpoch epoch;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param repository the warning store
     * @param timeline   the active-at-T rule
     * @param coverage   the point-in-polygon matcher
     * @param locations  the location store
     * @param status     the in-memory poll record
     * @param properties warnings configuration
     * @param epoch      the serve epoch, bumped by a simulation insert
     * @param clock      the injected clock (rewound for a rewound admin GET)
     */
    public WarningService(WeatherWarningVersionRepository repository, WarningTimeline timeline,
            WarningCoverage coverage, LocationRepository locations, WarningPollStatus status,
            WarningsProperties properties, ServedBriefingEpoch epoch, Clock clock) {
        this.repository = repository;
        this.timeline = timeline;
        this.coverage = coverage;
        this.locations = locations;
        this.status = status;
        this.properties = properties;
        this.epoch = epoch;
        this.clock = clock;
    }

    /**
     * The shared warnings view: no simulated rows, only warnings covering an enabled location.
     *
     * @return the response; an empty, stale, not-enabled one when the feature is off
     */
    public WarningsResponse served() {
        if (!properties.isEnabled()) {
            return new WarningsResponse(false, null, true, List.of(), properties.getPublicUrl(),
                    properties.getAttribution());
        }
        List<WarningDto> warnings = new ArrayList<>();
        for (WarningDto dto : activeDtos(false)) {
            if (!dto.locationIds().isEmpty()) {
                warnings.add(dto);
            }
        }
        Instant lastSuccess = status.lastSuccessAt();
        return new WarningsResponse(true, lastSuccess, isStale(lastSuccess), warnings, properties.getPublicUrl(),
                properties.getAttribution());
    }

    /**
     * The Operations view, simulated rows included.
     *
     * @return the status
     */
    public WarningsAdminStatus adminStatus() {
        Instant lastSuccess = status.lastSuccessAt();
        return new WarningsAdminStatus(
                properties.isEnabled(),
                properties.getApiKey() != null && !properties.getApiKey().isBlank(),
                properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank(),
                status.lastAttempt(),
                lastSuccess,
                isStale(lastSuccess),
                repository.count(),
                repository.countBySimulated(true),
                status.unknownTypeCounts(),
                activeDtos(true));
    }

    /**
     * Inserts one clearly simulated warning (a square around the given point).
     *
     * @param request the simulation request
     * @return the stored warning as the admin surface shows it
     * @throws IllegalArgumentException for an unknown level, an out-of-range coordinate or duration
     */
    public WarningDto simulate(SimulateWarningRequest request) {
        String level = normaliseLevel(request.level());
        if (Math.abs(request.latitude()) > MAX_LATITUDE || Math.abs(request.longitude()) > MAX_LONGITUDE) {
            throw new IllegalArgumentException("latitude or longitude out of range");
        }
        int hours = request.durationHours() == null ? DEFAULT_SIMULATED_HOURS : request.durationHours();
        if (hours < 1 || hours > MAX_SIMULATED_HOURS) {
            throw new IllegalArgumentException("durationHours must be between 1 and " + MAX_SIMULATED_HOURS);
        }
        Instant now = clock.instant();
        WeatherWarningVersionEntity row = new WeatherWarningVersionEntity();
        row.setWarningId("SIM-" + UUID.randomUUID().toString().substring(0, SIMULATED_ID_LENGTH));
        row.setWarningVersion(INITIAL_VERSION);
        row.setStatus(WeatherWarningVersionEntity.STATUS_ACTIVE);
        row.setSourceStatus(SIMULATED_STATUS);
        row.setWarningLevel(level);
        List<String> types = request.weatherTypes() == null || request.weatherTypes().isEmpty()
                ? List.of(DEFAULT_TYPE) : request.weatherTypes();
        row.setWeatherTypes(WarningGeometry.toJson(types));
        row.setHeadline(request.headline() == null || request.headline().isBlank()
                ? DEFAULT_HEADLINE : request.headline());
        row.setValidFrom(now);
        row.setValidTo(now.plus(Duration.ofHours(hours)));
        row.setIssuedAt(now);
        row.setModifiedAt(now);
        String geometry = WarningGeometry.square(request.longitude(), request.latitude(),
                SIMULATED_HALF_SIDE_DEGREES);
        row.setGeometry(geometry);
        row.setSimplifiedGeometry(geometry);
        row.setSimulated(true);
        row.setReceivedAt(now);
        WeatherWarningVersionEntity saved = repository.save(row);
        epoch.bump();
        return toDto(new WarningTimeline.ActiveWarning(saved, null, null),
                coverage.locationsByWarning(List.of(saved), enabledLocations()).get(saved.getWarningId()));
    }

    private List<WarningDto> activeDtos(boolean includeSimulated) {
        Instant now = clock.instant();
        List<WarningTimeline.ActiveWarning> active =
                timeline.active(repository.findAllVersionsOfWarningsValidAfter(now), includeSimulated);
        List<WeatherWarningVersionEntity> versions = new ArrayList<>();
        for (WarningTimeline.ActiveWarning a : active) {
            versions.add(a.version());
        }
        Map<String, Set<Long>> covered = coverage.locationsByWarning(versions, enabledLocations());
        List<WarningDto> out = new ArrayList<>();
        for (WarningTimeline.ActiveWarning a : active) {
            out.add(toDto(a, covered.get(a.version().getWarningId())));
        }
        return out;
    }

    private List<LocationEntity> enabledLocations() {
        return locations.findAllByEnabledTrueOrderByNameAsc();
    }

    private boolean isStale(Instant lastSuccess) {
        return lastSuccess == null
                || lastSuccess.plus(Duration.ofMinutes(properties.getPollStaleMinutes())).isBefore(clock.instant());
    }

    private static String normaliseLevel(String level) {
        if (level == null || level.isBlank()) {
            return DEFAULT_LEVEL;
        }
        return switch (level.trim().toLowerCase(Locale.ROOT)) {
            case "yellow" -> "YELLOW";
            case "amber" -> "AMBER";
            case "red" -> "RED";
            default -> throw new IllegalArgumentException("level must be Yellow, Amber or Red");
        };
    }

    private static WarningDto toDto(WarningTimeline.ActiveWarning active, Set<Long> locationIds) {
        WeatherWarningVersionEntity v = active.version();
        return new WarningDto(v.getWarningId(), formatVersion(v.getWarningVersion()), v.getWarningLevel(),
                WarningFeedParser.stringList(v.getWeatherTypes()), v.getLikelihood(), v.getImpact(),
                v.getValidFrom(), v.getValidTo(), v.getIssuedAt(), v.getModifiedAt(), v.getHeadline(),
                WarningFeedParser.stringList(v.getWhatToExpect()), v.getFurtherDetails(), v.getUpdateDescription(),
                v.getWhatShouldIDo(), active.updated(), WarningFeedParser.stringList(v.getAffectedAreas()),
                WarningGeometry.toMap(v.getSimplifiedGeometry()), active.upgradedFrom(), active.upgradedAt(),
                locationIds, v.isSimulated());
    }

    /** The version as the feed writes it: "3.0", "10.0", or "2.25" when a second decimal is significant. */
    static String formatVersion(BigDecimal version) {
        if (version == null) {
            return null;
        }
        BigDecimal stripped = version.stripTrailingZeros();
        return (stripped.scale() < 1 ? stripped.setScale(1) : stripped).toPlainString();
    }
}
