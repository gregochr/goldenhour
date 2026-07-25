package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.model.LocationEvaluationView.Source;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
        List<LocationEntity> locations = locationService.findAllEnabled();
        Map<String, CachedEntry> cachedByKey = loadCachedEvaluations(start, end, locations);
        Map<String, ForecastEvaluationEntity> latestForecasts =
                loadLatestForecasts(locations, start, end, types);
        return buildViews(cachedByKey, latestForecasts, locations, start, end, types);
    }

    /**
     * Returns only the cached-only views for a date range, skipping the per-location
     * {@code forecast_evaluation} load and merge that {@link #forDateRange} performs.
     *
     * <p>{@code GET /api/forecast} already loads its own latest {@code forecast_evaluation} rows and
     * only needs the cached-only (not-yet-persisted) rows from here, so re-querying
     * {@code forecast_evaluation} once per location would be wasted work — an N+1 on the map's
     * primary endpoint. Because {@link #mergeToView} gives cached results priority, passing an empty
     * forecast map yields exactly the {@code CACHED_EVALUATION} views {@code forDateRange} would
     * produce (and {@code NONE} for the rest, which are dropped). The caller passes its already-loaded
     * enabled locations so this adds no extra {@code findAllEnabled} query.
     *
     * @param start     the start date (inclusive)
     * @param end       the end date (inclusive)
     * @param types     the target types to include
     * @param locations the enabled locations, supplied by the caller to avoid a repeat query
     * @return the cached-only views, ordered by date then location
     */
    public List<LocationEvaluationView> cachedOnlyViewsForDateRange(LocalDate start, LocalDate end,
            Set<TargetType> types, List<LocationEntity> locations) {
        Map<String, CachedEntry> cachedByKey = loadCachedEvaluations(start, end, locations);
        return buildViews(cachedByKey, Map.of(), locations, start, end, types);
    }

    /**
     * Loads the latest {@code forecast_evaluation} row per (location, date, target type) for the
     * range, keyed by {@code locationId|date|targetType}.
     *
     * <p>Uses the dedup-at-source bulk query (one statement for every location, backed by
     * {@code idx_forecast_eval_latest_run}) rather than a range query per location. The former shape
     * issued ~one round trip per enabled location and pulled <em>every</em> historical run over the
     * window — this table is insert-only — only to discard all but the latest per slot.
     *
     * <p>Two behaviours of the bulk query are load-bearing and must not be simplified away: it does
     * <em>not</em> filter target type (so the {@code types} guard stays), and its
     * {@code forecastRunAt = (SELECT MAX(...))} predicate returns <em>both</em> rows on an exact
     * timestamp tie (so the strict-{@code isAfter} reduction below stays, keeping the first row seen
     * exactly as before — a {@code Collectors.toMap} would throw on the duplicate key).
     */
    private Map<String, ForecastEvaluationEntity> loadLatestForecasts(List<LocationEntity> locations,
            LocalDate start, LocalDate end, Set<TargetType> types) {
        List<Long> locationIds = locations.stream()
                .map(LocationEntity::getId)
                .toList();
        if (locationIds.isEmpty()) {
            // An empty IN list is invalid on some dialects — mirror the guard in ForecastController.
            return Map.of();
        }
        Map<String, ForecastEvaluationEntity> latestForecasts = new HashMap<>();
        for (ForecastEvaluationEntity row : forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(locationIds, start, end)) {
            if (!types.contains(row.getTargetType())) {
                continue;
            }
            String key = row.getLocation().getId() + "|" + row.getTargetDate()
                    + "|" + row.getTargetType();
            ForecastEvaluationEntity existing = latestForecasts.get(key);
            if (existing == null
                    || row.getForecastRunAt().isAfter(existing.getForecastRunAt())) {
                latestForecasts.put(key, row);
            }
        }
        return latestForecasts;
    }

    /**
     * Merges cached evaluations and (optionally) latest forecast rows into views for every
     * location × date × target type, dropping {@code NONE} results. Shared by {@link #forDateRange}
     * (with forecast rows) and {@link #cachedOnlyViewsForDateRange} (with an empty forecast map).
     */
    private List<LocationEvaluationView> buildViews(Map<String, CachedEntry> cachedByKey,
            Map<String, ForecastEvaluationEntity> latestForecasts, List<LocationEntity> locations,
            LocalDate start, LocalDate end, Set<TargetType> types) {
        List<LocationEvaluationView> views = new ArrayList<>();
        for (LocationEntity loc : locations) {
            String regionName = loc.getRegion() != null ? loc.getRegion().getName() : null;
            Long regionId = loc.getRegion() != null ? loc.getRegion().getId() : null;

            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                for (TargetType type : types) {
                    // Check cached evaluation
                    BriefingEvaluationResult cachedResult = null;
                    Instant cachedEvaluatedAt = null;
                    if (regionName != null) {
                        String cacheKey = regionName + "|" + date + "|" + type;
                        CachedEntry regionEntry = cachedByKey.get(cacheKey);
                        if (regionEntry != null) {
                            cachedResult = regionEntry.results().get(loc.getName());
                            cachedEvaluatedAt = regionEntry.evaluatedAt();
                        }
                    }

                    // Check forecast_evaluation
                    String forecastKey = loc.getId() + "|" + date + "|" + type;
                    ForecastEvaluationEntity forecastRow = latestForecasts.get(forecastKey);

                    // Apply merge rule
                    LocationEvaluationView view = mergeToView(
                            loc.getId(), loc.getName(), regionId, regionName,
                            date, type, cachedResult, cachedEvaluatedAt, forecastRow);

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
                        } else if (row.getTriage() != null
                                && row.getTriage().getReason() != null) {
                            result.put(loc.getName(), new BriefingEvaluationResult(
                                    loc.getName(), null, null, null, null,
                                    row.getTriage().getReason(), row.getTriage().getMessage()));
                        }
                    });
        }

        return result;
    }

    /**
     * Bulk equivalent of {@link #getScoresForEnrichment} across a whole date range, keyed by
     * {@code "regionName|date|targetType"}.
     *
     * <p>Same precedence — in-memory cached scores (which carry the Claude {@code headline}) win,
     * with a {@code forecast_evaluation} fallback for locations the cache does not cover. The
     * difference is query shape: the fallback issues a <em>single</em> dedup-at-source query for
     * every region-assigned location, instead of one {@code findTop} per (location, date,
     * targetType) or one range query per location. Serving a briefing re-enriches the full plan
     * window in a single pass, so this collapses what was O(locations × dates × targets) point
     * lookups — and then O(locations) range scans — into one indexed statement.
     *
     * @param start the start date (inclusive)
     * @param end   the end date (inclusive)
     * @param types the target types to include
     * @return map of {@code "regionName|date|targetType"} to a map of locationName → result
     */
    public Map<String, Map<String, BriefingEvaluationResult>> getScoresForEnrichmentBulk(
            LocalDate start, LocalDate end, Set<TargetType> types) {
        Map<String, Map<String, BriefingEvaluationResult>> byKey = new HashMap<>();
        List<LocationEntity> locations = locationService.findAllEnabled();

        // 1. In-memory cached scores first — no DB, and they carry the Claude headline.
        Set<String> regionNames = locations.stream()
                .filter(loc -> loc.getRegion() != null)
                .map(loc -> loc.getRegion().getName())
                .collect(java.util.stream.Collectors.toSet());
        for (String regionName : regionNames) {
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                for (TargetType type : types) {
                    Map<String, BriefingEvaluationResult> cached =
                            briefingEvaluationService.getCachedScores(regionName, date, type);
                    if (!cached.isEmpty()) {
                        byKey.put(regionName + "|" + date + "|" + type, new HashMap<>(cached));
                    }
                }
            }
        }

        // 2. forecast_evaluation fallback — ONE bulk query for every region-assigned location,
        //    reduced in memory to the latest row per (location, date, type). Previously this issued
        //    a range query per location, each pulling every historical run over the window from an
        //    insert-only table just to keep the newest per slot. The type guard and the strict
        //    isAfter reduction are retained: the bulk query doesn't filter target type, and its
        //    MAX(forecastRunAt) predicate returns both rows on an exact tie.
        List<Long> regionLocationIds = locations.stream()
                .filter(loc -> loc.getRegion() != null)
                .map(LocationEntity::getId)
                .toList();
        Map<Long, Map<String, ForecastEvaluationEntity>> latestByLocationId = new HashMap<>();
        if (!regionLocationIds.isEmpty()) {
            for (ForecastEvaluationEntity row : forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(regionLocationIds, start, end)) {
                if (!types.contains(row.getTargetType())) {
                    continue;
                }
                Map<String, ForecastEvaluationEntity> latest = latestByLocationId
                        .computeIfAbsent(row.getLocation().getId(), k -> new HashMap<>());
                String fk = row.getTargetDate() + "|" + row.getTargetType();
                ForecastEvaluationEntity existing = latest.get(fk);
                if (existing == null
                        || row.getForecastRunAt().isAfter(existing.getForecastRunAt())) {
                    latest.put(fk, row);
                }
            }
        }

        // Iterate the locations (not the query result) so the cached-score-wins precedence below
        // still resolves in the original, stable location order.
        for (LocationEntity loc : locations) {
            if (loc.getRegion() == null) {
                continue;
            }
            String regionName = loc.getRegion().getName();
            Map<String, ForecastEvaluationEntity> latest =
                    latestByLocationId.getOrDefault(loc.getId(), Map.of());
            for (ForecastEvaluationEntity row : latest.values()) {
                Map<String, BriefingEvaluationResult> regionMap = byKey.computeIfAbsent(
                        regionName + "|" + row.getTargetDate() + "|" + row.getTargetType(),
                        k -> new HashMap<>());
                if (regionMap.containsKey(loc.getName())) {
                    continue; // cached score wins
                }
                if (row.getRating() != null) {
                    regionMap.put(loc.getName(), new BriefingEvaluationResult(
                            loc.getName(), row.getRating(), row.getFierySkyPotential(),
                            row.getGoldenHourPotential(), row.getSummary()));
                } else if (row.getTriage() != null && row.getTriage().getReason() != null) {
                    regionMap.put(loc.getName(), new BriefingEvaluationResult(
                            loc.getName(), null, null, null, null,
                            row.getTriage().getReason(), row.getTriage().getMessage()));
                }
            }
        }

        return byKey;
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
        Instant cachedEvaluatedAt = (regionName != null && cachedResult != null)
                ? briefingEvaluationService.getCachedEvaluatedAt(regionName, date, targetType)
                        .orElse(null)
                : null;
        return mergeToView(loc.getId(), loc.getName(), regionId, regionName, date, targetType,
                cachedResult, cachedEvaluatedAt, forecastRow);
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
            BriefingEvaluationResult cachedResult, Instant cachedEvaluatedAt,
            ForecastEvaluationEntity forecastRow) {

        // 1. Cached evaluation wins (both scored and triaged entries)
        if (cachedResult != null) {
            DisplayVerdict displayVerdict = DisplayVerdict.resolve(
                    cachedResult.rating(),
                    cachedResult.triageReason() != null ? Verdict.STANDDOWN : null);
            return new LocationEvaluationView(
                    locationId, locationName, regionId, regionName, date, targetType,
                    Source.CACHED_EVALUATION,
                    cachedResult.rating(), cachedResult.summary(),
                    cachedResult.fierySkyPotential(), cachedResult.goldenHourPotential(),
                    cachedResult.triageReason(), cachedResult.triageMessage(),
                    null, cachedEvaluatedAt,
                    displayVerdict);
        }

        // 2. Scored forecast_evaluation row
        if (forecastRow != null && forecastRow.getRating() != null) {
            DisplayVerdict displayVerdict = DisplayVerdict.resolve(forecastRow.getRating(), null);
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
                            : null,
                    displayVerdict);
        }

        // 3. Triaged forecast_evaluation row
        if (forecastRow != null && forecastRow.getTriage() != null
                && forecastRow.getTriage().getReason() != null) {
            DisplayVerdict displayVerdict = DisplayVerdict.resolve(null, Verdict.STANDDOWN);
            return new LocationEvaluationView(
                    locationId, locationName, regionId, regionName, date, targetType,
                    Source.FORECAST_EVALUATION_TRIAGE,
                    null, null, null, null,
                    forecastRow.getTriage().getReason(), forecastRow.getTriage().getMessage(),
                    null,
                    forecastRow.getForecastRunAt() != null
                            ? forecastRow.getForecastRunAt().atZone(
                                    java.time.ZoneId.of("Europe/London")).toInstant()
                            : null,
                    displayVerdict);
        }

        // 4. Nothing
        return emptyView(locationId, locationName, regionId, regionName, date, targetType);
    }

    private LocationEvaluationView emptyView(Long locationId, String locationName,
            Long regionId, String regionName, LocalDate date, TargetType targetType) {
        return new LocationEvaluationView(
                locationId, locationName, regionId, regionName, date, targetType,
                Source.NONE, null, null, null, null, null, null, null, null,
                DisplayVerdict.AWAITING);
    }

    /**
     * A cached evaluation entry for one "regionName|date|targetType" key: the per-location results
     * plus the instant the evaluation was produced (nullable when unknown).
     *
     * @param results     locationName → evaluation result
     * @param evaluatedAt when the cache entry was produced, or {@code null} if unknown
     */
    private record CachedEntry(Map<String, BriefingEvaluationResult> results, Instant evaluatedAt) {
    }

    /**
     * Loads all cached evaluation entries for the date range from the in-memory cache first,
     * falling back to the database. Returns a map keyed by "regionName|date|targetType"
     * to a {@link CachedEntry} carrying both the per-location results and the evaluation instant.
     */
    private Map<String, CachedEntry> loadCachedEvaluations(LocalDate start, LocalDate end,
            List<LocationEntity> locations) {
        Map<String, CachedEntry> result = new HashMap<>();

        // Try in-memory cache first (it's the primary read source)
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
                        Instant evaluatedAt = briefingEvaluationService
                                .getCachedEvaluatedAt(regionName, date, type).orElse(null);
                        result.put(regionName + "|" + date + "|" + type,
                                new CachedEntry(cached, evaluatedAt));
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
                result.put(key, new CachedEntry(map, entity.getEvaluatedAt()));
            } catch (Exception e) {
                LOG.warn("Failed to parse cached evaluation {}: {}",
                        key, e.getMessage());
            }
        }

        return result;
    }
}
