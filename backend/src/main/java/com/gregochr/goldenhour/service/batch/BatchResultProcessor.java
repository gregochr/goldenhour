package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.ErrorObject;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchResult;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.CostCalculator;
import com.gregochr.goldenhour.service.JobRunService;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fetches completed Anthropic Batch API results and writes them to the appropriate cache.
 *
 * <p>For FORECAST batches: parses each per-location response, looks up the location by the ID
 * encoded in the {@code customId}. Two formats are accepted:
 * <ul>
 *   <li>Scheduled: {@code "fc-{locationId}-{date}-{targetType}"}</li>
 *   <li>Force-submit: {@code "force-{regionName}-{locationId}-{date}-{targetType}"}</li>
 * </ul>
 * Reconstructs the {@code "regionName|date|targetType"} cache key and writes to
 * {@link BriefingEvaluationService}.
 *
 * <p>For AURORA batches: re-runs weather triage to obtain current cloud data, parses the
 * single-response aurora evaluation, and writes scored results to {@link AuroraStateCache}.
 * The {@code customId} format is {@code "au-{alertLevel}-{date}"}.
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
    private final JobRunService jobRunService;
    private final CostCalculator costCalculator;

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
     * @param jobRunService               service for completing the linked job run record
     * @param costCalculator              calculates token-based costs for batch results
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
            ObjectMapper objectMapper,
            JobRunService jobRunService,
            CostCalculator costCalculator) {
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
        this.jobRunService = jobRunService;
        this.costCalculator = costCalculator;
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
        long totalInput = 0;
        long totalOutput = 0;
        long totalCacheRead = 0;
        long totalCacheCreate = 0;
        String firstModelId = null;
        Map<String, Integer> errorTypeCounts = new HashMap<>();
        boolean firstError = true;

        try (var streamResp = anthropicClient.messages().batches()
                .resultsStreaming(batch.getAnthropicBatchId())) {
            for (MessageBatchIndividualResponse response : (Iterable<MessageBatchIndividualResponse>)
                    streamResp.stream()::iterator) {
                String customId = response.customId();

                if (!response.result().isSucceeded()) {
                    String[] detail = describeFailedResult(response.result());
                    LOG.warn("Forecast batch: request '{}' {} — {}", customId,
                            detail[0], detail[1]);
                    if (firstError) {
                        LOG.info("Forecast batch: first error sample — customId={}, "
                                + "resultObject={}", customId, response.result());
                        firstError = false;
                    }
                    errorTypeCounts.merge(detail[0], 1, Integer::sum);
                    errored++;
                    continue;
                }

                Message message = extractMessage(response);
                if (message == null) {
                    LOG.warn("Forecast batch: no message for '{}'", customId);
                    errored++;
                    continue;
                }

                String text = extractTextFromMessage(message);
                if (text == null) {
                    LOG.warn("Forecast batch: no text content for '{}'", customId);
                    errored++;
                    continue;
                }

                // Capture model from first response
                if (firstModelId == null) {
                    firstModelId = message.model().asString();
                }

                // Log per-request token usage
                Usage usage = message.usage();
                long input = usage.inputTokens();
                long output = usage.outputTokens();
                long cacheRead = usage.cacheReadInputTokens().orElse(0L);
                long cacheCreate = usage.cacheCreationInputTokens().orElse(0L);
                LOG.info("Batch token usage [{}]: input={}, output={}, cacheRead={}, cacheCreate={}",
                        customId, input, output, cacheRead, cacheCreate);
                totalInput += input;
                totalOutput += output;
                totalCacheRead += cacheRead;
                totalCacheCreate += cacheCreate;

                // Scheduled format: "fc-{locationId}-{yyyy}-{MM}-{dd}-{targetType}"
                // e.g. "fc-42-2026-04-16-SUNRISE" → 6 parts
                // Force-submit format: "force-{regionName}-{locationId}-{yyyy}-{MM}-{dd}-{targetType}"
                // e.g. "force-TheNorthYorkMoors-93-2026-04-16-SUNSET" → 7 parts
                String[] parts = customId.split("-");
                int locationIdIdx;
                int dateStartIdx;
                int eventIdx;

                if (parts.length == 6 && "fc".equals(parts[0])) {
                    locationIdIdx = 1;
                    dateStartIdx = 2;
                    eventIdx = 5;
                } else if (parts.length == 7 && "force".equals(parts[0])) {
                    locationIdIdx = 2;
                    dateStartIdx = 3;
                    eventIdx = 6;
                } else {
                    LOG.warn("Forecast batch: malformed customId '{}', skipping", customId);
                    errored++;
                    continue;
                }

                long locationId;
                try {
                    locationId = Long.parseLong(parts[locationIdIdx]);
                } catch (NumberFormatException e) {
                    LOG.warn("Forecast batch: non-numeric locationId in '{}', skipping", customId);
                    errored++;
                    continue;
                }

                LocationEntity location = locationRepository.findById(locationId).orElse(null);
                if (location == null) {
                    LOG.warn("Forecast batch: location {} not found for customId '{}', skipping",
                            locationId, customId);
                    errored++;
                    continue;
                }

                if (customId.startsWith("force-")) {
                    LOG.info("Batch result: processing force-submit result for locationId={} "
                            + "date={}-{}-{} event={}",
                            locationId, parts[dateStartIdx], parts[dateStartIdx + 1],
                            parts[dateStartIdx + 2], parts[eventIdx]);
                }

                String date = parts[dateStartIdx] + "-" + parts[dateStartIdx + 1]
                        + "-" + parts[dateStartIdx + 2];
                String targetTypePart = parts[eventIdx];
                String regionName = location.getRegion() != null
                        ? location.getRegion().getName() : location.getName();
                String cacheKey = regionName + "|" + date + "|" + targetTypePart;
                String locationName = location.getName();

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
                    LOG.warn("Forecast batch: raw response for '{}': {}", customId, text);
                    errored++;
                }
            }
        } catch (Exception e) {
            LOG.error("Forecast batch: failed to stream results for {}: {}",
                    batch.getAnthropicBatchId(), e.getMessage(), e);
            markFailed(batch, "Failed to stream results: " + e.getMessage());
            return;
        }

        // Log error type summary if there were failures
        if (!errorTypeCounts.isEmpty()) {
            String errorSummary = errorTypeCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
            LOG.warn("Forecast batch {}: error summary — [{}]",
                    batch.getAnthropicBatchId(), errorSummary);
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

        persistTokenUsage(batch, totalInput, totalOutput, totalCacheRead, totalCacheCreate,
                firstModelId);

        batchRepository.save(batch);
        if (batch.getJobRunId() != null) {
            jobRunService.completeBatchRun(batch.getJobRunId(), succeeded,
                    batch.getRequestCount() - succeeded);
        }
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
        String auroraModelId = null;
        long totalInput = 0;
        long totalOutput = 0;
        long totalCacheRead = 0;
        long totalCacheCreate = 0;

        try (var streamResp = anthropicClient.messages().batches()
                .resultsStreaming(batch.getAnthropicBatchId())) {
            for (MessageBatchIndividualResponse response : (Iterable<MessageBatchIndividualResponse>)
                    streamResp.stream()::iterator) {
                String customId = response.customId();

                if (!response.result().isSucceeded()) {
                    String[] detail = describeFailedResult(response.result());
                    LOG.warn("Aurora batch: request '{}' {} — {}", customId,
                            detail[0], detail[1]);
                    LOG.info("Aurora batch: error detail — resultObject={}",
                            response.result());
                    markFailed(batch, "Aurora batch request " + detail[0]
                            + ": " + detail[1]);
                    return;
                }

                Message message = extractMessage(response);
                if (message != null) {
                    rawResponse = extractTextFromMessage(message);
                    auroraModelId = message.model().asString();

                    Usage usage = message.usage();
                    totalInput = usage.inputTokens();
                    totalOutput = usage.outputTokens();
                    totalCacheRead = usage.cacheReadInputTokens().orElse(0L);
                    totalCacheCreate = usage.cacheCreationInputTokens().orElse(0L);
                    LOG.info("Batch token usage [{}]: input={}, output={}, cacheRead={}, "
                            + "cacheCreate={}", customId, totalInput, totalOutput,
                            totalCacheRead, totalCacheCreate);
                }

                // customId format: "au-{alertLevel}-{date}"
                // e.g. "au-MODERATE-2026-04-16" → split on "-" limit 3 gives
                // ["au", "MODERATE", "2026-04-16"]
                String[] parts = customId.split("-", 3);
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

            persistTokenUsage(batch, totalInput, totalOutput, totalCacheRead, totalCacheCreate,
                    auroraModelId);

            batchRepository.save(batch);
            if (batch.getJobRunId() != null) {
                jobRunService.completeBatchRun(batch.getJobRunId(), allScores.size(), 0);
            }

        } catch (Exception e) {
            LOG.error("Aurora batch: score parsing/caching failed: {}", e.getMessage(), e);
            markFailed(batch, "Score parsing failed: " + e.getMessage());
        }
    }

    /**
     * Extracts the {@link Message} from a succeeded batch individual response.
     */
    private Message extractMessage(MessageBatchIndividualResponse response) {
        return response.result().succeeded()
                .map(succeeded -> succeeded.message())
                .orElse(null);
    }

    /**
     * Extracts the text content from a {@link Message}.
     */
    private String extractTextFromMessage(Message message) {
        return message.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElse(null);
    }

    /**
     * Persists token usage totals and estimated cost on the batch entity.
     */
    private void persistTokenUsage(ForecastBatchEntity batch, long totalInput, long totalOutput,
            long totalCacheRead, long totalCacheCreate, String modelId) {
        if (totalInput == 0 && totalOutput == 0) {
            return;
        }

        batch.setTotalInputTokens(totalInput);
        batch.setTotalOutputTokens(totalOutput);
        batch.setTotalCacheReadTokens(totalCacheRead);
        batch.setTotalCacheCreationTokens(totalCacheCreate);

        EvaluationModel evalModel = resolveEvaluationModel(modelId);
        TokenUsage tokenUsage = new TokenUsage(totalInput, totalOutput, totalCacheCreate,
                totalCacheRead);
        long costMicroDollars = costCalculator.calculateCostMicroDollars(evalModel, tokenUsage, true);
        BigDecimal costUsd = BigDecimal.valueOf(costMicroDollars)
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        batch.setEstimatedCostUsd(costUsd);

        LOG.info("Batch cost summary [{}]: model={}, input={}k, output={}k, cacheRead={}k, "
                + "cacheCreate={}k, estimated cost=${} USD",
                batch.getAnthropicBatchId(), modelId != null ? modelId : "unknown",
                totalInput / 1000, totalOutput / 1000, totalCacheRead / 1000,
                totalCacheCreate / 1000, costUsd.toPlainString());
    }

    /**
     * Resolves an Anthropic model ID string to an {@link EvaluationModel} enum value.
     */
    static EvaluationModel resolveEvaluationModel(String modelId) {
        if (modelId == null) {
            return EvaluationModel.SONNET;
        }
        for (EvaluationModel em : EvaluationModel.values()) {
            if (em.getModelId() != null && modelId.contains(em.getModelId())) {
                return em;
            }
        }
        if (modelId.contains("haiku")) {
            return EvaluationModel.HAIKU;
        } else if (modelId.contains("opus")) {
            return EvaluationModel.OPUS;
        } else if (modelId.contains("sonnet")) {
            return EvaluationModel.SONNET;
        }
        LOG.warn("Unknown model for pricing: {} — using Sonnet rates", modelId);
        return EvaluationModel.SONNET;
    }

    /**
     * Describes a non-succeeded batch result as a two-element array: [type, detail].
     *
     * <p>For errored results, drills into the {@link ErrorObject} to extract the specific
     * error type (e.g. "overloaded_error") and message. For expired/canceled results,
     * returns a descriptive status string.
     *
     * @param result the non-succeeded batch result
     * @return {@code [statusType, detailMessage]}
     */
    static String[] describeFailedResult(MessageBatchResult result) {
        if (result.isErrored()) {
            var errResult = result.asErrored();
            ErrorObject err = errResult.error().error();
            return new String[]{resolveErrorType(err), resolveErrorMessage(err)};
        }
        if (result.isExpired()) {
            return new String[]{"expired", "request expired before processing"};
        }
        if (result.isCanceled()) {
            return new String[]{"canceled", "request was canceled"};
        }
        return new String[]{"unknown", result.toString()};
    }

    /**
     * Resolves the Anthropic error type string from an {@link ErrorObject}.
     */
    static String resolveErrorType(ErrorObject err) {
        if (err.isOverloadedError()) {
            return "overloaded_error";
        }
        if (err.isInvalidRequestError()) {
            return "invalid_request_error";
        }
        if (err.isRateLimitError()) {
            return "rate_limit_error";
        }
        if (err.isAuthenticationError()) {
            return "authentication_error";
        }
        if (err.isBillingError()) {
            return "billing_error";
        }
        if (err.isPermissionError()) {
            return "permission_error";
        }
        if (err.isNotFoundError()) {
            return "not_found_error";
        }
        if (err.isTimeoutError()) {
            return "timeout_error";
        }
        if (err.isApiError()) {
            return "api_error";
        }
        return "unknown";
    }

    /**
     * Extracts the error message from an {@link ErrorObject}.
     */
    static String resolveErrorMessage(ErrorObject err) {
        if (err.isOverloadedError()) {
            return err.asOverloadedError().message();
        }
        if (err.isInvalidRequestError()) {
            return err.asInvalidRequestError().message();
        }
        if (err.isRateLimitError()) {
            return err.asRateLimitError().message();
        }
        if (err.isAuthenticationError()) {
            return err.asAuthenticationError().message();
        }
        if (err.isBillingError()) {
            return err.asBillingError().message();
        }
        if (err.isPermissionError()) {
            return err.asPermissionError().message();
        }
        if (err.isNotFoundError()) {
            return err.asNotFoundError().message();
        }
        if (err.isTimeoutError()) {
            return err.asTimeoutError().message();
        }
        if (err.isApiError()) {
            return err.asApiError().message();
        }
        return err.toString();
    }

    private void markFailed(ForecastBatchEntity batch, String reason) {
        batch.setStatus(BatchStatus.FAILED);
        batch.setErrorMessage(reason);
        batch.setEndedAt(Instant.now());
        batchRepository.save(batch);
        if (batch.getJobRunId() != null) {
            int succeeded = batch.getSucceededCount() != null ? batch.getSucceededCount() : 0;
            jobRunService.completeBatchRun(batch.getJobRunId(), succeeded,
                    batch.getRequestCount() - succeeded);
        }
    }
}
