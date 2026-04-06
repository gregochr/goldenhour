package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Orchestrates per-region Claude evaluations from the briefing drill-down and manages
 * an in-memory score cache keyed by region+date+targetType.
 *
 * <p>Results are streamed back via SSE as each location completes. The cache is
 * invalidated when the briefing refreshes (via {@link BriefingRefreshedEvent}).
 */
@Service
public class BriefingEvaluationService {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingEvaluationService.class);

    private final LocationService locationService;
    private final BriefingService briefingService;
    private final ForecastService forecastService;
    private final ModelSelectionService modelSelectionService;
    private final JobRunService jobRunService;

    /** Outer key: "regionName|date|targetType", value: cached entry with results + timestamp. */
    private final ConcurrentHashMap<String, CachedEvaluation> cache = new ConcurrentHashMap<>();

    private static final DateTimeFormatter UK_TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.of("Europe/London"));

    /**
     * Cached evaluation results for a region/date/targetType.
     */
    record CachedEvaluation(
            ConcurrentHashMap<String, BriefingEvaluationResult> results,
            Instant evaluatedAt
    ) {
    }

    /**
     * Constructs a {@code BriefingEvaluationService}.
     *
     * @param locationService       service for retrieving enabled locations
     * @param briefingService       service for the cached briefing
     * @param forecastService       service for weather fetch, triage, and Claude evaluation
     * @param modelSelectionService service for resolving the active Claude model
     * @param jobRunService         service for job run tracking
     */
    public BriefingEvaluationService(LocationService locationService,
            BriefingService briefingService,
            ForecastService forecastService,
            ModelSelectionService modelSelectionService,
            JobRunService jobRunService) {
        this.locationService = locationService;
        this.briefingService = briefingService;
        this.forecastService = forecastService;
        this.modelSelectionService = modelSelectionService;
        this.jobRunService = jobRunService;
    }

    /**
     * Evaluates GO/MARGINAL locations for a region/date/targetType, streaming results via SSE.
     *
     * <p>If cached results exist, they are emitted as rapid-fire events (no Claude calls).
     * Otherwise, locations are evaluated sequentially with the configured SHORT_TERM model.
     *
     * @param regionName the region to evaluate
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @param emitter    the SSE emitter to send events to
     */
    public void evaluateRegion(String regionName, LocalDate date, TargetType targetType,
            SseEmitter emitter) {
        String cacheKey = regionName + "|" + date + "|" + targetType;

        // Track client disconnect so we stop submitting Claude calls
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));

        // Cache hit — emit stored results rapidly
        CachedEvaluation cached = cache.get(cacheKey);
        if (cached != null && !cached.results().isEmpty()) {
            LOG.info("Briefing evaluation cache hit for {}", cacheKey);
            emitCachedResults(cached, emitter, regionName, date, targetType);
            return;
        }

        // Find locations in this region
        List<LocationEntity> regionLocations = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getRegion() != null && loc.getRegion().getName().equals(regionName))
                .filter(briefingService::isColourLocation)
                .toList();

        // Filter to GO/MARGINAL slots from the cached briefing
        Set<String> evaluableNames = getEvaluableLocationNames(regionName, date, targetType);
        List<LocationEntity> toEvaluate = regionLocations.stream()
                .filter(loc -> evaluableNames.contains(loc.getName()))
                .toList();

        if (toEvaluate.isEmpty()) {
            LOG.info("No evaluable locations for {}", cacheKey);
            sendSafe(emitter, "evaluation-complete",
                    Map.of("completed", 0, "total", 0, "failed", 0,
                            "regionName", regionName, "date", date.toString(),
                            "targetType", targetType.name()));
            completeEmitter(emitter);
            return;
        }

        // Create a job run for cost tracking
        EvaluationModel model = modelSelectionService.getActiveModel(RunType.SHORT_TERM);
        JobRunEntity jobRun = jobRunService.startRun(RunType.SHORT_TERM, true, model);

        ConcurrentHashMap<String, BriefingEvaluationResult> results = new ConcurrentHashMap<>();

        int total = toEvaluate.size();
        int completed = 0;
        int failed = 0;

        for (LocationEntity location : toEvaluate) {
            if (cancelled.get()) {
                LOG.info("Client disconnected — stopping evaluation for {} ({}/{} done)",
                        cacheKey, completed + failed, total);
                break;
            }

            try {
                BriefingEvaluationResult result = evaluateSingleLocation(
                        location, date, targetType, model, jobRun);
                results.put(location.getName(), result);
                completed++;
                sendSafe(emitter, "location-scored", result);
            } catch (Exception e) {
                failed++;
                LOG.warn("Briefing evaluation failed for {}: {}", location.getName(), e.getMessage());
                sendSafe(emitter, "evaluation-error",
                        Map.of("locationName", location.getName(), "error", e.getMessage()));
            }

            sendSafe(emitter, "progress",
                    Map.of("completed", completed + failed, "total", total, "failed", failed));
        }

        jobRunService.completeRun(jobRun, completed, failed, List.of(date));

        // Only cache if we completed all locations (not cancelled mid-stream)
        Instant evaluatedAt = Instant.now();
        if (!cancelled.get()) {
            cache.put(cacheKey, new CachedEvaluation(results, evaluatedAt));
        }

        sendSafe(emitter, "evaluation-complete",
                Map.of("completed", completed, "total", total, "failed", failed,
                        "regionName", regionName, "date", date.toString(),
                        "targetType", targetType.name(),
                        "evaluatedAt", UK_TIME.format(evaluatedAt)));
        completeEmitter(emitter);

        LOG.info("Briefing evaluation complete for {}: {}/{} succeeded, {} failed",
                cacheKey, completed, total, failed);
    }

    /**
     * Returns cached evaluation scores for the given region/date/targetType, or an empty map.
     *
     * @param regionName the region name
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return map of locationName to evaluation result
     */
    public Map<String, BriefingEvaluationResult> getCachedScores(String regionName,
            LocalDate date, TargetType targetType) {
        String cacheKey = regionName + "|" + date + "|" + targetType;
        CachedEvaluation cached = cache.get(cacheKey);
        return cached != null ? Collections.unmodifiableMap(cached.results()) : Map.of();
    }

    /**
     * Returns the UK-formatted evaluation time for cached results, or null if not cached.
     *
     * @param regionName the region name
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return formatted time string (e.g. "14:32") or null
     */
    public String getCachedEvaluatedAt(String regionName, LocalDate date, TargetType targetType) {
        String cacheKey = regionName + "|" + date + "|" + targetType;
        CachedEvaluation cached = cache.get(cacheKey);
        return cached != null ? UK_TIME.format(cached.evaluatedAt()) : null;
    }

    /**
     * Writes batch-evaluated results directly into the cache for a given region/date/targetType.
     *
     * <p>Called by {@code BatchResultProcessor} after successfully fetching completed batch results
     * from Anthropic. The cache key format matches the SSE path exactly so subsequent SSE requests
     * return from cache rather than re-invoking Claude.
     *
     * @param cacheKey the cache key in the format "regionName|date|targetType"
     * @param results  the evaluation results to store
     */
    public void writeFromBatch(String cacheKey,
            List<BriefingEvaluationResult> results) {
        ConcurrentHashMap<String, BriefingEvaluationResult> resultMap = new ConcurrentHashMap<>();
        results.forEach(r -> resultMap.put(r.locationName(), r));
        cache.put(cacheKey, new CachedEvaluation(resultMap, Instant.now()));
        LOG.info("Batch results written to evaluation cache for key: {} ({} results)",
                cacheKey, results.size());
    }

    /**
     * Clears all cached evaluation results. Called when the briefing refreshes.
     */
    public void clearCache() {
        int size = cache.size();
        cache.clear();
        if (size > 0) {
            LOG.info("Briefing evaluation cache cleared ({} entries)", size);
        }
    }

    /**
     * Listens for briefing refresh events and clears the evaluation cache.
     *
     * @param event the briefing refreshed event
     */
    @EventListener
    public void onBriefingRefreshed(BriefingRefreshedEvent event) {
        clearCache();
    }

    private BriefingEvaluationResult evaluateSingleLocation(LocationEntity location,
            LocalDate date, TargetType targetType, EvaluationModel model, JobRunEntity jobRun) {
        ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                location, date, targetType, location.getTideType(), model, false, jobRun);

        if (preEval.triaged()) {
            return new BriefingEvaluationResult(
                    location.getName(), 1, 5, 5,
                    "Conditions unsuitable — " + preEval.triageReason());
        }

        ForecastEvaluationEntity entity = forecastService.evaluateAndPersist(preEval, jobRun);
        return new BriefingEvaluationResult(
                location.getName(),
                entity.getRating(),
                entity.getFierySkyPotential(),
                entity.getGoldenHourPotential(),
                entity.getSummary());
    }

    /**
     * Returns the set of location names with GO or MARGINAL verdict for the given cell.
     */
    private Set<String> getEvaluableLocationNames(String regionName, LocalDate date,
            TargetType targetType) {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            return Set.of();
        }

        return briefing.days().stream()
                .filter(day -> day.date().equals(date))
                .flatMap(day -> day.eventSummaries().stream())
                .filter(es -> es.targetType() == targetType)
                .flatMap(es -> es.regions().stream())
                .filter(region -> region.regionName().equals(regionName))
                .flatMap(region -> region.slots().stream())
                .filter(slot -> slot.verdict() == Verdict.GO || slot.verdict() == Verdict.MARGINAL)
                .map(BriefingSlot::locationName)
                .collect(Collectors.toSet());
    }

    private void emitCachedResults(CachedEvaluation cached,
            SseEmitter emitter, String regionName, LocalDate date, TargetType targetType) {
        int total = cached.results().size();
        int i = 0;
        for (BriefingEvaluationResult result : cached.results().values()) {
            sendSafe(emitter, "location-scored", result);
            i++;
            sendSafe(emitter, "progress", Map.of("completed", i, "total", total, "failed", 0));
        }
        sendSafe(emitter, "evaluation-complete",
                Map.of("completed", total, "total", total, "failed", 0,
                        "regionName", regionName, "date", date.toString(),
                        "targetType", targetType.name(),
                        "evaluatedAt", UK_TIME.format(cached.evaluatedAt())));
        completeEmitter(emitter);
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            LOG.debug("Emitter already completed: {}", e.getMessage());
        }
    }

    private void sendSafe(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            LOG.debug("SSE send failed for event '{}': {}", eventName, e.getMessage());
        }
    }
}
