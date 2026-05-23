package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingGatingPolicy;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Collects forecast evaluation tasks from the cached daily briefing and applies
 * triage, stability, and prefetch gates so the result is ready for direct
 * submission via {@link com.gregochr.goldenhour.service.evaluation.EvaluationService}.
 *
 * <p>Extracted from {@link ScheduledBatchEvaluationService} in v2.12.5 (Pass 3.2.1)
 * so the scheduled service shrinks to its two real responsibilities — concurrency
 * guarding and submission — while task-construction concerns (briefing read,
 * weather pre-fetch, cloud-point pre-fetch, per-task triage, stability gating,
 * near/far × inland/coastal bucketing) live behind this collaborator.
 *
 * <p>The collector exposes two methods, mirroring the two distinct flows in
 * {@code ScheduledBatchEvaluationService}:
 * <ul>
 *   <li>{@link #collectScheduledBatches()} — full overnight flow with near/far
 *       split (model per tier), four-bucket result, and the
 *       {@code minPrefetchSuccessRatio} gate that aborts if too many weather
 *       pre-fetches fail.</li>
 *   <li>{@link #collectRegionFilteredBatches(List)} — admin region-filtered flow
 *       using {@code BATCH_NEAR_TERM} for all tasks, two-bucket result, and a
 *       weaker prefetch gate (aborts only on zero results, not on partial
 *       degradation — preserving legacy admin-trigger behaviour).</li>
 * </ul>
 *
 * <p>The collector is stateless and Spring-singleton; concurrent invocation is
 * safe and the caller is responsible for serialising entry via the
 * {@code forecastBatchRunning} guard in {@link ScheduledBatchEvaluationService}.
 *
 * <p>All {@code [BATCH DIAG]} candidate-loop logging produced by the legacy
 * paths is preserved verbatim inside this class; per-bucket submission logs
 * remain at the call site.
 */
@Service
public class ForecastTaskCollector {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastTaskCollector.class);

    /**
     * The days-ahead threshold separating near-term from far-term batches.
     * Tasks with {@code daysAhead <= NEAR_TERM_MAX_DAYS} go to the near-term
     * batch; tasks with {@code daysAhead > NEAR_TERM_MAX_DAYS} go to the
     * far-term batch (subject to stability gating).
     */
    static final int NEAR_TERM_MAX_DAYS = 1;

    private final LocationService locationService;
    private final BriefingService briefingService;
    private final BriefingEvaluationService briefingEvaluationService;
    private final ForecastService forecastService;
    private final ForecastStabilityClassifier stabilityClassifier;
    private final ModelSelectionService modelSelectionService;
    private final OpenMeteoService openMeteoService;
    private final SolarService solarService;
    private final FreshnessResolver freshnessResolver;
    private final StabilitySnapshotProvider stabilitySnapshotProvider;

    /** Minimum ratio of successful weather pre-fetches to proceed (scheduled path only). */
    private final double minPrefetchSuccessRatio;

    /**
     * Constructs the collector.
     *
     * @param locationService           service for retrieving enabled locations
     * @param briefingService           cached daily briefing accessor
     * @param briefingEvaluationService evaluation cache (read-only — freshness check)
     * @param forecastService           weather fetch + triage
     * @param stabilityClassifier       per-grid-cell stability classifier
     * @param modelSelectionService     resolves the active Claude model per run type
     * @param openMeteoService          bulk weather + cloud-point pre-fetch
     * @param solarService              solar azimuth and event time helpers
     * @param freshnessResolver         per-stability cache freshness thresholds
     * @param stabilitySnapshotProvider provides the latest stability snapshot
     * @param minPrefetchSuccessRatio   minimum prefetch ratio to proceed (scheduled path)
     */
    public ForecastTaskCollector(LocationService locationService,
            BriefingService briefingService,
            BriefingEvaluationService briefingEvaluationService,
            ForecastService forecastService,
            ForecastStabilityClassifier stabilityClassifier,
            ModelSelectionService modelSelectionService,
            OpenMeteoService openMeteoService,
            SolarService solarService,
            FreshnessResolver freshnessResolver,
            StabilitySnapshotProvider stabilitySnapshotProvider,
            @Value("${photocast.batch.min-prefetch-success-ratio:0.5}")
            double minPrefetchSuccessRatio) {
        this.locationService = locationService;
        this.briefingService = briefingService;
        this.briefingEvaluationService = briefingEvaluationService;
        this.forecastService = forecastService;
        this.stabilityClassifier = stabilityClassifier;
        this.modelSelectionService = modelSelectionService;
        this.openMeteoService = openMeteoService;
        this.solarService = solarService;
        this.freshnessResolver = freshnessResolver;
        this.stabilitySnapshotProvider = stabilitySnapshotProvider;
        this.minPrefetchSuccessRatio = minPrefetchSuccessRatio;
    }

    /**
     * Collects scheduled forecast tasks bucketed by near/far × inland/coastal.
     *
     * <p>Returns an all-empty {@link ScheduledBatchTasks} value when:
     * <ul>
     *   <li>no cached briefing is available,</li>
     *   <li>no candidate slots survive the first pass (past dates, cached regions,
     *       non-eligible verdicts, unknown locations),</li>
     *   <li>weather pre-fetch returns zero results, or</li>
     *   <li>weather pre-fetch ratio falls below {@code minPrefetchSuccessRatio}.</li>
     * </ul>
     * Each abort path logs a clearly distinguishable reason at WARN/ERROR level so
     * operators can tell "no work this cycle" apart from "upstream degradation".
     *
     * @return bucketed tasks (possibly all-empty)
     */
    public ScheduledBatchTasks collectScheduledBatches() {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            LOG.warn("[BATCH DIAG] Forecast batch skipped: no cached briefing available");
            return ScheduledBatchTasks.empty();
        }

        EvaluationModel nearTermModel =
                modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM);
        EvaluationModel farTermModel =
                modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM);
        LOG.warn("[BATCH DIAG] Starting forecast batch — nearTermModel={}, farTermModel={}, "
                        + "briefing days={}",
                nearTermModel, farTermModel,
                briefing.days() != null ? briefing.days().size() : 0);

        List<ForecastCandidate> candidates = collectForecastCandidates(briefing);
        if (candidates.isEmpty()) {
            LOG.warn("[BATCH DIAG] Forecast batch: no evaluable locations found after "
                    + "task collection, skipping submission");
            return ScheduledBatchTasks.empty();
        }

        LOG.info("Forecast batch: {} candidate task(s) — bulk pre-fetching weather",
                candidates.size());

        int uniqueLocationCount = countUniqueLocations(candidates);
        Map<String, WeatherExtractionResult> prefetchedWeather =
                prefetchBatchWeather(candidates);
        double successRatio = uniqueLocationCount > 0
                ? (double) prefetchedWeather.size() / uniqueLocationCount : 0.0;
        if (prefetchedWeather.isEmpty()) {
            LOG.error("Forecast batch: weather pre-fetch returned 0/{} locations "
                    + "— aborting (likely Open-Meteo outage)", uniqueLocationCount);
            return ScheduledBatchTasks.empty();
        }
        if (successRatio < minPrefetchSuccessRatio) {
            LOG.error("Forecast batch: weather pre-fetch too degraded — {}/{} locations "
                            + "(ratio {}, threshold {}) — aborting (likely Open-Meteo outage)",
                    prefetchedWeather.size(), uniqueLocationCount,
                    String.format("%.2f", successRatio),
                    String.format("%.2f", minPrefetchSuccessRatio));
            return ScheduledBatchTasks.empty();
        }
        if (prefetchedWeather.size() < uniqueLocationCount) {
            LOG.warn("Forecast batch: weather pre-fetch partial — {}/{} locations fetched, "
                            + "continuing with available data",
                    prefetchedWeather.size(), uniqueLocationCount);
        }

        CloudPointCache cloudCache;
        try {
            cloudCache = prefetchBatchCloudPoints(candidates, prefetchedWeather);
        } catch (Exception e) {
            LOG.warn("Forecast batch: cloud pre-fetch failed — continuing without cloud cache: {}",
                    e.getMessage());
            cloudCache = null;
        }

        Map<String, GridCellStabilityResult> stabilityByCell =
                classifyGridCellsAndPublishSnapshot(candidates, prefetchedWeather);

        List<EvaluationTask.Forecast> nearInland = new ArrayList<>();
        List<EvaluationTask.Forecast> nearCoastal = new ArrayList<>();
        List<EvaluationTask.Forecast> farInland = new ArrayList<>();
        List<EvaluationTask.Forecast> farCoastal = new ArrayList<>();

        int skippedTriage = 0;
        int skippedStability = 0;
        int skippedError = 0;
        int includedNear = 0;
        int includedFar = 0;
        EligibilityAggregator agg = new EligibilityAggregator();

        LOG.warn("[BATCH DIAG] Starting triage loop — {} candidate tasks", candidates.size());

        for (ForecastCandidate candidate : candidates) {
            try {
                ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        candidate.location().getTideType(), nearTermModel, false, null,
                        prefetchedWeather, cloudCache);
                if (preEval.triaged()) {
                    LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=TRIAGED ({})",
                            candidate.location().getName(), candidate.date(),
                            candidate.targetType(), preEval.triageReason());
                    skippedTriage++;
                    continue;
                }
                int daysAhead = preEval.daysAhead();
                ForecastStability stability = stabilityFor(
                        candidate.location(), preEval, stabilityByCell);
                EligibilityDecision decision = resolveEligibility(
                        daysAhead, stability, nearTermModel, farTermModel);
                if (!decision.eligible()) {
                    LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=STABILITY ({})",
                            candidate.location().getName(), candidate.date(),
                            candidate.targetType(), decision.skipReason());
                    agg.recordExcluded(daysAhead, stability);
                    skippedStability++;
                    continue;
                }

                boolean isNearTerm = daysAhead <= NEAR_TERM_MAX_DAYS;
                EvaluationTask.Forecast eval = new EvaluationTask.Forecast(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        decision.model(), preEval.atmosphericData(),
                        EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
                boolean isCoastal = preEval.atmosphericData().tide() != null;
                String locationType = isCoastal ? "coastal" : "inland";

                if (isNearTerm) {
                    if (isCoastal) {
                        nearCoastal.add(eval);
                    } else {
                        nearInland.add(eval);
                    }
                    includedNear++;
                } else {
                    if (isCoastal) {
                        farCoastal.add(eval);
                    } else {
                        farInland.add(eval);
                    }
                    includedFar++;
                }
                agg.recordIncluded(daysAhead, stability);
                LOG.warn("[BATCH DIAG] INCLUDE {} | date={} event={} | tier={} type={}",
                        candidate.location().getName(), candidate.date(),
                        candidate.targetType(),
                        isNearTerm ? "near" : "far", locationType);
            } catch (Exception e) {
                LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=ERROR ({})",
                        candidate.location().getName(), candidate.date(),
                        candidate.targetType(), e.getMessage());
                skippedError++;
            }
        }

        int totalIncluded = includedNear + includedFar;
        LOG.warn("[BATCH DIAG] Triage complete — {} included (near={}, far={}), {} skipped "
                        + "(triage={}, stability={}, error={})",
                totalIncluded, includedNear, includedFar,
                skippedTriage + skippedStability + skippedError,
                skippedTriage, skippedStability, skippedError);
        LOG.info("[BATCH ELIG] {}", agg.formatSummary());

        if (totalIncluded == 0) {
            LOG.info("Forecast batch: no evaluable locations after triage, skipping submission");
            return ScheduledBatchTasks.empty();
        }

        return new ScheduledBatchTasks(nearInland, nearCoastal, farInland, farCoastal);
    }

    /**
     * Encodes the Gate 4 batch eligibility policy: which (daysAhead, stability)
     * combinations enter the batch, and which model tier evaluates them.
     *
     * <table>
     *   <caption>Eligibility table</caption>
     *   <tr><th>daysAhead</th><th>Eligibility</th><th>Model tier</th></tr>
     *   <tr><td>T+0, T+1</td><td>all stabilities</td>
     *       <td>{@code BATCH_NEAR_TERM}</td></tr>
     *   <tr><td>T+2</td><td>SETTLED or TRANSITIONAL</td>
     *       <td>{@code BATCH_FAR_TERM}</td></tr>
     *   <tr><td>T+3</td><td>SETTLED only</td>
     *       <td>{@code BATCH_FAR_TERM}</td></tr>
     *   <tr><td>T+4 and beyond</td><td>never eligible</td><td>—</td></tr>
     * </table>
     *
     * <p>UNSETTLED cells from T+1 onward are not evaluated by the batch — they
     * remain triage-only. The policy is intentionally independent of
     * {@code ForecastStability.evaluationWindowDays()}, which is now a
     * display-only depth hint for the admin UI.
     *
     * @param daysAhead     forecast horizon (T+0 = 0)
     * @param stability     classified stability for the grid cell
     * @param nearTermModel resolved {@code BATCH_NEAR_TERM} model for this run
     * @param farTermModel  resolved {@code BATCH_FAR_TERM} model for this run
     * @return include-with-model or skip-with-reason
     */
    static EligibilityDecision resolveEligibility(int daysAhead, ForecastStability stability,
            EvaluationModel nearTermModel, EvaluationModel farTermModel) {
        return switch (daysAhead) {
            case 0, 1 -> EligibilityDecision.include(nearTermModel);
            case 2 -> (stability == ForecastStability.SETTLED
                       || stability == ForecastStability.TRANSITIONAL)
                    ? EligibilityDecision.include(farTermModel)
                    : EligibilityDecision.skip("T+2 " + stability);
            case 3 -> stability == ForecastStability.SETTLED
                    ? EligibilityDecision.include(farTermModel)
                    : EligibilityDecision.skip("T+3 " + stability);
            default -> EligibilityDecision.skip("T+" + daysAhead + " beyond horizon");
        };
    }

    /**
     * Resolves the stability classification for a candidate, with a
     * TRANSITIONAL fallback for tasks that lack a grid cell or a forecast
     * response (matches the pre-Gate-4 behaviour of allowing T+0/T+1 for
     * unclassified locations; under the new policy, TRANSITIONAL adds T+2
     * as well, which is acceptable for the rare unclassified case).
     */
    private ForecastStability stabilityFor(LocationEntity location,
            ForecastPreEvalResult preEval,
            Map<String, GridCellStabilityResult> stabilityByCell) {
        if (!location.hasGridCell() || preEval.forecastResponse() == null) {
            return ForecastStability.TRANSITIONAL;
        }
        String key = location.gridCellKey();
        GridCellStabilityResult stability = stabilityByCell.computeIfAbsent(key, k ->
                stabilityClassifier.classify(
                        key, location.getGridLat(), location.getGridLng(),
                        preEval.forecastResponse().getHourly()));
        return stability != null ? stability.stability() : ForecastStability.TRANSITIONAL;
    }

    /**
     * Collects region-filtered forecast tasks for an admin batch.
     *
     * <p>Uses {@code BATCH_NEAR_TERM} model for every task and produces a
     * simpler inland/coastal bucketing (no near/far split). Returns an
     * all-empty value when the briefing has no candidates after filtering or
     * weather pre-fetch yields zero results. Unlike the scheduled path, the
     * region-filtered admin path tolerates partial-prefetch degradation
     * (no ratio threshold) — mirroring legacy behaviour.
     *
     * @param regionIds region IDs to include — null or empty means all regions
     * @return inland/coastal tasks (possibly all-empty)
     */
    public RegionFilteredBatchTasks collectRegionFilteredBatches(List<Long> regionIds) {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            LOG.warn("[BATCH] Scheduled batch skipped: no cached briefing available");
            return RegionFilteredBatchTasks.empty();
        }

        Set<Long> regionFilter = (regionIds != null && !regionIds.isEmpty())
                ? new HashSet<>(regionIds) : null;

        EvaluationModel model = modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM);
        List<ForecastCandidate> candidates = collectForecastCandidates(briefing);

        if (regionFilter != null) {
            candidates = candidates.stream()
                    .filter(c -> c.location().getRegion() != null
                            && regionFilter.contains(c.location().getRegion().getId()))
                    .toList();
        }

        if (candidates.isEmpty()) {
            LOG.warn("[BATCH] No evaluable locations after region filtering");
            return RegionFilteredBatchTasks.empty();
        }

        Map<String, WeatherExtractionResult> prefetchedWeather =
                prefetchBatchWeather(candidates);
        if (prefetchedWeather.isEmpty()) {
            LOG.error("[BATCH] Weather pre-fetch returned zero results — aborting");
            return RegionFilteredBatchTasks.empty();
        }

        CloudPointCache cloudCache;
        try {
            cloudCache = prefetchBatchCloudPoints(candidates, prefetchedWeather);
        } catch (Exception e) {
            LOG.warn("[BATCH] Cloud pre-fetch failed — continuing without: {}", e.getMessage());
            cloudCache = null;
        }

        List<EvaluationTask.Forecast> inland = new ArrayList<>();
        List<EvaluationTask.Forecast> coastal = new ArrayList<>();
        Map<String, GridCellStabilityResult> stabilityByCell = new HashMap<>();

        for (ForecastCandidate candidate : candidates) {
            try {
                ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        candidate.location().getTideType(), model, false, null,
                        prefetchedWeather, cloudCache);
                if (preEval.triaged()) {
                    continue;
                }
                int daysAhead = preEval.daysAhead();
                ForecastStability stability = stabilityFor(
                        candidate.location(), preEval, stabilityByCell);
                // Region-filtered admin batches only ever use the near-term model
                // (legacy contract). Pass `model` as both tiers so the policy's
                // include branch lands with the right model regardless of horizon.
                EligibilityDecision decision = resolveEligibility(
                        daysAhead, stability, model, model);
                if (!decision.eligible()) {
                    continue;
                }
                EvaluationTask.Forecast eval = new EvaluationTask.Forecast(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        decision.model(), preEval.atmosphericData(),
                        EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
                if (preEval.atmosphericData().tide() != null) {
                    coastal.add(eval);
                } else {
                    inland.add(eval);
                }
            } catch (Exception e) {
                LOG.warn("[BATCH] Failed data assembly for {}: {}",
                        candidate.location().getName(), e.getMessage());
            }
        }

        return new RegionFilteredBatchTasks(inland, coastal);
    }

    /**
     * First pass over the briefing: collects all GO/MARGINAL slots that are not
     * already cached. No API calls are made here.
     */
    private List<ForecastCandidate> collectForecastCandidates(DailyBriefingResponse briefing) {
        List<ForecastCandidate> candidates = new ArrayList<>();
        int skippedCache = 0;
        int skippedVerdict = 0;
        int skippedUnknown = 0;
        int skippedPastDate = 0;
        int totalSlots = 0;
        Map<ForecastStability, int[]> cachedByStability = new HashMap<>();
        Map<ForecastStability, int[]> eligibleByStability = new HashMap<>();
        for (ForecastStability s : ForecastStability.values()) {
            cachedByStability.put(s, new int[]{0});
            eligibleByStability.put(s, new int[]{0});
        }

        Map<String, ForecastStability> stabilityByLocation = buildStabilityLookup();

        // Use Europe/London because solar events are for UK locations — a sunrise
        // in Northumberland on April 19th BST is what matters, not the UTC date.
        LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));

        for (BriefingDay day : briefing.days()) {
            LocalDate date = day.date();
            if (date.isBefore(today)) {
                int daySlots = day.eventSummaries().stream()
                        .flatMap(es -> es.regions().stream())
                        .mapToInt(r -> r.slots() != null ? r.slots().size() : 0)
                        .sum();
                skippedPastDate += daySlots;
                totalSlots += daySlots;
                LOG.warn("[BATCH DIAG] SKIP date {} | reason=PAST_DATE ({} slots skipped)",
                        date, daySlots);
                continue;
            }
            for (BriefingEventSummary eventSummary : day.eventSummaries()) {
                TargetType targetType = eventSummary.targetType();
                for (BriefingRegion region : eventSummary.regions()) {
                    String cacheKey = com.gregochr.goldenhour.service.evaluation.CacheKeyFactory
                            .build(region.regionName(), date, targetType);
                    ForecastStability regionStability = mostVolatileStability(
                            region, stabilityByLocation);
                    Duration freshness = freshnessResolver.maxAgeFor(regionStability);
                    int regionSlots = region.slots() != null ? region.slots().size() : 0;
                    eligibleByStability.get(regionStability)[0] += regionSlots;
                    if (briefingEvaluationService.hasFreshEvaluation(cacheKey, freshness)) {
                        LOG.warn("[BATCH DIAG] SKIP region {} | reason=CACHED "
                                        + "(stability={}, threshold={}h, {} slots skipped)",
                                cacheKey, regionStability,
                                freshness.toHours(), regionSlots);
                        cachedByStability.get(regionStability)[0] += regionSlots;
                        skippedCache += regionSlots;
                        totalSlots += regionSlots;
                        continue;
                    }
                    for (BriefingSlot slot : region.slots()) {
                        totalSlots++;
                        if (!BriefingGatingPolicy.isEligibleForEvaluation(slot)) {
                            LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | "
                                            + "reason=VERDICT_{} ({})",
                                    slot.locationName(), date, targetType,
                                    slot.verdict(), slot.standdownReason());
                            skippedVerdict++;
                            continue;
                        }
                        LocationEntity location = findLocation(slot.locationName());
                        if (location == null) {
                            LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | "
                                            + "reason=UNKNOWN_LOCATION",
                                    slot.locationName(), date, targetType);
                            skippedUnknown++;
                            continue;
                        }
                        candidates.add(new ForecastCandidate(location, date, targetType));
                    }
                }
            }
        }

        LOG.warn("[BATCH DIAG] Task collection complete — {} tasks from {} total slots "
                        + "(pastDate={}, cached={}, verdict={}, unknownLoc={})",
                candidates.size(), totalSlots, skippedPastDate, skippedCache,
                skippedVerdict, skippedUnknown);
        logStabilityBreakdown(eligibleByStability, cachedByStability);
        return candidates;
    }

    private int countUniqueLocations(List<ForecastCandidate> candidates) {
        Set<String> seen = new HashSet<>();
        for (ForecastCandidate c : candidates) {
            seen.add(OpenMeteoService.coordKey(c.location().getLat(),
                    c.location().getLon()));
        }
        return seen.size();
    }

    private Map<String, WeatherExtractionResult> prefetchBatchWeather(
            List<ForecastCandidate> candidates) {
        Map<String, double[]> uniqueCoords = new LinkedHashMap<>();
        for (ForecastCandidate c : candidates) {
            String key = OpenMeteoService.coordKey(c.location().getLat(),
                    c.location().getLon());
            uniqueCoords.putIfAbsent(key,
                    new double[]{c.location().getLat(), c.location().getLon()});
        }
        LOG.info("Forecast batch: weather pre-fetch for {} unique location(s) (from {} tasks)",
                uniqueCoords.size(), candidates.size());
        Map<String, WeatherExtractionResult> result =
                openMeteoService.prefetchWeatherBatchResilient(
                        new ArrayList<>(uniqueCoords.values()));
        return result != null ? result : Map.of();
    }

    private CloudPointCache prefetchBatchCloudPoints(List<ForecastCandidate> candidates,
            Map<String, WeatherExtractionResult> prefetchedWeather) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<double[]> allPoints = new ArrayList<>();

        for (ForecastCandidate c : candidates) {
            double lat = c.location().getLat();
            double lon = c.location().getLon();
            LocalDate date = c.date();
            TargetType targetType = c.targetType();

            int azimuth = targetType == TargetType.SUNRISE
                    ? solarService.sunriseAzimuthDeg(lat, lon, date)
                    : solarService.sunsetAzimuthDeg(lat, lon, date);

            allPoints.addAll(openMeteoService.computeDirectionalCloudPoints(lat, lon, azimuth));

            if (prefetchedWeather != null) {
                String coordKey = OpenMeteoService.coordKey(lat, lon);
                WeatherExtractionResult cached = prefetchedWeather.get(coordKey);
                if (cached != null && cached.forecastResponse() != null
                        && cached.forecastResponse().getHourly() != null) {
                    LocalDateTime eventTime = targetType == TargetType.SUNRISE
                            ? solarService.sunriseUtc(lat, lon, date)
                            : solarService.sunsetUtc(lat, lon, date);
                    OpenMeteoForecastResponse.Hourly h = cached.forecastResponse().getHourly();
                    List<String> times = h.getTime();
                    if (times != null && h.getWindDirection10m() != null
                            && h.getWindSpeed10m() != null) {
                        int idx = TimeSlotUtils.findNearestIndex(times, eventTime);
                        if (idx < h.getWindDirection10m().size()
                                && idx < h.getWindSpeed10m().size()) {
                            Integer windDir = h.getWindDirection10m().get(idx);
                            Double windSpeed = h.getWindSpeed10m().get(idx);
                            if (windDir != null && windSpeed != null) {
                                double[] upwind = openMeteoService.computeUpwindPoint(
                                        lat, lon, windDir, windSpeed, now, eventTime);
                                if (upwind != null) {
                                    allPoints.add(upwind);
                                }
                            }
                        }
                    }
                }
            }
        }

        LOG.info("Forecast batch: cloud point pre-fetch — {} raw points from {} tasks",
                allPoints.size(), candidates.size());
        return openMeteoService.prefetchCloudBatch(allPoints, null);
    }

    /**
     * Classifies every unique grid cell touched by the candidate set, publishes the
     * resulting snapshot via {@link StabilitySnapshotProvider#update}, and returns the
     * classification map for reuse during the per-task triage loop.
     *
     * <p>This is the canonical producer of {@code stability_snapshot} rows for the
     * overnight scheduled flow — previously written by
     * {@code ForecastCommandExecutor.applyStabilityFilter} but stranded when that
     * path's {@code @Scheduled} trigger was commented out during the v2.12
     * consolidation. Without this write the reader at
     * {@link #buildStabilityLookup()} sees nothing in memory or in the database and
     * defaults every region to UNSETTLED, collapsing the stability gate.
     *
     * <p>Cells whose locations lack a grid assignment are skipped (the existing
     * triage-loop helper handles those by returning a 1-day window). If no cells
     * survive classification, no snapshot is published — the previously persisted
     * snapshot remains authoritative.
     *
     * <p>The persist itself is best-effort: see the failure semantics in
     * {@link StabilitySnapshotProvider}.
     *
     * @param candidates        surviving briefing candidates
     * @param prefetchedWeather coord-key → prefetched forecast/air-quality result
     * @return classification keyed by grid-cell key (empty if no cells classified)
     */
    private Map<String, GridCellStabilityResult> classifyGridCellsAndPublishSnapshot(
            List<ForecastCandidate> candidates,
            Map<String, WeatherExtractionResult> prefetchedWeather) {
        Map<String, GridCellStabilityResult> stabilityByCell = new LinkedHashMap<>();
        Map<String, Set<String>> locationsByCell = new LinkedHashMap<>();

        for (ForecastCandidate candidate : candidates) {
            LocationEntity loc = candidate.location();
            if (!loc.hasGridCell()) {
                continue;
            }
            String key = loc.gridCellKey();
            stabilityByCell.computeIfAbsent(key, k -> {
                String coordKey = OpenMeteoService.coordKey(loc.getLat(), loc.getLon());
                WeatherExtractionResult weather = prefetchedWeather.get(coordKey);
                OpenMeteoForecastResponse resp =
                        weather != null ? weather.forecastResponse() : null;
                return stabilityClassifier.classify(
                        key, loc.getGridLat(), loc.getGridLng(),
                        resp != null ? resp.getHourly() : null);
            });
            locationsByCell
                    .computeIfAbsent(key, k -> new LinkedHashSet<>())
                    .add(loc.getName());
        }

        if (stabilityByCell.isEmpty()) {
            LOG.warn("[STABILITY] No grid cells classified in this batch run "
                    + "— snapshot not written");
            return stabilityByCell;
        }

        Map<ForecastStability, Long> countsByStability = stabilityByCell.values().stream()
                .collect(Collectors.groupingBy(
                        GridCellStabilityResult::stability, Collectors.counting()));

        List<StabilitySummaryResponse.GridCellDetail> cellDetails =
                stabilityByCell.values().stream()
                        .map(r -> new StabilitySummaryResponse.GridCellDetail(
                                r.gridCellKey(), r.gridLat(), r.gridLng(),
                                r.stability(), r.reason(), r.evaluationWindowDays(),
                                List.copyOf(locationsByCell.getOrDefault(
                                        r.gridCellKey(), Set.of()))))
                        .sorted(Comparator.comparing(
                                StabilitySummaryResponse.GridCellDetail::gridCellKey))
                        .toList();

        StabilitySummaryResponse summary = new StabilitySummaryResponse(
                Instant.now(), stabilityByCell.size(), countsByStability, cellDetails);

        LOG.info("[STABILITY] Built snapshot for {} grid cells (counts: {})",
                stabilityByCell.size(), countsByStability);
        stabilitySnapshotProvider.update(summary);

        return stabilityByCell;
    }

    private LocationEntity findLocation(String name) {
        return locationService.findAllEnabled().stream()
                .filter(loc -> loc.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private void logStabilityBreakdown(Map<ForecastStability, int[]> eligible,
            Map<ForecastStability, int[]> cached) {
        StringBuilder sb = new StringBuilder("[BATCH DIAG] Candidate breakdown by stability:");
        for (ForecastStability level : ForecastStability.values()) {
            int elig = eligible.get(level)[0];
            int cach = cached.get(level)[0];
            if (elig == 0) {
                continue;
            }
            int refreshed = elig - cach;
            double pct = elig > 0 ? (refreshed * 100.0 / elig) : 0;
            Duration threshold = freshnessResolver.maxAgeFor(level);
            sb.append(String.format(" %s: %d of %d (%.1f%% refreshed, threshold %dh) |",
                    level, refreshed, elig, pct, threshold.toHours()));
        }
        if (sb.charAt(sb.length() - 1) == '|') {
            sb.setLength(sb.length() - 1);
        }
        LOG.warn("{}", sb);
    }

    private Map<String, ForecastStability> buildStabilityLookup() {
        StabilitySummaryResponse snapshot = stabilitySnapshotProvider.getLatestStabilitySummary();
        if (snapshot == null || snapshot.cells() == null) {
            LOG.warn("[BATCH DIAG] Stability snapshot unavailable — no snapshot in memory or DB, "
                    + "all regions treated as UNSETTLED ({}h threshold)",
                    freshnessResolver.maxAgeFor(ForecastStability.UNSETTLED).toHours());
            return Map.of();
        }
        long ageHours = java.time.temporal.ChronoUnit.HOURS.between(
                snapshot.generatedAt(), java.time.Instant.now());
        String source = ageHours > 12 ? "DB (recovered after restart)" : "in-memory";
        LOG.info("[BATCH DIAG] Stability snapshot loaded from {}: age={}h, {} grid cells",
                source, ageHours, snapshot.cells().size());
        Map<String, ForecastStability> lookup = new HashMap<>();
        for (StabilitySummaryResponse.GridCellDetail cell : snapshot.cells()) {
            for (String locName : cell.locationNames()) {
                lookup.put(locName, cell.stability());
            }
        }
        return lookup;
    }

    private ForecastStability mostVolatileStability(BriefingRegion region,
            Map<String, ForecastStability> stabilityByLocation) {
        if (region.slots() == null || region.slots().isEmpty()
                || stabilityByLocation.isEmpty()) {
            return ForecastStability.UNSETTLED;
        }
        ForecastStability most = ForecastStability.SETTLED;
        for (BriefingSlot slot : region.slots()) {
            ForecastStability slotStability = stabilityByLocation.getOrDefault(
                    slot.locationName(), ForecastStability.UNSETTLED);
            if (slotStability == ForecastStability.UNSETTLED) {
                return ForecastStability.UNSETTLED;
            }
            if (slotStability == ForecastStability.TRANSITIONAL) {
                most = ForecastStability.TRANSITIONAL;
            }
        }
        return most;
    }

    /**
     * Lightweight (location, date, targetType) triple emitted by the first
     * briefing pass before weather pre-fetch and triage are applied.
     */
    private record ForecastCandidate(LocationEntity location, LocalDate date,
            TargetType targetType) {
    }

    /**
     * Per-cycle counters that cross-tab included and excluded candidate counts
     * by {@code (daysAhead, stability)}. Used to emit the single-line
     * {@code [BATCH ELIG]} INFO summary that operators rely on to confirm Gate 4
     * is honouring its policy table after each scheduled run.
     */
    private static final class EligibilityAggregator {

        private final Map<Integer, EnumMap<ForecastStability, Integer>> included =
                new LinkedHashMap<>();
        private final Map<Integer, EnumMap<ForecastStability, Integer>> excluded =
                new LinkedHashMap<>();

        void recordIncluded(int daysAhead, ForecastStability stability) {
            bump(included, daysAhead, stability);
        }

        void recordExcluded(int daysAhead, ForecastStability stability) {
            bump(excluded, daysAhead, stability);
        }

        private static void bump(
                Map<Integer, EnumMap<ForecastStability, Integer>> sink,
                int daysAhead, ForecastStability stability) {
            sink.computeIfAbsent(daysAhead, k -> new EnumMap<>(ForecastStability.class))
                    .merge(stability, 1, Integer::sum);
        }

        /**
         * Formats a single readable line of the form:
         * {@code included T+0=5(S:3,T:1,U:1) T+2=18(S:12,T:6) | excluded T+2=1(U:1) T+3=4(T:3,U:1)}.
         */
        String formatSummary() {
            return "included " + formatBlock(included) + " | excluded " + formatBlock(excluded);
        }

        private static String formatBlock(
                Map<Integer, EnumMap<ForecastStability, Integer>> block) {
            if (block.isEmpty()) {
                return "none";
            }
            StringBuilder sb = new StringBuilder();
            block.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        int total = entry.getValue().values().stream()
                                .mapToInt(Integer::intValue).sum();
                        sb.append("T+").append(entry.getKey()).append('=').append(total);
                        sb.append('(');
                        boolean first = true;
                        for (Map.Entry<ForecastStability, Integer> e : entry.getValue().entrySet()) {
                            if (!first) {
                                sb.append(',');
                            }
                            sb.append(initial(e.getKey())).append(':').append(e.getValue());
                            first = false;
                        }
                        sb.append(')');
                    });
            return sb.toString();
        }

        private static char initial(ForecastStability stability) {
            return switch (stability) {
                case SETTLED      -> 'S';
                case TRANSITIONAL -> 'T';
                case UNSETTLED    -> 'U';
            };
        }
    }
}
