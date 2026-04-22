package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.model.LocationEvaluationView.Source;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical merge layer that combines scored results from {@code cached_evaluation}
 * with triage/scored rows from {@code forecast_evaluation}.
 *
 * <p>Precedence: cached evaluation (batch/SSE) &gt; scored forecast row &gt; triage row &gt; none.
 * Both the Plan tab and Map tab read through this service so there is a single source of truth.
 */
@Service
public class EvaluationViewService {

    private static final Logger LOG = LoggerFactory.getLogger(EvaluationViewService.class);

    private static final TypeReference<List<BriefingEvaluationResult>> RESULT_LIST_TYPE =
            new TypeReference<>() { };

    private final BriefingEvaluationService briefingEvaluationService;
    private final CachedEvaluationRepository cachedEvaluationRepository;
    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final LocationService locationService;
    private final ObjectMapper objectMapper;

    /**
     * Constructs an {@code EvaluationViewService}.
     *
     * @param briefingEvaluationService in-memory cache of batch/SSE evaluation results
     * @param cachedEvaluationRepository repository for durable cached evaluations
     * @param forecastEvaluationRepository repository for forecast evaluation rows
     * @param locationService service for retrieving location entities
     * @param objectMapper Jackson mapper for JSON deserialisation
     */
    public EvaluationViewService(BriefingEvaluationService briefingEvaluationService,
            CachedEvaluationRepository cachedEvaluationRepository,
            ForecastEvaluationRepository forecastEvaluationRepository,
            LocationService locationService,
            ObjectMapper objectMapper) {
        this.briefingEvaluationService = briefingEvaluationService;
        this.cachedEvaluationRepository = cachedEvaluationRepository;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.locationService = locationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the merged evaluation view for all locations in a region.
     *
     * @param regionId   the region primary key
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return one view per location in the region
     */
    public List<LocationEvaluationView> forRegion(Long regionId, LocalDate date,
            TargetType targetType) {
        List<LocationEntity> regionLocations = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getRegion() != null && loc.getRegion().getId().equals(regionId))
                .toList();

        if (regionLocations.isEmpty()) {
            return List.of();
        }

        String regionName = regionLocations.getFirst().getRegion().getName();
        Map<String, BriefingEvaluationResult> cached =
                briefingEvaluationService.getCachedScores(regionName, date, targetType);

        List<LocationEvaluationView> views = new ArrayList<>();
        for (LocationEntity loc : regionLocations) {
            views.add(buildView(loc, date, targetType, cached.get(loc.getName())));
        }
        return views;
    }

    /**
     * Returns the merged evaluation view for a single location.
     *
     * @param locationId the location primary key
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return the merged view
     */
    public LocationEvaluationView forLocation(Long locationId, LocalDate date,
            TargetType targetType) {
        LocationEntity loc = locationService.findAllEnabled().stream()
                .filter(l -> l.getId().equals(locationId))
                .findFirst()
                .orElse(null);
        if (loc == null) {
            return emptyView(locationId, null, null, null, date, targetType);
        }

        BriefingEvaluationResult cachedResult = null;
        if (loc.getRegion() != null) {
            Map<String, BriefingEvaluationResult> cached =
                    briefingEvaluationService.getCachedScores(
                            loc.getRegion().getName(), date, targetType);
            cachedResult = cached.get(loc.getName());
        }
        return buildView(loc, date, targetType, cachedResult);
    }

    /**
     * Returns merged evaluation views for all enabled locations across a date range.
     *
     * <p>Used by the Map tab to load all scores in a single call. Returns a flat list
     * covering every (location, date, targetType) combination that has any data.
     *
     * @param start the start date (inclusive)
     * @param end   the end date (inclusive)
     * @param types the target types to include
     * @return all views with data, ordered by date then location
     */
    public List<LocationEvaluationView> forDateRange(LocalDate start, LocalDate end,
            Set<TargetType> types) {
        // 1. Load all cached evaluation entries from DB for the date range
        Map<String, Map<String, BriefingEvaluationResult>> cachedByKey =
                loadCachedEvaluations(start, end);

        // 2. Load all forecast_evaluation rows for enabled locations in date range
        List<LocationEntity> locations = locationService.findAllEnabled();
        Map<String, ForecastEvaluationEntity> latestForecasts = new HashMap<>();
        for (LocationEntity loc : locations) {
            List<ForecastEvaluationEntity> rows =
                    forecastEvaluationRepository
                            .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                                    loc.getId(), start, end);
            for (ForecastEvaluationEntity row : rows) {
                if (!types.contains(row.getTargetType())) {
                    continue;
                }
                String key = loc.getId() + "|" + row.getTargetDate() + "|" + row.getTargetType();
                ForecastEvaluationEntity existing = latestForecasts.get(key);
                if (existing == null
                        || row.getForecastRunAt().isAfter(existing.getForecastRunAt())) {
                    latestForecasts.put(key, row);
                }
            }
        }

