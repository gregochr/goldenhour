package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.CoastalPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import com.gregochr.goldenhour.client.MetOfficeSpaceWeatherScraper;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Submits forecast and aurora evaluations to the Anthropic Batch API for cost-efficient
 * asynchronous processing.
 *
 * <p>FORECAST batch: one request per GO/MARGINAL location in the current daily briefing.
 * The {@code customId} encodes {@code "regionName|date|targetType|locationName"} so
 * {@link BatchResultProcessor} can route results to the correct evaluation cache entry.
 *
 * <p>AURORA batch: a single request containing the full multi-location aurora prompt
 * (identical structure to the real-time path). The {@code customId} is
 * {@code "aurora|<alertLevel>"}.
 *
 * <p>Both jobs are registered with {@link DynamicSchedulerService} and controlled via
 * the Scheduler admin UI.
 */
@Service
public class ScheduledBatchEvaluationService {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledBatchEvaluationService.class);

    private final AnthropicClient anthropicClient;
    private final ForecastBatchRepository batchRepository;
    private final LocationService locationService;
    private final BriefingService briefingService;
    private final BriefingEvaluationService briefingEvaluationService;
    private final ForecastService forecastService;
    private final ForecastStabilityClassifier stabilityClassifier;
    private final PromptBuilder promptBuilder;
    private final CoastalPromptBuilder coastalPromptBuilder;
    private final ModelSelectionService modelSelectionService;
    private final NoaaSwpcClient noaaSwpcClient;
    private final WeatherTriageService weatherTriageService;
    private final ClaudeAuroraInterpreter claudeAuroraInterpreter;
    private final AuroraOrchestrator auroraOrchestrator;
    private final LocationRepository locationRepository;
    private final AuroraProperties auroraProperties;
    private final MetOfficeSpaceWeatherScraper metOfficeScraper;
    private final DynamicSchedulerService dynamicSchedulerService;

    /**
     * Constructs the batch evaluation service.
     *
     * @param anthropicClient             raw Anthropic SDK client for batch API access
     * @param batchRepository             repository for persisting batch records
     * @param locationService             service for retrieving enabled locations
     * @param briefingService             service for accessing the cached daily briefing
     * @param briefingEvaluationService   evaluation cache — checked before building requests
     * @param forecastService             service for weather fetch and triage
     * @param stabilityClassifier         classifies forecast stability per grid cell
     * @param promptBuilder               builds system prompt and user messages for inland locations
     * @param coastalPromptBuilder        builds system prompt and user messages for coastal locations
     * @param modelSelectionService       resolves the active Claude model per run type
     * @param noaaSwpcClient              NOAA SWPC space weather data client
     * @param weatherTriageService        aurora weather triage
     * @param claudeAuroraInterpreter     aurora prompt builder and response parser
     * @param auroraOrchestrator          derives alert level from space weather data
     * @param locationRepository          location JPA repository for Bortle-filtered candidates
     * @param auroraProperties            aurora configuration (Bortle thresholds)
     * @param metOfficeScraper            Met Office space weather narrative
     * @param dynamicSchedulerService     scheduler to register job targets
     */
    public ScheduledBatchEvaluationService(AnthropicClient anthropicClient,
            ForecastBatchRepository batchRepository,
            LocationService locationService,
            BriefingService briefingService,
            BriefingEvaluationService briefingEvaluationService,
            ForecastService forecastService,
            ForecastStabilityClassifier stabilityClassifier,
            PromptBuilder promptBuilder,
            CoastalPromptBuilder coastalPromptBuilder,
            ModelSelectionService modelSelectionService,
            NoaaSwpcClient noaaSwpcClient,
            WeatherTriageService weatherTriageService,
            ClaudeAuroraInterpreter claudeAuroraInterpreter,
            AuroraOrchestrator auroraOrchestrator,
            LocationRepository locationRepository,
            AuroraProperties auroraProperties,
            MetOfficeSpaceWeatherScraper metOfficeScraper,
            DynamicSchedulerService dynamicSchedulerService) {
        this.anthropicClient = anthropicClient;
        this.batchRepository = batchRepository;
        this.locationService = locationService;
        this.briefingService = briefingService;
        this.briefingEvaluationService = briefingEvaluationService;
        this.forecastService = forecastService;
        this.stabilityClassifier = stabilityClassifier;
        this.promptBuilder = promptBuilder;
        this.coastalPromptBuilder = coastalPromptBuilder;
        this.modelSelectionService = modelSelectionService;
        this.noaaSwpcClient = noaaSwpcClient;
        this.weatherTriageService = weatherTriageService;
        this.claudeAuroraInterpreter = claudeAuroraInterpreter;
        this.auroraOrchestrator = auroraOrchestrator;
        this.locationRepository = locationRepository;
        this.auroraProperties = auroraProperties;
        this.metOfficeScraper = metOfficeScraper;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /**
     * Registers job targets with the dynamic scheduler.
     */
    @PostConstruct
    public void registerJobTargets() {
        dynamicSchedulerService.registerJobTarget(
                "near_term_batch_evaluation", this::submitForecastBatch);
        dynamicSchedulerService.registerJobTarget(
                "aurora_batch_evaluation", this::submitAuroraBatch);
    }

    /**
     * Builds and submits a forecast evaluation batch to the Anthropic Batch API.
     *
     * <p>Iterates over all GO and MARGINAL location slots from the current daily briefing.
     * For each, fetches weather data and (if triage passes) adds a request to the batch.
     * Skips submission if no non-triaged locations are found.
     */
    public void submitForecastBatch() {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            LOG.info("Forecast batch skipped: no cached briefing available");
            return;
        }

        EvaluationModel model = modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH);
        List<BatchCreateParams.Request> requests = new ArrayList<>();
        Map<String, GridCellStabilityResult> stabilityByCell = new HashMap<>();

        for (BriefingDay day : briefing.days()) {
            LocalDate date = day.date();
            for (BriefingEventSummary eventSummary : day.eventSummaries()) {
                TargetType targetType = eventSummary.targetType();
                for (BriefingRegion region : eventSummary.regions()) {
                    String regionName = region.regionName();
                    String cacheKey = regionName + "|" + date + "|" + targetType;
                    if (briefingEvaluationService.hasEvaluation(cacheKey)) {
                        LOG.debug("Forecast batch: {} already cached, skipping region", cacheKey);
                        continue;
                    }
                    for (BriefingSlot slot : region.slots()) {
                        if (slot.verdict() != Verdict.GO && slot.verdict() != Verdict.MARGINAL) {
                            continue;
                        }
                        LocationEntity location = findLocation(slot.locationName());
                        if (location == null) {
                            LOG.warn("Forecast batch: unknown location '{}', skipping",
                                    slot.locationName());
                            continue;
                        }
                        try {
                            ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                                    location, date, targetType, location.getTideType(),
                                    model, false, null);
                            if (preEval.triaged()) {
                                LOG.debug("Forecast batch: {} triaged ({}), skipping",
                                        location.getName(), preEval.triageReason());
                                continue;
                            }
                            int daysAhead = preEval.daysAhead();
                            int maxDays = getStabilityWindowDays(location, preEval, stabilityByCell);
                            if (daysAhead > maxDays) {
                                LOG.debug("Forecast batch: {} T+{} skipped — beyond stability window (max={})",
                                        location.getName(), daysAhead, maxDays);
                                continue;
                            }
                            BatchCreateParams.Request request =
                                    buildForecastRequest(regionName, date, targetType,
                                            location, preEval.atmosphericData(), model);
                            requests.add(request);
                        } catch (Exception e) {
                            LOG.warn("Forecast batch: weather fetch failed for {}: {}",
                                    location.getName(), e.getMessage());
                        }
                    }
                }
            }
        }

        if (requests.isEmpty()) {
            LOG.info("Forecast batch: no evaluable locations found, skipping submission");
            return;
        }

        submitBatch(requests, BatchType.FORECAST, "Forecast batch");
    }

    /**
     * Builds and submits an aurora evaluation batch to the Anthropic Batch API.
     *
     * <p>Fetches current NOAA SWPC data, derives the alert level, and runs weather triage.
     * Submits a single batch request if any locations pass triage. Skips submission if the
     * alert level is QUIET or no locations are viable.
     */
    public void submitAuroraBatch() {
        SpaceWeatherData spaceWeather;
        try {
            spaceWeather = noaaSwpcClient.fetchAll();
        } catch (Exception e) {
            LOG.warn("Aurora batch skipped: NOAA fetch failed — {}", e.getMessage());
            return;
        }

        AlertLevel level = auroraOrchestrator.deriveAlertLevel(spaceWeather);
        if (level == AlertLevel.QUIET) {
            LOG.info("Aurora batch skipped: alert level is QUIET");
            return;
        }

        int threshold = (level == AlertLevel.STRONG)
                ? auroraProperties.getBortleThreshold().getStrong()
                : auroraProperties.getBortleThreshold().getModerate();

        List<LocationEntity> candidates = locationRepository
                .findByBortleClassLessThanEqualAndEnabledTrue(threshold);

        if (candidates.isEmpty()) {
            LOG.info("Aurora batch skipped: no Bortle-eligible locations (threshold={})", threshold);
            return;
        }

        WeatherTriageService.TriageResult triage = weatherTriageService.triage(candidates);
        if (triage.viable().isEmpty()) {
            LOG.info("Aurora batch skipped: no locations passed weather triage");
            return;
        }

        String metOfficeText = metOfficeScraper.getForecastText();
        String userMessage = claudeAuroraInterpreter.buildUserMessage(
                level, triage.viable(), triage.cloudByLocation(), spaceWeather,
                metOfficeText, TriggerType.FORECAST_LOOKAHEAD, null);

        EvaluationModel model =
                modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION);
        String customId = "aurora|" + level.name();

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId(customId)
                .params(BatchCreateParams.Request.Params.builder()
                        .model(model.getModelId())
                        .maxTokens(1024)
                        .addUserMessage(userMessage)
                        .build())
                .build();

        submitBatch(List.of(request), BatchType.AURORA, "Aurora batch");
    }

    /**
     * Submits a list of requests to the Anthropic Batch API and persists the tracking entity.
     */
    private void submitBatch(List<BatchCreateParams.Request> requests,
            BatchType batchType, String logPrefix) {
        try {
            BatchCreateParams params = BatchCreateParams.builder()
                    .requests(requests)
                    .build();

            MessageBatch batch = anthropicClient.messages().batches().create(params);

            java.time.Instant expiresAt = batch.expiresAt().toInstant();

            ForecastBatchEntity entity = new ForecastBatchEntity(
                    batch.id(), batchType, requests.size(), expiresAt);
            batchRepository.save(entity);

            LOG.info("{} submitted: batchId={}, {} request(s), expires={}",
                    logPrefix, batch.id(), requests.size(), expiresAt);
        } catch (Exception e) {
            LOG.error("{} submission failed: {}", logPrefix, e.getMessage(), e);
        }
    }

    /**
     * Builds a single batch request for a forecast location.
     *
     * <p>The {@code customId} format is {@code "regionName|date|targetType|locationName"},
     * which {@link BatchResultProcessor} uses to write results to the correct cache entry.
     */
    private BatchCreateParams.Request buildForecastRequest(String regionName, LocalDate date,
            TargetType targetType, LocationEntity location, AtmosphericData data,
            EvaluationModel model) {
        PromptBuilder builder = data.tide() != null
                ? coastalPromptBuilder : promptBuilder;

        String userMessage = data.surge() != null
                ? builder.buildUserMessage(data, data.surge(),
                        data.adjustedRangeMetres(), data.astronomicalRangeMetres())
                : builder.buildUserMessage(data);

        String customId = regionName + "|" + date + "|" + targetType + "|" + location.getName();

        return BatchCreateParams.Request.builder()
                .customId(customId)
                .params(BatchCreateParams.Request.Params.builder()
                        .model(model.getModelId())
                        .maxTokens(512)
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(builder.getSystemPrompt())
                                        .cacheControl(CacheControlEphemeral.builder().build())
                                        .build()))
                        .addUserMessage(userMessage)
                        .build())
                .build();
    }

    /**
     * Returns the evaluation window in days for the given location, capped at 3.
     *
     * <p>Mirrors the logic in {@code ForecastCommandExecutor.applyStabilityFilter()}: each
     * unique grid cell is classified once via {@link ForecastStabilityClassifier} and the result
     * cached in {@code stabilityByCell} for the duration of the batch submission run.
     *
     * <p>Defaults to 1 if the location has no grid cell or no forecast response is available.
     *
     * @param location        the location to evaluate
     * @param preEval         the pre-evaluation result containing the raw forecast response
     * @param stabilityByCell per-grid-cell stability cache shared across the current batch run
     * @return max days ahead to include, between 0 and 3
     */
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
}
