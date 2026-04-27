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
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final ForecastCommandExecutor forecastCommandExecutor;

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
     * @param forecastCommandExecutor   provides the latest stability snapshot
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
            ForecastCommandExecutor forecastCommandExecutor,
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
        this.forecastCommandExecutor = forecastCommandExecutor;
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

        List<EvaluationTask.Forecast> nearInland = new ArrayList<>();
        List<EvaluationTask.Forecast> nearCoastal = new ArrayList<>();
        List<EvaluationTask.Forecast> farInland = new ArrayList<>();
        List<EvaluationTask.Forecast> farCoastal = new ArrayList<>();
        Map<String, GridCellStabilityResult> stabilityByCell = new HashMap<>();

        int skippedTriage = 0;
        int skippedStability = 0;
        int skippedError = 0;
        int includedNear = 0;
        int includedFar = 0;

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
                int maxDays = getStabilityWindowDays(
                        candidate.location(), preEval, stabilityByCell);
                if (daysAhead > maxDays) {
                    LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=STABILITY "
                                    + "T+{}d maxDays={}",
                            candidate.location().getName(), candidate.date(),
                            candidate.targetType(), daysAhead, maxDays);
                    skippedStability++;
                    continue;
                }

                boolean isNearTerm = daysAhead <= NEAR_TERM_MAX_DAYS;
                EvaluationModel model = isNearTerm ? nearTermModel : farTermModel;
                EvaluationTask.Forecast eval = new EvaluationTask.Forecast(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        model, preEval.atmosphericData());
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

        if (totalIncluded == 0) {
            LOG.info("Forecast batch: no evaluable locations after triage, skipping submission");
            return ScheduledBatchTasks.empty();
        }

        return new ScheduledBatchTasks(nearInland, nearCoastal, farInland, farCoastal);
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
                int maxDays = getStabilityWindowDays(
                        candidate.location(), preEval, stabilityByCell);
                if (daysAhead > maxDays) {
                    continue;
                }
                EvaluationTask.Forecast eval = new EvaluationTask.Forecast(
                        candidate.location(), candidate.date(), candidate.targetType(),
                        model, preEval.atmosphericData());
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
                        if (slot.verdict() != Verdict.GO && slot.verdict() != Verdict.MARGINAL) {
                            LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | "
                                            + "reason=VERDICT_{}", slot.locationName(),
                                    date, targetType, slot.verdict());
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

    private int getStabilityWindowDays(LocationEntity location, ForecastPreEvalResult preEval,
            Map<String, GridCellStabilityResult> stabilityByCell) {
        if (!location.hasGridCell() || preEval.forecastResponse() == null) {
            return 1;
        }
        String key = location.gridCellKey();
        GridCellStabilityResult stability = stabilityByCell.computeIfAbsent(key, k ->
                stabilityClassifier.classify(
                        key, location.getGridLat(), location.getGridLng(),
                        preEval.forecastResponse().getHourly()));
        return stability != null ? Math.min(stability.evaluationWindowDays(), 3) : 1;
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
        StabilitySummaryResponse snapshot = forecastCommandExecutor.getLatestStabilitySummary();
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
}