        // 3. Merge: iterate all locations × dates × types
        List<LocationEvaluationView> views = new ArrayList<>();
        for (LocationEntity loc : locations) {
            String regionName = loc.getRegion() != null ? loc.getRegion().getName() : null;
            Long regionId = loc.getRegion() != null ? loc.getRegion().getId() : null;

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                for (TargetType type : types) {
                    // Check cached evaluation
                    BriefingEvaluationResult cachedResult = null;
                    if (regionName != null) {
                        String cacheKey = regionName + "|" + date + "|" + type;
                        Map<String, BriefingEvaluationResult> regionResults =
                                cachedByKey.get(cacheKey);
                        if (regionResults != null) {
                            cachedResult = regionResults.get(loc.getName());
                        }
                    }

                    // Check forecast_evaluation
                    String forecastKey = loc.getId() + "|" + date + "|" + type;
                    ForecastEvaluationEntity forecastRow = latestForecasts.get(forecastKey);

                    // Apply merge rule
                    LocationEvaluationView view = mergeToView(
                            loc.getId(), loc.getName(), regionId, regionName,
                            date, type, cachedResult, forecastRow);

                    if (view.source() != Source.NONE) {
                        views.add(view);
                    }
                }
            }
        }
        return views;
    }

    /**
     * Returns merged evaluation results for a region, keyed by location name.
     *
     * <p>Convenience method for Plan tab enrichment — returns the same shape as
     * {@link BriefingEvaluationService#getCachedScores} but with fallback to
     * {@code forecast_evaluation} rows when the cache has no entry for a location.
     *
     * @param regionName the region name
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return map of locationName to evaluation result
     */
    public Map<String, BriefingEvaluationResult> getScoresForEnrichment(
            String regionName, LocalDate date, TargetType targetType) {
        // Start with cached scores (covers batch + SSE results)
        Map<String, BriefingEvaluationResult> result =
                new HashMap<>(briefingEvaluationService.getCachedScores(regionName, date, targetType));

        // Supplement with forecast_evaluation for locations not in cache
        List<LocationEntity> regionLocations = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getRegion() != null
                        && loc.getRegion().getName().equals(regionName))
                .toList();

        for (LocationEntity loc : regionLocations) {
            if (result.containsKey(loc.getName())) {
                continue;
            }
            forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            loc.getId(), date, targetType)
                    .ifPresent(row -> {
                        if (row.getRating() != null) {
                            result.put(loc.getName(), new BriefingEvaluationResult(
                                    loc.getName(), row.getRating(),
                                    row.getFierySkyPotential(), row.getGoldenHourPotential(),
                                    row.getSummary()));
                        } else if (row.getTriageReason() != null) {
                            result.put(loc.getName(), new BriefingEvaluationResult(
                                    loc.getName(), null, null, null, null,
                                    row.getTriageReason(), row.getTriageMessage()));
                        }
                    });
        }

        return result;
    }

    /**
     * Builds a view for a single location, applying the merge precedence rule.
     */
    private LocationEvaluationView buildView(LocationEntity loc, LocalDate date,
            TargetType targetType, BriefingEvaluationResult cachedResult) {
        // Check forecast_evaluation as fallback
        ForecastEvaluationEntity forecastRow = forecastEvaluationRepository
                .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                        loc.getId(), date, targetType)
                .orElse(null);

        Long regionId = loc.getRegion() != null ? loc.getRegion().getId() : null;
        String regionName = loc.getRegion() != null ? loc.getRegion().getName() : null;
        return mergeToView(loc.getId(), loc.getName(), regionId, regionName, date, targetType,
                cachedResult, forecastRow);
    }

    /**
     * Applies the merge precedence rule to produce a single view.
     *
     * <ol>
     *   <li>Cached evaluation with a rating → CACHED_EVALUATION</li>
     *   <li>Cached evaluation with triage → CACHED_EVALUATION (triaged)</li>
     *   <li>Scored forecast_evaluation row → FORECAST_EVALUATION_SCORED</li>
     *   <li>Triaged forecast_evaluation row → FORECAST_EVALUATION_TRIAGE</li>
     *   <li>Nothing → NONE</li>
     * </ol>
     */
    private LocationEvaluationView mergeToView(Long locationId, String locationName,
            Long regionId, String regionName, LocalDate date, TargetType targetType,
            BriefingEvaluationResult cachedResult, ForecastEvaluationEntity forecastRow) {

        // 1. Cached evaluation wins (both scored and triaged entries)
        if (cachedResult != null) {
            return new LocationEvaluationView(
                    locationId, locationName, regionId, regionName, date, targetType,
                    Source.CACHED_EVALUATION,
                    cachedResult.rating(), cachedResult.summary(),
                    cachedResult.fierySkyPotential(), cachedResult.goldenHourPotential(),
                    cachedResult.triageReason(), cachedResult.triageMessage(),
                    null, null);
        }

        // 2. Scored forecast_evaluation row
        if (forecastRow != null && forecastRow.getRating() != null) {
            return new LocationEvaluationView(
                    locationId, locationName, regionId, regionName, date, targetType,
                    Source.FORECAST_EVALUATION_SCORED,
                    forecastRow.getRating(), forecastRow.getSummary(),
                    forecastRow.getFierySkyPotential(), forecastRow.getGoldenHourPotential(),
                    null, null,
                    forecastRow.getEvaluationModel() != null
                            ? forecastRow.getEvaluationModel().name() : null,
                    forecastRow.getForecastRunAt() != null
                            ? forecastRow.getForecastRunAt().atZone(
                                    java.time.ZoneId.of("Europe/London")).toInstant()
                            : null);
        }

        // 3. Triaged forecast_evaluation row
        if (forecastRow != null && forecastRow.getTriageReason() != null) {
            return new LocationEvaluationView(
                    locationId, locationName, regionId, regionName, date, targetType,
                    Source.FORECAST_EVALUATION_TRIAGE,
                    null, null, null, null,
                    forecastRow.getTriageReason(), forecastRow.getTriageMessage(),
                    null,
                    forecastRow.getForecastRunAt() != null
                            ? forecastRow.getForecastRunAt().atZone(
                                    java.time.ZoneId.of("Europe/London")).toInstant()
                            : null);
        }

        // 4. Nothing
        return emptyView(locationId, locationName, regionId, regionName, date, targetType);
    }

    private LocationEvaluationView emptyView(Long locationId, String locationName,
            Long regionId, String regionName, LocalDate date, TargetType targetType) {
        return new LocationEvaluationView(
                locationId, locationName, regionId, regionName, date, targetType,
                Source.NONE, null, null, null, null, null, null, null, null);
    }

    /**
     * Loads all cached evaluation entries for the date range from the in-memory cache first,
     * falling back to the database. Returns a map keyed by "regionName|date|targetType"
     * to a map of locationName → result.
     */
    private Map<String, Map<String, BriefingEvaluationResult>> loadCachedEvaluations(
            LocalDate start, LocalDate end) {
        Map<String, Map<String, BriefingEvaluationResult>> result = new HashMap<>();

        // Try in-memory cache first (it's the primary read source)
        List<LocationEntity> locations = locationService.findAllEnabled();
        Set<String> regionNames = locations.stream()
                .filter(l -> l.getRegion() != null)
                .map(l -> l.getRegion().getName())
                .collect(java.util.stream.Collectors.toSet());

        for (String regionName : regionNames) {
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                for (TargetType type : TargetType.values()) {
                    if (type == TargetType.HOURLY) {
                        continue;
                    }
                    Map<String, BriefingEvaluationResult> cached =
                            briefingEvaluationService.getCachedScores(regionName, date, type);
                    if (!cached.isEmpty()) {
                        result.put(regionName + "|" + date + "|" + type, cached);
                    }
                }
            }
        }

        // Also check DB for anything not in the in-memory cache
        List<CachedEvaluationEntity> dbEntries =
                cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(start);
        for (CachedEvaluationEntity entity : dbEntries) {
            if (entity.getEvaluationDate().isAfter(end)) {
                continue;
            }
            String key = entity.getCacheKey();
            if (result.containsKey(key)) {
                continue; // in-memory cache takes precedence
            }
            try {
                List<BriefingEvaluationResult> results = objectMapper.readValue(
                        entity.getResultsJson(), RESULT_LIST_TYPE);
                Map<String, BriefingEvaluationResult> map = new HashMap<>();
                results.forEach(r -> map.put(r.locationName(), r));
                result.put(key, map);
            } catch (Exception e) {
                LOG.warn("Failed to parse cached evaluation {}: {}",
                        key, e.getMessage());
            }
        }

        return result;
    }
}
