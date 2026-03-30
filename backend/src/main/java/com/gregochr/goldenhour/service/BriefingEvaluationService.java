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

import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Outer key: "regionName|date|targetType", inner key: locationName. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, BriefingEvaluationResult>> cache =
            new ConcurrentHashMap<>();

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

        // Cache hit — emit stored results rapidly
        ConcurrentHashMap<String, BriefingEvaluationResult> cached = cache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
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
            emitter.complete();
            return;
        }

        // Create a job run for cost tracking
        EvaluationModel model = modelSelectionService.getActiveModel(RunType.SHORT_TERM);
        JobRunEntity jobRun = jobRunService.startRun(RunType.SHORT_TERM, true, model);

        ConcurrentHashMap<String, BriefingEvaluationResult> results = new ConcurrentHashMap<>();
        cache.put(cacheKey, results);

        int total = toEvaluate.size();
        int completed = 0;
        int failed = 0;

        for (LocationEntity location : toEvaluate) {
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
        sendSafe(emitter, "evaluation-complete",
                Map.of("completed", completed, "total", total, "failed", failed,
                        "regionName", regionName, "date", date.toString(),
                        "targetType", targetType.name()));
        emitter.complete();

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
        ConcurrentHashMap<String, BriefingEvaluationResult> cached = cache.get(cacheKey);
        return cached != null ? Collections.unmodifiableMap(cached) : Map.of();
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

    private void emitCachedResults(ConcurrentHashMap<String, BriefingEvaluationResult> cached,
            SseEmitter emitter, String regionName, LocalDate date, TargetType targetType) {
        int total = cached.size();
        int i = 0;
        for (BriefingEvaluationResult result : cached.values()) {
            sendSafe(emitter, "location-scored", result);
            i++;
            sendSafe(emitter, "progress", Map.of("completed", i, "total", total, "failed", 0));
        }
        sendSafe(emitter, "evaluation-complete",
                Map.of("completed", total, "total", total, "failed", 0,
                        "regionName", regionName, "date", date.toString(),
                        "targetType", targetType.name()));
        emitter.complete();
    }

    private void sendSafe(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            LOG.debug("SSE send failed for event '{}': {}", eventName, e.getMessage());
        }
    }
}
