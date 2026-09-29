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
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 *
 * <p>⚠️ <b>A nightly Gate 4 stability skip is a second kind of "newer evidence" and is applied
 * BEFORE precedence, not inside it.</b> {@code cachedWins} only ever compares a cached rating
 * against a {@code forecast_evaluation} row — it has no way to notice that the pipeline looked at a
 * slot again and declined to re-score it, because that decision writes no row to either table (see
 * {@link #retractStaleEvidence}). Every entry point below therefore loads the slot's most recent
 * {@code SKIPPED_STABILITY} disposition first and nulls out whichever of the cached result and the
 * forecast row predates it, so a rating the batch has since declined to refresh is served the same
 * way a never-rated slot is — no star, no verdict, no prose — rather than going on being served
 * from a run the pipeline itself has moved past. A later real evaluation (eligible or forced)
 * simply outdates the skip and is unaffected.
 */
@Service
public class EvaluationViewService {

    private static final Logger LOG = LoggerFactory.getLogger(EvaluationViewService.class);

    private static final TypeReference<List<BriefingEvaluationResult>> RESULT_LIST_TYPE =
            new TypeReference<>() { };

    private final BriefingEvaluationService briefingEvaluationService;
    private final CachedEvaluationRepository cachedEvaluationRepository;
    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final ForecastRunDispositionRepository forecastRunDispositionRepository;
    private final LocationService locationService;
    private final ObjectMapper objectMapper;
    private final SolarService solarService;

    /**
     * Constructs an {@code EvaluationViewService}.
     *
     * @param briefingEvaluationService in-memory cache of batch/SSE evaluation results
     * @param cachedEvaluationRepository repository for durable cached evaluations
     * @param forecastEvaluationRepository repository for forecast evaluation rows
     * @param forecastRunDispositionRepository repository for per-candidate batch dispositions,
     *                                          read here for the stale-rating stability-skip rule
     * @param locationService service for retrieving location entities
     * @param objectMapper Jackson mapper for JSON deserialisation
     * @param solarService the sole calculator for the golden/blue hour boundaries
     */
    public EvaluationViewService(BriefingEvaluationService briefingEvaluationService,
            CachedEvaluationRepository cachedEvaluationRepository,
            ForecastEvaluationRepository forecastEvaluationRepository,
            ForecastRunDispositionRepository forecastRunDispositionRepository,
            LocationService locationService,
            ObjectMapper objectMapper,
            SolarService solarService) {
        this.briefingEvaluationService = briefingEvaluationService;
        this.cachedEvaluationRepository = cachedEvaluationRepository;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.forecastRunDispositionRepository = forecastRunDispositionRepository;
        this.locationService = locationService;
        this.objectMapper = objectMapper;
        this.solarService = solarService;
    }

    /**
     * Bulk-loads, for every slot with at least one nightly Gate 4 stability skip in the range, the
     * instant of its most recent such skip — see {@link ForecastRunDispositionRepository
     * #findLatestStabilitySkipTimestamps} for exactly which dispositions count.
     *
     * <p>Public so {@code ForecastController} can apply the same retraction to the raw
     * {@code forecast_evaluation} rows it reads directly for {@code GET /api/forecast} — the one
     * serve path that does not go through {@link #mergeToView} at all for a slot already covered by
     * a persisted row, and so cannot pick the rule up merely by calling a method already here.
     *
     * <p>One query per call, bounded to the caller's own served window — never called per slot.
     * Every caller loads it once per request: {@code forDateRange} and {@code
     * getScoresForEnrichmentBulk} (the two accessors behind {@code GET /api/forecast}, {@code
     * GET /api/briefing/evaluate/scores} and {@code GET /api/briefing}) each call it exactly once,
     * outside their own per-location/per-date loops, never inside one.
     *
     * <p>⚠️ <b>Known limit — the join is on location NAME, and a rename between the skip and the
     * serve breaks it.</b> {@code forecast_run_disposition.location_name} is a denormalised snapshot
     * taken when the disposition was written; renaming a location afterwards (production did this
     * the day this rule shipped — "Windy Gyll" to "Windy Gyle") means its older stability-skip rows
     * carry the old name, and this lookup — keyed on the location's CURRENT name, since that is what
     * {@code cached_evaluation} and this method's other callers key on too — misses them. The
     * practical effect is narrow and safe-direction: the renamed location's rating survives past the
     * point it should have been retracted, until its next stability skip or its next real evaluation
     * is written under the new name. Not fixed here — deliberately left as a known limit rather than
     * addressed, since a rename is rare and the failure mode is "serves a rating one cycle too long",
     * the same direction every other unknown-freshness case in this class already fails toward.
     *
     * @param start first evaluation date to include (inclusive)
     * @param end   last evaluation date to include (inclusive)
     * @return {@code "locationName|date|targetType"} to the instant of that slot's most recent
     *         stability skip; a slot with none is simply absent, never mapped to {@code null}
     */
    public Map<String, Instant> loadStabilitySkips(LocalDate start, LocalDate end) {
        Map<String, Instant> result = new HashMap<>();
        for (Object[] row : forecastRunDispositionRepository
                .findLatestStabilitySkipTimestamps(start, end)) {
            String locationName = (String) row[0];
            LocalDate date = (LocalDate) row[1];
            String eventType = (String) row[2];
            Instant lastSkippedAt = (Instant) row[3];
            result.put(stabilitySkipKey(locationName, date, eventType), lastSkippedAt);
        }
        return result;
    }

    /**
     * Bulk-loads, for every slot with at least one {@code EVALUATED} or {@code FORCE_EVALUATED}
     * disposition in the range, whether its most recent such disposition is
     * {@code FORCE_EVALUATED} — the one fact the verdict-minimum-sample rule's force-evaluation
     * exemption needs (owner decision, 2026-09-29;
     * {@code docs/engineering/plan-verdict-consolidation-plan.md}, {@code VerdictSampleGate}).
     *
     * <p>Same shape and the same reasoning as {@link #loadStabilitySkips}: one bulk query per
     * serve, bounded to the caller's own served window, never called per region or per slot.
     *
     * <p><b>Defensive by design.</b> A failed or empty lookup must never grant the exemption — an
     * unknown answer reads as "not forced", the same safe direction every other unknown-freshness
     * case in this class already fails toward — so a repository failure is caught and logged rather
     * than propagated, and this returns an empty map on that path.
     *
     * <p>Inherits the same location-NAME join limit {@link #loadStabilitySkips} documents: a
     * location renamed between the forced evaluation and this serve is not found under its new
     * name and reads as not forced. Not fixed here, for the same reason it is not fixed there — the
     * failure direction is safe (the exemption simply does not apply one cycle too early, rather
     * than applying when it should not).
     *
     * @param start first evaluation date to include (inclusive)
     * @param end   last evaluation date to include (inclusive)
     * @return {@code "locationName|date|targetType"} to whether that slot's most recent evaluating
     *         disposition is {@code FORCE_EVALUATED}; a slot with none is absent, never mapped to
     *         a value
     */
    public Map<String, Boolean> loadForcedFlags(LocalDate start, LocalDate end) {
        Map<String, Boolean> result = new HashMap<>();
        try {
            for (Object[] row : forecastRunDispositionRepository
                    .findLatestEvaluatingDispositions(start, end)) {
                String locationName = (String) row[0];
                LocalDate date = (LocalDate) row[1];
                String eventType = (String) row[2];
                String disposition = (String) row[3];
                result.put(stabilitySkipKey(locationName, date, eventType),
                        "FORCE_EVALUATED".equals(disposition));
            }
        } catch (RuntimeException e) {
            LOG.warn("[FORCE-EVAL] Could not load forced-evaluation dispositions for {}..{} — "
                    + "every slot in range reads as not forced this serve: {}",
                    start, end, e.toString());
            return new HashMap<>();
        }
        return result;
    }

    /**
     * Stamps a resolved enrichment result as forced when the disposition lookup says its most
     * recent evaluating run was {@code FORCE_EVALUATED} — a no-op on a null result, an unrated
     * result, or when the lookup has nothing for this slot's key.
     *
     * <p>One predicate for both {@link #getScoresForEnrichment} and
     * {@link #getScoresForEnrichmentBulk}, so the two cannot disagree about when a rating counts as
     * forced — the same reason {@link #cachedWins} is one method rather than two derivations.
     *
     * @param result the resolved result, or null
     * @param forced whether this slot's key was found FORCE_EVALUATED, or null when absent
     * @return {@code result} unchanged, or stamped forced when it carries a rating and the lookup
     *         says so
     */
    private static BriefingEvaluationResult stampForced(BriefingEvaluationResult result,
            Boolean forced) {
        if (result == null || result.rating() == null || !Boolean.TRUE.equals(forced)) {
            return result;
        }
        return result.withForced(true);
    }

    /**
     * The lookup key {@link #loadStabilitySkips} and every caller of {@link #mergeToView} /
     * {@link #resolveForEnrichment} agree on — location name (never id: the disposition table and
     * {@code cached_evaluation} are both keyed by name), evaluation date, event type name.
     *
     * @param locationName the location name
     * @param date         the evaluation date
     * @param targetType   SUNRISE or SUNSET
     * @return the composite key
     */
    private static String stabilitySkipKey(String locationName, LocalDate date,
            TargetType targetType) {
        return stabilitySkipKey(locationName, date, targetType.name());
    }

    /**
     * Overload for the raw string the JPQL projection returns, so {@link #loadStabilitySkips}
     * builds its map with the exact same key shape the {@link TargetType}-typed overload produces.
     */
    private static String stabilitySkipKey(String locationName, LocalDate date, String eventType) {
        return locationName + "|" + date + "|" + eventType;
    }

    /**
     * Whether evidence written at {@code evidenceWrittenAt} has been superseded by a nightly Gate 4
     * stability skip decided afterwards, and must therefore not be served on its own.
     *
     * <p>A nightly stability skip writes no row to {@code cached_evaluation} or
     * {@code forecast_evaluation} — the whole reason the defect this guards against exists — so
     * this is evidence the precedence rule in {@link #cachedWins} cannot see by comparing the two
     * tables against each other. It has to be checked separately, before precedence, against both
     * sources independently (a fresher forecast row and a stale cache retract only the cache; both
     * stale retracts both).
     *
     * <p>Either argument {@code null} never retracts: no evidence instant means nothing to compare
     * (the existing {@code cachedIsAtLeastAsFresh}/{@code cachedWins} null convention — unknown age
     * keeps today's behaviour), and no stability skip means the pipeline has recorded no decision
     * against this slot at all.
     *
     * <p>⚠️ <b>Accepted trade-off — "newest decision wins" cuts both ways.</b> A hand-started admin
     * run or a JFDI force-submit made shortly BEFORE the nightly cycle starts is retracted a few
     * minutes later if that same cycle's Gate 4 policy declines the slot — the evaluation was real
     * and current when it landed, but the pipeline's own next look is what this rule treats as the
     * newer word on the subject. This is not a bug to fix: the rule has no way to know a human just
     * asked for that slot, and treating an admin evaluation as exempt would mean the one path most
     * likely to be re-checked deliberately (an operator chasing a specific forecast) is also the one
     * path immune to ever being retracted once stale. The window is short — one nightly cycle — and
     * a genuinely wanted rating survives it if the slot is still Gate-4-eligible; if it is not, that
     * is the same policy every other slot answers to.
     *
     * @param evidenceWrittenAt   when the cached result or forecast row was written, or
     *                            {@code null} when unknown
     * @param latestStabilitySkipAt the slot's most recent {@code SKIPPED_STABILITY} disposition
     *                              instant, or {@code null} when it has none
     * @return true when the evidence predates the skip and must be treated as absent
     */
    public static boolean isRetractedByStabilitySkip(Instant evidenceWrittenAt,
            Instant latestStabilitySkipAt) {
        return evidenceWrittenAt != null && latestStabilitySkipAt != null
                && latestStabilitySkipAt.isAfter(evidenceWrittenAt);
    }

    /**
     * Retracts a cached result if it predates the slot's most recent stability skip.
     *
     * @param cachedResult          the cached entry, or null
     * @param cacheEvaluatedAt      when the cache entry was written — the region stamp, resolved to
     *                              the per-location write time the same way {@link #cacheWriteTime}
     *                              does, inside this method
     * @param latestStabilitySkipAt the slot's most recent stability skip instant, or null
     * @return {@code cachedResult} unchanged, or {@code null} when it must be treated as absent
     */
    private static BriefingEvaluationResult retractStaleEvidence(BriefingEvaluationResult cachedResult,
            Instant cacheEvaluatedAt, Instant latestStabilitySkipAt) {
        if (cachedResult == null) {
            return null;
        }
        Instant writtenAt = cacheWriteTime(cachedResult, cacheEvaluatedAt);
        return isRetractedByStabilitySkip(writtenAt, latestStabilitySkipAt) ? null : cachedResult;
    }

    /**
     * Retracts a {@code forecast_evaluation} row if it predates the slot's most recent stability
     * skip — whether the row carries a rating or only a triage reason. A stale triage row must be
     * retracted exactly like a stale rating: otherwise a slot the pipeline has since decided not to
     * re-look at would go on reading as a weather stand-down from a run the pipeline has moved past,
     * rather than as the never-rated slot it now is.
     *
     * @param forecastRow           the row, or null
     * @param latestStabilitySkipAt the slot's most recent stability skip instant, or null
     * @return {@code forecastRow} unchanged, or {@code null} when it must be treated as absent
     */
    private static ForecastEvaluationEntity retractStaleForecastRow(
            ForecastEvaluationEntity forecastRow, Instant latestStabilitySkipAt) {
        return isRetractedByStabilitySkip(forecastRunInstant(forecastRow), latestStabilitySkipAt)
                ? null : forecastRow;
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
        Map<String, Instant> stabilitySkips = loadStabilitySkips(date, date);

        List<LocationEvaluationView> views = new ArrayList<>();
        for (LocationEntity loc : regionLocations) {
            views.add(buildView(loc, date, targetType, cached.get(loc.getName()), stabilitySkips));
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
        return buildView(loc, date, targetType, cachedResult, loadStabilitySkips(date, date));
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
        Map<String, Instant> stabilitySkips = loadStabilitySkips(start, end);
        Map<Long, LocationEntity> byId = locations.stream()
                .collect(Collectors.toMap(LocationEntity::getId, loc -> loc, (a, b) -> a));
        // ⚠️ The light times are attached HERE and not inside `buildViews`, which is shared with
        // `cachedOnlyViewsForDateRange` — i.e. with `GET /api/forecast`, the map's primary
        // endpoint. That caller hands every surviving view to `ForecastDtoMapper.toSparseListDto`,
        // which reads none of the four, and drops most of them first as already covered by a
        // persisted forecast row. Attaching in the shared method therefore spent three Meeus calls
        // per cached-only row, on every map mount, for a value nothing on that path can read. The
        // fields are serialised by ONE endpoint, so exactly one path pays for them.
        return buildViews(cachedByKey, latestForecasts, locations, start, end, types, stabilitySkips)
                .stream()
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
        return cachedOnlyViewsForDateRange(start, end, types, locations,
                loadStabilitySkips(start, end));
    }

    /**
     * Overload of {@link #cachedOnlyViewsForDateRange} for a caller that has already bulk-loaded
     * the range's stability skips for its own retraction pass over {@code forecast_evaluation} —
     * {@code ForecastController.getForecasts} is the one caller today. Saves a second identical
     * query within the same request; behaviour is otherwise identical.
     *
     * @param start          the start date (inclusive)
     * @param end            the end date (inclusive)
     * @param types          the target types to include
     * @param locations      the enabled locations, supplied by the caller to avoid a repeat query
     * @param stabilitySkips the range's stability skips, keyed as {@link #loadStabilitySkips} keys them
     * @return the cached-only views, ordered by date then location
     */
    public List<LocationEvaluationView> cachedOnlyViewsForDateRange(LocalDate start, LocalDate end,
            Set<TargetType> types, List<LocationEntity> locations,
            Map<String, Instant> stabilitySkips) {
        Map<String, CachedEntry> cachedByKey = loadCachedEvaluations(start, end, locations);
        return buildViews(cachedByKey, Map.of(), locations, start, end, types, stabilitySkips);
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
            LocalDate start, LocalDate end, Set<TargetType> types,
            Map<String, Instant> stabilitySkips) {
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
                            date, type, cachedResult, cachedEvaluatedAt, forecastRow,
                            stabilitySkips.get(stabilitySkipKey(loc.getName(), date, type)));

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
        Map<String, Instant> stabilitySkips = loadStabilitySkips(date, date);
        Map<String, Boolean> forcedFlags = loadForcedFlags(date, date);

        Map<String, BriefingEvaluationResult> result = new HashMap<>();
        for (LocationEntity loc : regionLocations) {
            String key = stabilitySkipKey(loc.getName(), date, targetType);
            Instant latestSkipAt = stabilitySkips.get(key);
            BriefingEvaluationResult resolved = resolveForEnrichmentRetractionAware(loc.getName(),
                    cached.get(loc.getName()), cachedEvaluatedAt,
                    latest.get(loc.getId() + "|" + date + "|" + targetType), latestSkipAt);
            resolved = stampForced(resolved, forcedFlags.get(key));
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
        // One bulk load for the whole window — see the class javadoc on why a stability skip is
        // applied before precedence rather than inside it.
        Map<String, Instant> stabilitySkips = loadStabilitySkips(start, end);
        // One more bulk load for the whole window — the force-evaluation sample exemption's one
        // fact (owner decision, 2026-09-29; see VerdictSampleGate). Loaded once here rather than
        // inside either loop below, same reasoning as stabilitySkips.
        Map<String, Boolean> forcedFlags = loadForcedFlags(start, end);
        // Locations retracted by either step, per key — a location can be retracted in step 1
        // (its cache entry) and then found to have no surviving forecast row in step 2 either, or
        // vice versa. Recorded rather than resolved inline because step 1 cannot yet see step 2's
        // forecast rows: nulling out the cache there is not enough to know whether the OUTCOME for
        // this slot is "retracted" or "never had anything cached at all" until step 2 has also run.
        // See the class javadoc's note on why retraction must be distinguishable from absence.
        Map<String, Set<String>> retractedLocationsByKey = new HashMap<>();

        // 1. In-memory cached scores first — no DB round trip. (Both stores carry a headline, so
        //    that is no longer what separates them; only freshness is.) Each location's own entry
        //    is retracted independently — one stale location in an otherwise-fresh region must not
        //    survive on the strength of its neighbours.
        Set<String> regionNames = locations.stream()
                .filter(loc -> loc.getRegion() != null)
                .map(loc -> loc.getRegion().getName())
                .collect(java.util.stream.Collectors.toSet());
        for (String regionName : regionNames) {
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                for (TargetType type : types) {
                    Map<String, BriefingEvaluationResult> cached =
                            briefingEvaluationService.getCachedScores(regionName, date, type);
                    if (cached.isEmpty()) {
                        continue;
                    }
                    String key = regionName + "|" + date + "|" + type;
                    Instant regionEvaluatedAt = briefingEvaluationService
                            .getCachedEvaluatedAt(regionName, date, type).orElse(null);
                    Map<String, BriefingEvaluationResult> retained = new HashMap<>();
                    for (Map.Entry<String, BriefingEvaluationResult> e : cached.entrySet()) {
                        String slotKey = stabilitySkipKey(e.getKey(), date, type);
                        Instant skipAt = stabilitySkips.get(slotKey);
                        if (isRetractedByStabilitySkip(
                                cacheWriteTime(e.getValue(), regionEvaluatedAt), skipAt)) {
                            retractedLocationsByKey.computeIfAbsent(key, k -> new HashSet<>())
                                    .add(e.getKey());
                            continue;
                        }
                        // Stamped here so a cache-only location (never visited by step 2 below,
                        // which iterates forecast_evaluation rows) still carries its forced flag —
                        // step 2 re-stamps the winning result anyway, harmlessly, for every
                        // location that DOES have a forecast row.
                        retained.put(e.getKey(),
                                stampForced(e.getValue(), forcedFlags.get(slotKey)));
                    }
                    byKey.put(key, retained);
                    if (regionEvaluatedAt != null) {
                        cachedEvaluatedAtByKey.put(key, regionEvaluatedAt);
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
                String slotKey = stabilitySkipKey(
                        loc.getName(), row.getTargetDate(), row.getTargetType());
                Instant skipAt = stabilitySkips.get(slotKey);
                ForecastEvaluationEntity retainedRow = row;
                if (isRetractedByStabilitySkip(forecastRunInstant(row), skipAt)) {
                    retractedLocationsByKey.computeIfAbsent(key, k -> new HashSet<>())
                            .add(loc.getName());
                    retainedRow = null;
                }
                BriefingEvaluationResult resolved = resolveForEnrichment(loc.getName(),
                        regionMap.get(loc.getName()), cachedEvaluatedAtByKey.get(key), retainedRow);
                resolved = stampForced(resolved, forcedFlags.get(slotKey));
                if (resolved != null) {
                    regionMap.put(loc.getName(), resolved);
                }
            }
        }

        // A location recorded as retracted by either step above ends up here only if nothing else
        // — a fresher cache entry, a fresher forecast row — already resolved a value for it: the
        // marker must never displace a later real evaluation. This is also where a location whose
        // ONLY evidence was a (now-retracted) cache entry and which carries no forecast_evaluation
        // row at all gets its outcome at all, since step 2 never visits a location with no row.
        for (Map.Entry<String, Set<String>> entry : retractedLocationsByKey.entrySet()) {
            Map<String, BriefingEvaluationResult> regionMap =
                    byKey.computeIfAbsent(entry.getKey(), k -> new HashMap<>());
            for (String locationName : entry.getValue()) {
                regionMap.putIfAbsent(locationName, BriefingEvaluationResult.retracted(locationName));
            }
        }

        return byKey;
    }

    /**
     * {@link #resolveForEnrichment}, but returning {@link BriefingEvaluationResult#retracted} —
     * rather than {@code null} — when nothing survived BECAUSE a stability skip retracted it.
     *
     * <p><b>Why the distinction matters here and not on the view path.</b>
     * {@code BriefingRegionEvaluationRollup.enrichSlot} treats a {@code null} map entry as "nothing
     * new to say" and leaves the slot's own fields — including any {@code claudeRating} embedded
     * in it from the LAST briefing build — untouched. A genuinely uncovered slot (never enriched,
     * outside this resolver's window) and a slot whose one-time rating has since been retracted
     * both currently resolve to {@code null} here, and the rollup cannot act on one without acting
     * on the other unless the two are told apart at the source. {@link #mergeToView} has no
     * equivalent trap — a fresh {@code LocationEvaluationView} is built on every call regardless,
     * so {@code Source.NONE} already means "nothing" correctly there and needs no third state.
     *
     * @param locationName          the location this result is about
     * @param cachedResult          the cached entry for it, or null when the cache does not cover it
     * @param cachedEvaluatedAt     when the cache entry was written, or null when unknown
     * @param forecastRow           that location's latest forecast row for the slot, or null
     * @param latestStabilitySkipAt the slot's most recent stability skip instant, or null
     * @return the winning result, {@link BriefingEvaluationResult#retracted} when a skip is why
     *         nothing survived, or {@code null} when there was genuinely nothing to resolve
     */
    private static BriefingEvaluationResult resolveForEnrichmentRetractionAware(
            String locationName, BriefingEvaluationResult cachedResult, Instant cachedEvaluatedAt,
            ForecastEvaluationEntity forecastRow, Instant latestStabilitySkipAt) {
        boolean cacheStale = cachedResult != null && isRetractedByStabilitySkip(
                cacheWriteTime(cachedResult, cachedEvaluatedAt), latestStabilitySkipAt);
        boolean forecastStale = isRetractedByStabilitySkip(
                forecastRunInstant(forecastRow), latestStabilitySkipAt);
        BriefingEvaluationResult resolved = resolveForEnrichment(locationName,
                cacheStale ? null : cachedResult, cachedEvaluatedAt,
                forecastStale ? null : forecastRow);
        if (resolved == null && (cacheStale || forecastStale)) {
            return BriefingEvaluationResult.retracted(locationName);
        }
        return resolved;
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
     * <p>Public so {@code ForecastController} can gate its own stability-skip retraction on the
     * same predicate: a row with neither a rating nor a triage reason was never itself an opinion
     * a reader could see as "the rating", so a stability skip predating it has nothing to retract
     * — exactly the reasoning that already lets {@link #cachedWins}'s clause 2 leave such a row
     * alone. Dropping such a row anyway would not restore any "never-rated" behaviour, because one
     * cannot occur for a SUNRISE/SUNSET slot on the path {@code ForecastTaskCollector} writes: the
     * only rows the batch collector persists without a rating are triage rows, which always carry
     * a reason.
     *
     * @param row the row, or null
     * @return true when the row says something a reader could act on
     */
    public static boolean hasSomethingToSay(ForecastEvaluationEntity row) {
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
            TargetType targetType, BriefingEvaluationResult cachedResult,
            Map<String, Instant> stabilitySkips) {
        // Check forecast_evaluation as fallback
        ForecastEvaluationEntity forecastRow = forecastEvaluationRepository
                .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                        loc.getId(), date, targetType, PageRequest.of(0, 1))
                .stream().findFirst()
                .orElse(null);

        Long regionId = loc.getRegion() != null ? loc.getRegion().getId() : null;
        String regionName = loc.getRegion() != null ? loc.getRegion().getName() : null;
        Instant cachedEvaluatedAt = (regionName != null && cachedResult != null)
                ? briefingEvaluationService.getCachedEvaluatedAt(regionName, date, targetType)
                        .orElse(null)
                : null;
        Instant latestStabilitySkipAt =
                stabilitySkips.get(stabilitySkipKey(loc.getName(), date, targetType));
        return withLight(mergeToView(loc.getId(), loc.getName(), regionId, regionName, date,
                targetType, cachedResult, cachedEvaluatedAt, forecastRow, latestStabilitySkipAt),
                loc);
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
     *
     * @param latestStabilitySkipAt the slot's most recent {@code SKIPPED_STABILITY} disposition
     *                              instant, or {@code null} when it has none — see
     *                              {@link #retractStaleEvidence}
     */
    private LocationEvaluationView mergeToView(Long locationId, String locationName,
            Long regionId, String regionName, LocalDate date, TargetType targetType,
            BriefingEvaluationResult cachedResult, Instant cachedEvaluatedAt,
            ForecastEvaluationEntity forecastRow, Instant latestStabilitySkipAt) {

        // A nightly stability skip decided after this evidence was written outdates it, applied to
        // both sources independently before any precedence rule below sees them — see the class
        // javadoc's note on why this runs before, not inside, `cachedWins`.
        cachedResult = retractStaleEvidence(cachedResult, cachedEvaluatedAt, latestStabilitySkipAt);
        forecastRow = retractStaleForecastRow(forecastRow, latestStabilitySkipAt);

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
     * <p>{@code forecast_run_at} is a naive {@code LocalDateTime} — the ONE and ONLY write site,
     * {@code ForecastService.buildEntity}, has stamped it {@code LocalDateTime.now(ZoneOffset.UTC)}
     * since the column's introduction (commit b3284f52, 2026-02-24; confirmed by {@code git blame}
     * — the line has never changed) and the batch result handler never bumps it when scoring a
     * PENDING row in place ("submit-time, not score-time" — see {@code ForecastResultHandler}'s own
     * javadoc). It must therefore be zoned as {@link ZoneOffset#UTC}, not {@code Europe/London},
     * before it can be compared with an {@link Instant}.
     *
     * <p>⚠️ <b>This zoned as London until a Codex review of #940 caught it.</b> The wrong zone made
     * every forecast row read one hour OLDER than it actually was during BST, which both skewed the
     * pre-existing {@link #cachedIsAtLeastAsFresh} freshness gate (a cached rating written up to an
     * hour before a later triage row could wrongly outrank it) and, once the stability-skip
     * retraction rule started comparing this instant against a true {@code Instant} from {@code
     * forecast_run_disposition}, could wrongly retract a row written shortly AFTER a skip because it
     * read as shortly before it instead.
     *
     * <p>Public so {@code ForecastController} can apply {@link #isRetractedByStabilitySkip} to the
     * raw rows it reads directly from {@code forecast_evaluation} for {@code GET /api/forecast} —
     * the one serve path that bypasses {@link #mergeToView} for a slot a persisted row already
     * covers — using the exact same zoning this class applies everywhere else.
     *
     * @param forecastRow the row, or null
     * @return the instant the forecast ran, or null
     */
    public static Instant forecastRunInstant(ForecastEvaluationEntity forecastRow) {
        if (forecastRow == null || forecastRow.getForecastRunAt() == null) {
            return null;
        }
        return forecastRow.getForecastRunAt().toInstant(ZoneOffset.UTC);
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
