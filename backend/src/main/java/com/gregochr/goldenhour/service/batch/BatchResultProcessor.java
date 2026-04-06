package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.ClaudeEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches completed Anthropic Batch API results and writes them to the appropriate cache.
 *
 * <p>For FORECAST batches: parses each per-location response, groups results by their
 * {@code "regionName|date|targetType"} cache key, and writes to {@link BriefingEvaluationService}.
 *
 * <p>For AURORA batches: re-runs weather triage to obtain current cloud data, parses the
 * single-response aurora evaluation, and writes scored results to {@link AuroraStateCache}.
 *
 * <p>Called by {@link BatchPollingService} once a batch transitions to {@code ENDED}.
 */
@Service
public class BatchResultProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(BatchResultProcessor.class);

    private final AnthropicClient anthropicClient;
    private final ForecastBatchRepository batchRepository;
    private final BriefingEvaluationService briefingEvaluationService;
    private final Map<EvaluationModel, EvaluationStrategy> evaluationStrategies;
    private final ModelSelectionService modelSelectionService;
    private final ClaudeAuroraInterpreter claudeAuroraInterpreter;
    private final AuroraStateCache auroraStateCache;
    private final WeatherTriageService weatherTriageService;
    private final LocationRepository locationRepository;
    private final AuroraProperties auroraProperties;
    private final NoaaSwpcClient noaaSwpcClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the batch result processor.
     *
     * @param anthropicClient             raw SDK client for downloading batch results
     * @param batchRepository             repository for updating batch status
     * @param briefingEvaluationService   evaluation cache writer for forecast results
     * @param evaluationStrategies        map of model to evaluation strategy (for response parsing)
     * @param modelSelectionService       resolves the active model for parsing
     * @param claudeAuroraInterpreter     aurora response parser
     * @param auroraStateCache            aurora score cache writer
     * @param weatherTriageService        for re-running weather triage on aurora result processing
     * @param locationRepository          for Bortle-filtered location lookup
     * @param auroraProperties            aurora config (Bortle thresholds)
     * @param noaaSwpcClient              NOAA SWPC client for aurora re-triage
     * @param objectMapper                Jackson mapper for JSON parsing
     */
    public BatchResultProcessor(AnthropicClient anthropicClient,
            ForecastBatchRepository batchRepository,
            BriefingEvaluationService briefingEvaluationService,
            Map<EvaluationModel, EvaluationStrategy> evaluationStrategies,
            ModelSelectionService modelSelectionService,
            ClaudeAuroraInterpreter claudeAuroraInterpreter,
            AuroraStateCache auroraStateCache,
            WeatherTriageService weatherTriageService,
            LocationRepository locationRepository,
            AuroraProperties auroraProperties,
            NoaaSwpcClient noaaSwpcClient,
            ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.batchRepository = batchRepository;
        this.briefingEvaluationService = briefingEvaluationService;
        this.evaluationStrategies = evaluationStrategies;
        this.modelSelectionService = modelSelectionService;
        this.claudeAuroraInterpreter = claudeAuroraInterpreter;
        this.auroraStateCache = auroraStateCache;
        this.weatherTriageService = weatherTriageService;
        this.locationRepository = locationRepository;
        this.auroraProperties = auroraProperties;
        this.noaaSwpcClient = noaaSwpcClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Downloads and processes results for an ended batch.
     *
     * <p>Updates the batch entity to {@code COMPLETED} or {@code FAILED} depending on
     * whether any results were successfully processed.
     *
     * @param batch the ended batch entity to process
     */
    public void processResults(ForecastBatchEntity batch) {
        LOG.info("Processing batch results: batchId={}, type={}", batch.getAnthropicBatchId(),
                batch.getBatchType());

        if (batch.getBatchType() == BatchType.FORECAST) {
            processForecastBatch(batch);
        } else if (batch.getBatchType() == BatchType.AURORA) {
            processAuroraBatch(batch);
        } else {
            LOG.warn("Unknown batch type: {}", batch.getBatchType());
            markFailed(batch, "Unknown batch type: " + batch.getBatchType());
        }
    }

    /**
     * Processes a completed FORECAST batch.
     *
     * <p>Streams individual responses, parses each forecast evaluation, groups by
     * {@code "regionName|date|targetType"}, and writes each group to the briefing evaluation cache.
     */
    private void processForecastBatch(ForecastBatchEntity batch) {
        Map<String, List<BriefingEvaluationResult>> byKey = new HashMap<>();
        int succeeded = 0;
        int errored = 0;

        try (var streamResp = anthropicClient.messages().batches()
                .resultsStreaming(batch.getAnthropicBatchId())) {
            for (MessageBatchIndividualResponse response : (Iterable<MessageBatchIndividualResponse>)
                    streamResp.stream()::iterator) {
                String customId = response.customId();

                if (!response.result().isSucceeded()) {
                    LOG.warn("Forecast batch: request '{}' did not succeed", customId);
                    errored++;
                    continue;
                }

                String text = extractText(response);
                if (text == null) {
                    LOG.warn("Forecast batch: no text content for '{}'", customId);
                    errored++;
                    continue;
                }

                // customId format: "regionName|date|targetType|locationName"
                String[] parts = customId.split("\\|", 4);
                if (parts.length < 4) {
                    LOG.warn("Forecast batch: malformed customId '{}', skipping", customId);
                    errored++;
                    continue;
                }

                String cacheKey = parts[0] + "|" + parts[1] + "|" + parts[2];
                String locationName = parts[3];

                try {
                    EvaluationModel model =
                            modelSelectionService.getActiveModel(RunType.SHORT_TERM);
                    ClaudeEvaluationStrategy strategy =
                            (ClaudeEvaluationStrategy) evaluationStrategies.get(model);
                    SunsetEvaluation eval = strategy.parseEvaluation(text, objectMapper);
                    BriefingEvaluationResult result = new BriefingEvaluationResult(
                            locationName,
                            eval.rating(),
                            eval.fierySkyPotential(),
                            eval.goldenHourPotential(),
                            eval.summary());
                    byKey.computeIfAbsent(cacheKey, k -> new ArrayList<>()).add(result);
                    succeeded++;
                } catch (Exception e) {
                    LOG.warn("Forecast batch: parse failed for '{}': {}", customId, e.getMessage());
                    errored++;
                }
            }
        } catch (Exception e) {
            LOG.error("Forecast batch: failed to stream results for {}: {}",
                    batch.getAnthropicBatchId(), e.getMessage(), e);
            markFailed(batch, "Failed to stream results: " + e.getMessage());
            return;
        }

        // Write each group to the evaluation cache
        for (Map.Entry<String, List<BriefingEvaluationResult>> entry : byKey.entrySet()) {
            briefingEvaluationService.writeFromBatch(entry.getKey(), entry.getValue());
        }

        LOG.info("Forecast batch complete: batchId={}, {} succeeded, {} errored, {} cache keys written",
                batch.getAnthropicBatchId(), succeeded, errored, byKey.size());

        batch.setSucceededCount(succeeded);
        batch.setErroredCount(errored);
        batch.setEndedAt(Instant.now());
        batch.setStatus(succeeded > 0 ? BatchStatus.COMPLETED : BatchStatus.FAILED);
        batchRepository.save(batch);
    }

    /**
     * Processes a completed AURORA batch.
     *
     * <p>Streams the single aurora response, re-runs weather triage to get current cloud data,
     * parses the multi-location aurora evaluation, and writes scores to {@link AuroraStateCache}.
     */
    private void processAuroraBatch(ForecastBatchEntity batch) {
        String rawResponse = null;
        AlertLevel level = AlertLevel.QUIET;

        try (var streamResp = anthropicClient.messages().batches()
                .resultsStreaming(batch.getAnthropicBatchId())) {
            for (MessageBatchIndividualResponse response : (Iterable<MessageBatchIndividualResponse>)
                    streamResp.stream()::iterator) {
                String customId = response.customId();

                if (!response.result().isSucceeded()) {
                    LOG.warn("Aurora batch: request '{}' did not succeed", customId);
                    markFailed(batch, "Aurora batch request did not succeed");
                    return;
                }

                rawResponse = extractText(response);

                // customId format: "aurora|<alertLevel>"
                String[] parts = customId.split("\\|", 2);
                if (parts.length >= 2) {
                    try {
                        level = AlertLevel.valueOf(parts[1]);
                    } catch (IllegalArgumentException e) {
                        LOG.warn("Aurora batch: unrecognised alert level '{}', defaulting to QUIET",
                                parts[1]);
                    }
                }
                break; // aurora batch has exactly 1 request
            }
        } catch (Exception e) {
            LOG.error("Aurora batch: failed to stream results for {}: {}",
                    batch.getAnthropicBatchId(), e.getMessage(), e);
            markFailed(batch, "Failed to stream results: " + e.getMessage());
            return;
        }

        if (rawResponse == null) {
            markFailed(batch, "Aurora batch returned no text content");
            return;
        }

        // Re-run weather triage to get current cloud data for the location list
        int threshold = (level == AlertLevel.STRONG)
                ? auroraProperties.getBortleThreshold().getStrong()
                : auroraProperties.getBortleThreshold().getModerate();

        List<LocationEntity> candidates = locationRepository
                .findByBortleClassLessThanEqualAndEnabledTrue(threshold);

        if (candidates.isEmpty()) {
            LOG.info("Aurora batch: no Bortle-eligible locations for re-triage, skipping score update");
            markFailed(batch, "No Bortle-eligible locations at result processing time");
            return;
        }

        WeatherTriageService.TriageResult triage;
        try {
            triage = weatherTriageService.triage(candidates);
        } catch (Exception e) {
            LOG.warn("Aurora batch: weather re-triage failed: {}", e.getMessage());
            markFailed(batch, "Weather re-triage failed: " + e.getMessage());
            return;
        }

        if (triage.viable().isEmpty()) {
            LOG.info("Aurora batch: no viable locations after re-triage, scores not updated");
            markFailed(batch, "No viable locations after re-triage");
            return;
        }

        try {
            List<AuroraForecastScore> scores = claudeAuroraInterpreter.parseBatchResponse(
                    rawResponse, level, triage.viable(), triage.cloudByLocation());

            // Assign 1★ to locations that were rejected at result-processing time
            List<AuroraForecastScore> allScores = new ArrayList<>(scores);
            for (LocationEntity rejected : triage.rejected()) {
                int cloud = triage.cloudByLocation().getOrDefault(rejected, 100);
                allScores.add(new AuroraForecastScore(rejected, 1, level, cloud,
                        "Overcast at time of evaluation", "✗ Cloud cover: Overcast"));
            }

            auroraStateCache.updateScores(allScores);

            LOG.info("Aurora batch complete: batchId={}, {} scores written to state cache",
                    batch.getAnthropicBatchId(), allScores.size());

            batch.setSucceededCount(allScores.size());
            batch.setErroredCount(0);
            batch.setEndedAt(Instant.now());
            batch.setStatus(BatchStatus.COMPLETED);
            batchRepository.save(batch);

        } catch (Exception e) {
            LOG.error("Aurora batch: score parsing/caching failed: {}", e.getMessage(), e);
            markFailed(batch, "Score parsing failed: " + e.getMessage());
        }
    }

    /**
     * Extracts the text content from a succeeded batch individual response.
     */
    private String extractText(MessageBatchIndividualResponse response) {
        return response.result().succeeded()
                .map(succeeded -> succeeded.message().content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .findFirst()
                        .orElse(null))
                .orElse(null);
    }

    private void markFailed(ForecastBatchEntity batch, String reason) {
        batch.setStatus(BatchStatus.FAILED);
        batch.setErrorMessage(reason);
        batch.setEndedAt(Instant.now());
        batchRepository.save(batch);
    }
}
