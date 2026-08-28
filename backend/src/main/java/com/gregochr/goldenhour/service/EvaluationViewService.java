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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical merge layer that combines scored results from {@code cached_evaluation}
 * with triage/scored rows from {@code forecast_evaluation}.
 *
 * <p>Precedence: cached evaluation (batch/SSE) &gt; scored forecast row &gt; triage row &gt; none,
 * with the cached entry winning only while it is at least as fresh as the forecast row it is
 * merged against. Both the Plan tab and Map tab read through this service so there is a single
 * source of truth.
 *
 * <p><b>One freshness rule, two return shapes.</b> The merge is performed twice here — once into
 * a {@link LocationEvaluationView} ({@link #mergeToView}) and once into the
 * {@code BriefingEvaluationResult} map the briefing enrichment consumes
 * ({@link #getScoresForEnrichment}, {@link #getScoresForEnrichmentBulk}) — because those two
 * consumers need different shapes. They must not have different <em>rules</em>, and they have
 * diverged twice.
 *
 * <p>First on the gate: it landed on the view path alone, so for three days one stale cached
 * rating lost the merge on the map and the region drill-down while still winning it on the
 * briefing payload — the same location reading 4★ on the Close to home panel and 2★ everywhere
 * else on the same screen.
 *
 * <p>Then on the <em>fallback</em>, which is subtler and which the fix for the first one asserted
 * was already closed. Both paths called {@link #cachedIsAtLeastAsFresh} and then disagreed about
 * what a won gate with an <em>empty</em> winner meant: the enrichment path kept the cached entry,
 * the view path returned {@code Source.NONE} and its caller dropped the location outright.
 *
 * <p>⚠️ So precedence is now ONE method — {@link #cachedWins} — and a third reader must call it
 * rather than re-derive any part of it. "Both paths share the gate" was true and was not enough;
 * the useful invariant is that neither path contains a precedence decision of its own.
 */
@Service
public class EvaluationViewService {

    private static final Logger LOG = LoggerFactory.getLogger(EvaluationViewService.class);

    /** Zone {@code forecast_evaluation.forecast_run_at} is implicitly recorded in. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final TypeReference<List<BriefingEvaluationResult>> RESULT_LIST_TYPE =
            new TypeReference<>() { };

    private final BriefingEvaluationService briefingEvaluationService;
    private final CachedEvaluationRepository cachedEvaluationRepository;
    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final LocationService locationService;
    private final ObjectMapper objectMapper;
    private final SolarService solarService;

    /**
     * Constructs an {@code EvaluationViewService}.
     *
     * @param briefingEvaluationService in-memory cache of batch/SSE evaluation results
     * @param cachedEvaluationRepository repository for durable cached evaluations
     * @param forecastEvaluationRepository repository for forecast evaluation rows
     * @param locationService service for retrieving location entities
     * @param objectMapper Jackson mapper for JSON deserialisation
     * @param solarService the sole calculator for the golden/blue hour boundaries
     */
    public EvaluationViewService(BriefingEvaluationService briefingEvaluationService,
            CachedEvaluationRepository cachedEvaluationRepository,
            ForecastEvaluationRepository forecastEvaluationRepository,
            LocationService locationService,
            ObjectMapper objectMapper,
            SolarService solarService) {
        this.briefingEvaluationService = briefingEvaluationService;
        this.cachedEvaluationRepository = cachedEvaluationRepository;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.locationService = locationService;
        this.objectMapper = objectMapper;
        this.solarService = solarService;
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
        Map<Long, LocationEntity> byId = locations.stream()
                .collect(Collectors.toMap(LocationEntity::getId, loc -> loc, (a, b) -> a));
        // ⚠️ The light times are attached HERE and not inside `buildViews`, which is shared with
        // `cachedOnlyViewsForDateRange` — i.e. with `GET /api/forecast`, the map's primary
        // endpoint. That caller hands every surviving view to `ForecastDtoMapper.toSparseListDto`,
        // which reads none of the four, and drops most of them first as already covered by a
        // persisted forecast row. Attaching in the shared method therefore spent three Meeus calls
        // per cached-only row, on every map mount, for a value nothing on that path can read. The
        // fields are serialised by ONE endpoint, so exactly one path pays for them.
        return buildViews(cachedByKey, latestForecasts, locations, start, end, types).stream()
                .map(view -> withLight(view, byId.get(view.locationId())))
                .toList();
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
     * {@link BriefingEvaluationService#getCachedScores}, resolved against
     * {@code forecast_evaluation}: a cached entry speaks for its location only while it is at
     * least as fresh as that location's latest forecast row, and the row speaks otherwise —
     * scored when it carries a rating, triaged when it carries only a reason.
     *
     * <p>The rows arrive in ONE query for the whole region rather than a {@code findTop} per
     * location. Gating needs a row for <em>every</em> location, not only the ones the cache
     * misses, so keeping the point lookup would have turned an occasional query into a
     * per-location fan-out on the briefing build path — which calls this once per region × date ×
     * event.
     *
     * @param regionName the region name
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return map of locationName to evaluation result
     */
    public Map<String, BriefingEvaluationResult> getScoresForEnrichment(
            String regionName, LocalDate date, TargetType targetType) {
        Map<String, BriefingEvaluationResult> cached =
                briefingEvaluationService.getCachedScores(regionName, date, targetType);
        Instant cachedEvaluatedAt = cached.isEmpty() ? null
                : briefingEvaluationService.getCachedEvaluatedAt(regionName, date, targetType)
                        .orElse(null);

        List<LocationEntity> regionLocations = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getRegion() != null
                        && loc.getRegion().getName().equals(regionName))
                .toList();
        Map<String, ForecastEvaluationEntity> latest =
                loadLatestForecasts(regionLocations, date, date, Set.of(targetType));

        Map<String, BriefingEvaluationResult> result = new HashMap<>();
        for (LocationEntity loc : regionLocations) {
            BriefingEvaluationResult resolved = resolveForEnrichment(loc.getName(),
                    cached.get(loc.getName()), cachedEvaluatedAt,
                    latest.get(loc.getId() + "|" + date + "|" + targetType));
            if (resolved != null) {
                result.put(loc.getName(), resolved);
            }
        }

        // Cached entries whose location is not in the enabled roster — renamed, disabled, or moved
        // to another region since the batch wrote them. This map used to START as a copy of the
        // cache, so they were carried; dropping them here would be an unrelated behaviour change
        // riding along with the freshness fix. By definition they have no forecast row to be gated
        // against.
        cached.forEach(result::putIfAbsent);
        return result;
    }

    /**
     * Bulk equivalent of {@link #getScoresForEnrichment} across a whole date range, keyed by
     * {@code "regionName|date|targetType"}.
     *
     * <p>Same precedence — in-memory cached scores win while they are at least as fresh as the
     * location's latest {@code forecast_evaluation} row, which speaks otherwise. Either way the
     * winner supplies rating, summary and headline together. The difference is query shape: the
     * merge issues a <em>single</em> dedup-at-source query for
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
        // When each key's cache entry was written, so step 2 can gate it against the forecast row.
        Map<String, Instant> cachedEvaluatedAtByKey = new HashMap<>();
        List<LocationEntity> locations = locationService.findAllEnabled();

        // 1. In-memory cached scores first — no DB round trip. (Both stores carry a headline, so
        //    that is no longer what separates them; only freshness is.)
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
                        String key = regionName + "|" + date + "|" + type;
                        byKey.put(key, new HashMap<>(cached));
                        briefingEvaluationService.getCachedEvaluatedAt(regionName, date, type)
                                .ifPresent(at -> cachedEvaluatedAtByKey.put(key, at));
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

        // Iterate the locations (not the query result) so the precedence below still resolves in
        // the original, stable location order.
        for (LocationEntity loc : locations) {
            if (loc.getRegion() == null) {
                continue;
            }
            String regionName = loc.getRegion().getName();
            Map<String, ForecastEvaluationEntity> latest =
                    latestByLocationId.getOrDefault(loc.getId(), Map.of());
            for (ForecastEvaluationEntity row : latest.values()) {
                String key = regionName + "|" + row.getTargetDate() + "|" + row.getTargetType();
                Map<String, BriefingEvaluationResult> regionMap =
                        byKey.computeIfAbsent(key, k -> new HashMap<>());
                BriefingEvaluationResult resolved = resolveForEnrichment(loc.getName(),
                        regionMap.get(loc.getName()), cachedEvaluatedAtByKey.get(key), row);
                if (resolved != null) {
                    regionMap.put(loc.getName(), resolved);
                }
            }
        }

        return byKey;
    }

    /**
     * Chooses the result that speaks for one location during briefing enrichment, under the same
     * freshness rule {@link #mergeToView} applies on the view path.
     *
     * <p>Falls back to the cached entry when a newer forecast row yields nothing usable — neither
     * a rating nor a triage reason. Losing the merge is not the same as having something to say,
     * and an unusable row must not blank a location the cache can still describe.
     *
     * @param locationName      the location this result is about
     * @param cachedResult      the cached entry for it, or null when the cache does not cover it
     * @param cachedEvaluatedAt when the cache entry was written, or null when unknown
     * @param forecastRow       that location's latest forecast row for the slot, or null
     * @return the winning result, or null when neither source has anything to say
     */
    private static BriefingEvaluationResult resolveForEnrichment(String locationName,
            BriefingEvaluationResult cachedResult, Instant cachedEvaluatedAt,
            ForecastEvaluationEntity forecastRow) {
        if (cachedWins(cachedResult, cachedEvaluatedAt, forecastRow)) {
            return cachedResult;
        }
        // No `: cachedResult` tail any more, and that is a simplification rather than a change:
        // `cachedWins` is false only when the cache is absent or the row says something, and
        // `toEnrichmentResult` is non-null exactly when the row says something. The old expression
        // could only ever fall through to a null `cachedResult`.
        return toEnrichmentResult(locationName, forecastRow);
    }

    /**
     * The single precedence rule both merge paths obey: may the cached entry speak for this slot?
     *
     * <p>Two clauses, and the second is the one this method exists for.
     *
     * <ol>
     *   <li>The cache is at least as fresh as the forecast row — {@link #cachedIsAtLeastAsFresh},
     *       the gate whose own javadoc records what it is for.</li>
     *   <li><b>Or that row has nothing to say.</b> Losing a freshness comparison is not the same
     *       as being contradicted. A newer row carrying neither a rating nor a triage reason — a
     *       bare base-forecast row, and roughly three quarters of {@code forecast_evaluation} has
     *       a null rating — reports no opinion about this slot, so there is nothing for the cache
     *       to be wrong about.</li>
     * </ol>
     *
     * <p>⚠️ <b>This does not weaken the freshness gate, and that is the objection to check first.</b>
     * The gate exists because a stale 4★ was outliving a current row triaged {@code HIGH_CLOUD} on
     * 87–99% low cloud — measured over four days in production, one rating 47.9 hours out of date.
     * In every case of that shape the newer row <em>is</em> triaged, so clause 2 is false and the
     * cache still loses. Clause 2 fires only where the newer row is empty, which carries no
     * contradicting information at all.
     *
     * <p>⚠️ <b>Both paths must call this rather than re-derive precedence.</b> They already shared
     * the gate and the class comment above claimed they were unified on the whole rule — they were
     * not: {@code resolveForEnrichment} fell back to the cached entry when the winning row was
     * empty and {@code mergeToView} returned {@code Source.NONE}, whose caller drops the row. Same
     * tables, same gate, opposite answer: the briefing kept the rating and {@code /scores} lost the
     * location entirely. Found while investigating the heat field's empty windows
     * ({@code docs/engineering/heat-field-scores-join-gap.md}); it was not the cause of that
     * (production had zero slots where the gate even fired) but it is a live trap the moment one
     * does.
     *
     * @param cachedResult      the cached entry, or null when the cache does not cover this slot
     * @param cachedEvaluatedAt when the cache entry was written, or null when unknown
     * @param forecastRow       the latest forecast row for the slot, or null
     * @return true when the cached entry should speak for this slot
     */
    private static boolean cachedWins(BriefingEvaluationResult cachedResult,
            Instant cachedEvaluatedAt, ForecastEvaluationEntity forecastRow) {
        if (cachedResult == null) {
            return false;
        }
        return cachedIsAtLeastAsFresh(cacheWriteTime(cachedResult, cachedEvaluatedAt), forecastRow)
                || !hasSomethingToSay(forecastRow);
    }

    /**
     * When the winning cached entry was written: this location's own stamp where it exists, and
     * the region-level one only where it does not.
     *
     * <p>⚠️ <b>The region stamp cannot answer this question, and reading it here was the triage
     * ratchet.</b> {@code CachedEvaluation.evaluatedAt} is per cache key and is reset by <em>any</em>
     * write to the region, while the batch write paths <em>merge</em> — a location the batch did not
     * carry keeps its old result and inherits the new region stamp. So a location's rating could
     * only ever be overwritten by a fresh evaluation, a fresh evaluation only happened when triage
     * passed, and the region stamp made the retained rating look newer than the very stand-down that
     * had excluded it. Deteriorating weather therefore locked an optimistic rating in place: the
     * worse the sky got, the more firmly the stale star held. Confirmed in production on
     * 2026-08-28, where a slot scored 4★ at 01:22 on an overnight run predicting a 57% solar horizon
     * was still being served that evening, after the 14:04 cycle read 84% and stood it down —
     * because the 14:09 merge that retained it moved the region stamp past the 14:04 row.
     *
     * <p>The per-location stamp is the field's documented purpose (see
     * {@link BriefingEvaluationResult#evaluatedAt()}), and it was added for exactly this class of
     * error one surface over: {@code evaluation_delta_log.age_hours} was logging 24h rating
     * movements as ~0h old off the region stamp. The gate pre-dates the field and was never moved
     * onto it.
     *
     * <p>Null falls back to the region stamp rather than to a policy of its own: results cached
     * before the field existed genuinely carry no per-location write time, and the region stamp is
     * the same answer those entries got before. {@link #cachedIsAtLeastAsFresh} then treats a null
     * pair as unknown, which keeps the cache — unchanged behaviour for legacy rows.
     *
     * @param cachedResult      the cached entry, never null
     * @param regionEvaluatedAt the region-level cache stamp, or null when unknown
     * @return the instant to gate this entry on, or null when neither is known
     */
    private static Instant cacheWriteTime(BriefingEvaluationResult cachedResult,
            Instant regionEvaluatedAt) {
        return cachedResult.evaluatedAt() != null ? cachedResult.evaluatedAt() : regionEvaluatedAt;
    }

    /**
     * Whether a forecast row carries an opinion about its slot — a rating, or a triage reason.
     *
     * <p>The condition under which {@link #toEnrichmentResult} returns non-null and under which
     * {@link #mergeToView}'s branches 2 and 3 fire, named once so the three cannot drift.
     *
     * @param row the row, or null
     * @return true when the row says something a reader could act on
     */
    private static boolean hasSomethingToSay(ForecastEvaluationEntity row) {
        return row != null
                && (row.getRating() != null
                    || (row.getTriage() != null && row.getTriage().getReason() != null));
    }

    /**
     * Converts a forecast row into the enrichment shape: scored when it carries a rating, triaged
     * when it carries only a reason, null when it says neither.
     *
     * <p>The row's own {@code headline} rides along with its rating and summary. It is not
     * decoration: {@code BriefingService.enrichSlot} <em>assigns</em> whatever it is given, so
     * passing null here would actively blank a card header the slot already had — and the drill-down
     * renders that header with no fallback. Every scored row carries one ({@code ForecastService}
     * persists it on the sole write path), so dropping it would discard prose that exists rather
     * than represent an absence. Whichever store wins the gate supplies all three fields together.
     *
     * @param locationName the location this row is about
     * @param row          the row, or null
     * @return the result, or null when the row says nothing usable
     */
    private static BriefingEvaluationResult toEnrichmentResult(String locationName,
            ForecastEvaluationEntity row) {
        if (!hasSomethingToSay(row)) {
            return null;
        }
        if (row.getRating() != null) {
            return new BriefingEvaluationResult(locationName, row.getRating(),
                    row.getFierySkyPotential(), row.getGoldenHourPotential(), row.getSummary(),
                    null, null, row.getHeadline());
        }
        return new BriefingEvaluationResult(locationName, null, null, null, null,
                row.getTriage().getReason(), row.getTriage().getMessage());
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
        return withLight(mergeToView(loc.getId(), loc.getName(), regionId, regionName, date,
                targetType, cachedResult, cachedEvaluatedAt, forecastRow), loc);
    }

    /**
     * Attaches this location's golden/blue hour boundaries for the view's own date and event.
     *
     * <p>Pure astronomy from the location's lat/lon — never persisted, no migration, and
     * deliberately <em>not</em> put on {@code BriefingSlot}, which serialises into
     * {@code daily_briefing_cache} where a rollback would then throw on every cached row.
     *
     * <p>{@link SolarService#goldenBlueWindow} is the single calculator, the same one
     * {@code ForecastDtoMapper} uses for the map popup. ⚠️ <b>That makes the astronomy shared; it
     * does NOT make the two surfaces incapable of disagreeing, and an earlier draft of this
     * javadoc claimed it did.</b> The mapper reads the coordinates <em>snapshotted onto the
     * forecast row</em> at run time ({@code ForecastEvaluationEntity.locationLat/Lon}); this reads
     * the <em>live</em> {@link LocationEntity}. Lat/lon is editable in the Admin UI, so between an
     * edit and the next run the map popup and the Plan sheet legitimately compute from two
     * different points. Neither is wrong — the map is describing the forecast it made — and the
     * fix is not to converge them here but to know which question each is answering.
     *
     * <p>{@code HOURLY} has no solar event to bound. Two failure modes degrade to no times rather
     * than to invented ones: a thrown exception, and the <b>midnight sentinel</b> — {@code
     * solar-utils} returns midnight-of-the-date, not null and not a throw, for an event that never
     * occurs, which around the solstice is every civil-twilight boundary above roughly 60.5°N and
     * therefore reachable from the northern end of this roster. A bracket check alone accepts it
     * (the sentinel sorts perfectly happily inside a day), so it is rejected per boundary by value.
     * The one-boundary case matters: a sentinel civil dawn drops the blue window and leaves the
     * golden one, which is a true partial answer rather than a suppressed whole one.
     *
     * <p>⚠️ <b>This deliberately does NOT carry {@code ForecastDtoMapper}'s lat/lon null guard, and
     * that is a difference in the inputs rather than in the rule.</b> That mapper reads a forecast
     * row's nullable {@code BigDecimal} copies of the coordinates, which genuinely can be absent;
     * {@link LocationEntity#getLat()} and {@code getLon()} are primitive {@code double} on
     * {@code NOT NULL} columns (V5), so "missing coordinates" is not a state this method can be
     * handed. There is therefore no such test to write, and adding a guard would either be dead or
     * would have to reject some real coordinate by value — which 0/0 is.
     *
     * <p>The remaining guard is {@code HOURLY} alone. Everything else that could go wrong here —
     * a null date, a null target type — reaches the calculator and comes back through the
     * {@code catch} as no times, which is the same answer a guard would have given for one fewer
     * unreachable branch.
     *
     * @param view the merged view
     * @param loc  the location the view is about
     * @return the view with light times attached, or unchanged when there are none to attach
     */
    private LocationEvaluationView withLight(LocationEvaluationView view, LocationEntity loc) {
        if (loc == null || view.targetType() == TargetType.HOURLY) {
            return view;
        }
        try {
            SolarService.SolarWindow window = solarService.goldenBlueWindow(
                    loc.getLat(), loc.getLon(), view.date(),
                    view.targetType() == TargetType.SUNRISE);
            LocalDateTime midnight = view.date().atStartOfDay();
            return view.withLightTimes(
                    realOrNull(window.goldenHourStart(), midnight),
                    realOrNull(window.goldenHourEnd(), midnight),
                    realOrNull(window.blueHourStart(), midnight),
                    realOrNull(window.blueHourEnd(), midnight));
        } catch (Exception e) {
            LOG.debug("Golden/blue hour calculation failed for {} on {} {}: {}",
                    loc.getName(), view.date(), view.targetType(), e.toString());
            return view;
        }
    }

    /**
     * Applies the merge precedence rule to produce a single view.
     *
     * <ol>
     *   <li>Cached evaluation, <b>if it is at least as fresh as the forecast row</b> →
     *       CACHED_EVALUATION (scored or triaged)</li>
     *   <li>Scored forecast_evaluation row → FORECAST_EVALUATION_SCORED</li>
     *   <li>Triaged forecast_evaluation row → FORECAST_EVALUATION_TRIAGE</li>
     *   <li>Nothing → NONE</li>
     * </ol>
     *
     * <p>Every branch returns a view with <b>no light times</b>: they are attached once, by
     * {@link #withLight}, because they answer a question this merge does not ask — the merge
     * decides what was said about a slot, the light times are true of it whatever was said.
     *
     * <p><b>The freshness gate is the whole point of this method.</b> Cached evaluations used to
     * win unconditionally, and because a triaged slot makes no Claude call and therefore writes no
     * cached rating, the only way a rating coexists with a triage is that the rating came from an
     * <em>earlier, more optimistic</em> run. Measured in production over four days for two
     * neighbouring locations: every 4★ cached rating was stale — one by 47.9 hours — against a
     * current row triaged {@code HIGH_CLOUD} on 87–99% low cloud at the solar horizon, while every
     * cached rating that was fresher than its forecast row scored ≤2. The stale rating was being
     * served to the Plan grid and the map as the live verdict.
     */
    private LocationEvaluationView mergeToView(Long locationId, String locationName,
            Long regionId, String regionName, LocalDate date, TargetType targetType,
            BriefingEvaluationResult cachedResult, Instant cachedEvaluatedAt,
            ForecastEvaluationEntity forecastRow) {

        // 1. Cached evaluation, under the SHARED precedence rule — see `cachedWins`.
        if (cachedWins(cachedResult, cachedEvaluatedAt, forecastRow)) {
            DisplayVerdict displayVerdict = DisplayVerdict.resolve(
                    cachedResult.rating(),
                    cachedResult.triageReason() != null ? Verdict.STANDDOWN : null);
            return new LocationEvaluationView(
                    locationId, locationName, regionId, regionName, date, targetType,
                    Source.CACHED_EVALUATION,
                    cachedResult.rating(), cachedResult.summary(),
                    cachedResult.fierySkyPotential(), cachedResult.goldenHourPotential(),
                    cachedResult.triageReason(), cachedResult.triageMessage(),
                    // The instant the gate above actually compared, not the region's last-touched
                    // stamp. It is served as `forecastRunAt` on the map DTO, so on a region whose
                    // slots span several batches the region stamp claimed a location was scored at
                    // whatever time some other location's batch happened to land.
                    null, cacheWriteTime(cachedResult, cachedEvaluatedAt),
                    displayVerdict,
                    null, null, null, null);
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
                    forecastRunInstant(forecastRow),
                    displayVerdict,
                    null, null, null, null);
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
                    forecastRunInstant(forecastRow),
                    displayVerdict,
                    null, null, null, null);
        }

        // 4. Nothing
        return emptyView(locationId, locationName, regionId, regionName, date, targetType);
    }

    /**
     * A boundary time, or null when it is {@code solar-utils}' never-occurs sentinel.
     *
     * <p>The library returns midnight-of-the-date for an event that does not happen — never null
     * and never a throw. {@code SolarCalculator.hourAngle} takes {@code acos} of an out-of-domain
     * cosine and gets NaN, and {@code Math.round(NaN)} is 0, so the result is
     * {@code date.atStartOfDay()}, which reads as a perfectly ordinary time to every downstream
     * check.
     *
     * <p>This is the SAME test {@link TodaysLightService}'s {@code boundary} already applies, and
     * it is stated there with the measurement: at 60.8°N (Unst, a real UK postcode) against
     * solar-utils 2.1.0, the sentinel lands on the civil pair at midsummer and on the golden pair
     * at midwinter. The two surfaces differ only in the POLICY that follows the detection, and
     * rightly: that one must draw a complete rule, so it substitutes an approximation; this one is
     * one line among several on a card, so it goes quiet. Same detection, different answers to
     * "what now".
     *
     * @param time     a boundary from {@link SolarService.SolarWindow}
     * @param midnight start of the day the boundary was asked for
     * @return the time, or null if it is the sentinel
     */
    private static LocalDateTime realOrNull(LocalDateTime time, LocalDateTime midnight) {
        return time == null || time.equals(midnight) ? null : time;
    }

    /**
     * Whether the cached evaluation may still speak for this slot.
     *
     * <p>Returns true when there is nothing to compare against, and when the cached write is not
     * older than the forecast run. Ties go to the cache: a same-instant pair is the batch writing
     * both halves of one run, where the cache carries strictly more (rating, prose, sub-scores).
     *
     * <p>Unknown freshness also returns true, which preserves the previous behaviour rather than
     * inventing a new one. Both {@code cached_evaluation} timestamps are {@code nullable = false}
     * and the in-memory writers all stamp {@code Instant.now()}, so a null here is a
     * cannot-happen rather than a case worth a policy.
     *
     * <p>The instant is resolved by {@link #cacheWriteTime}, which prefers the entry's own
     * per-location stamp — the region-level one belongs to the region, not to this slot.
     *
     * @param cachedEvaluatedAt when the cached entry was last written, or null if unknown
     * @param forecastRow       the latest forecast_evaluation row for the slot, or null
     * @return true if the cached entry should win the merge
     */
    private static boolean cachedIsAtLeastAsFresh(Instant cachedEvaluatedAt,
            ForecastEvaluationEntity forecastRow) {
        Instant runAt = forecastRunInstant(forecastRow);
        if (runAt == null || cachedEvaluatedAt == null) {
            return true;
        }
        return !cachedEvaluatedAt.isBefore(runAt);
    }

    /**
     * The forecast run instant, or null when there is no row or no stamp.
     *
     * <p>{@code forecast_run_at} is a naive {@code LocalDateTime}; it is recorded in
     * {@link #LONDON}, not UTC, so it must be zoned before it can be compared with an
     * {@link Instant}. Comparing it raw would be silently out by an hour through BST — enough to
     * invert the verdict on any pair written within an hour of each other, which the nightly
     * cycle produces routinely.
     *
     * @param forecastRow the row, or null
     * @return the instant the forecast ran, or null
     */
    private static Instant forecastRunInstant(ForecastEvaluationEntity forecastRow) {
        if (forecastRow == null || forecastRow.getForecastRunAt() == null) {
            return null;
        }
        return forecastRow.getForecastRunAt().atZone(LONDON).toInstant();
    }

    private LocationEvaluationView emptyView(Long locationId, String locationName,
            Long regionId, String regionName, LocalDate date, TargetType targetType) {
        return new LocationEvaluationView(
                locationId, locationName, regionId, regionName, date, targetType,
                Source.NONE, null, null, null, null, null, null, null, null,
                DisplayVerdict.AWAITING,
                null, null, null, null);
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
                // getUpdatedAt(), NOT getEvaluatedAt(): the entity documents the former as
                // "when this row was last updated" and the latter as "when the evaluation was
                // first created", and persistToDb only ever sets evaluated_at inside its
                // orElseGet for a NEW row. A slot re-evaluated for three days running still
                // carries its day-one evaluated_at, so hydrating from it made every
                // DB-sourced entry look older than it is — and the freshness rule below is
                // only sound on the last-write stamp.
                result.put(key, new CachedEntry(map, entity.getUpdatedAt()));
            } catch (Exception e) {
                LOG.warn("Failed to parse cached evaluation {}: {}",
                        key, e.getMessage());
            }
        }

        return result;
    }
}
