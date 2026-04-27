package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.ErrorObject;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchResult;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.CostCalculator;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.evaluation.AuroraResultHandler;
import com.gregochr.goldenhour.service.evaluation.AuroraResultHandler.AuroraBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.BatchSuccess;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.ForecastIdentity;
import com.gregochr.goldenhour.service.evaluation.ParsedCustomId;
import com.gregochr.goldenhour.service.evaluation.ResultContext;
import com.gregochr.goldenhour.entity.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fetches completed Anthropic Batch API results and dispatches them through the per-task-type
 * {@link ForecastResultHandler} / {@link AuroraResultHandler}.
 *
 * <p>This class owns only the outer loop (streaming individual responses, error counting,
 * token aggregation, batch entity status updates). All per-response parsing, rating
 * validation, cache writes, and {@code api_call_log} bookkeeping live inside the handlers,
 * which are also reachable from {@code EvaluationService.evaluateNow} so the sync and batch
 * paths produce byte-identical observability.
 *
 * <p>Called by {@link BatchPollingService} once a batch transitions to {@code ENDED}.
 */
@Service
public class BatchResultProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(BatchResultProcessor.class);

    private final AnthropicClient anthropicClient;
    private final ForecastBatchRepository batchRepository;
    private final LocationRepository locationRepository;
    private final JobRunService jobRunService;
    private final CostCalculator costCalculator;
    private final ForecastResultHandler forecastResultHandler;
    private final AuroraResultHandler auroraResultHandler;

    /**
     * Constructs the batch result processor.
     *
     * @param anthropicClient        raw SDK client for downloading batch results
     * @param batchRepository        repository for updating batch status
     * @param locationRepository     for {@code locationId → LocationEntity} resolution
     * @param jobRunService          service for completing the linked job run record and
     *                               recording inline failures (malformed id, missing
     *                               location) where no handler can act
     * @param costCalculator         calculates token-based costs for batch results
     * @param forecastResultHandler  per-response forecast result handler
     * @param auroraResultHandler    aurora batch handler
     */
    public BatchResultProcessor(AnthropicClient anthropicClient,
            ForecastBatchRepository batchRepository,
            LocationRepository locationRepository,
            JobRunService jobRunService,
            CostCalculator costCalculator,
            ForecastResultHandler forecastResultHandler,
            AuroraResultHandler auroraResultHandler) {
        this.anthropicClient = anthropicClient;
        this.batchRepository = batchRepository;
        this.locationRepository = locationRepository;
        this.jobRunService = jobRunService;
        this.costCalculator = costCalculator;
        this.forecastResultHandler = forecastResultHandler;
        this.auroraResultHandler = auroraResultHandler;
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
     * <p>Streams individual responses, dispatches each through
     * {@link ForecastResultHandler#parseBatchResponse}, groups successful results by
     * {@code "regionName|date|targetType"}, and flushes each group to
     * {@link ForecastResultHandler#flushCacheKey} after the loop.
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

        ResultContext context = ResultContext.forBatch(
                batch.getJobRunId(), batch.getAnthropicBatchId(), null);

        try (var streamResp = anthropicClient.messages().batches()
                .resultsStreaming(batch.getAnthropicBatchId())) {
            for (MessageBatchIndividualResponse response : (Iterable<MessageBatchIndividualResponse>)
                    streamResp.stream()::iterator) {
                String customId = response.customId();

                if (!response.result().isSucceeded()) {
                    String[] detail;
                    try {
                        detail = describeFailedResult(response.result());
                    } catch (Exception ex) {
                        detail = new String[]{"unknown", "failed to extract error: "
                                + ex.getMessage()};
                    }
                    LOG.warn("Forecast batch: request '{}' {} — {}", customId,
                            detail[0], detail[1]);
                    if (firstError) {
                        LOG.info("Forecast batch: first error sample — customId={}, "
                                + "resultObject={}", customId, response.result());
                        firstError = false;
                    }
                    errorTypeCounts.merge(detail[0], 1, Integer::sum);
                    errored++;
                    inlineFailureLog(context, customId,
                            detail[0].toUpperCase(), detail[0], detail[1], null, null);
                    continue;
                }

                Message message = extractMessage(response);
                if (message == null) {
                    LOG.warn("Forecast batch: no message for '{}'", customId);
                    errored++;
                    inlineFailureLog(context, customId, "NO_MESSAGE",
                            "extraction_error", "succeeded but no message", null, null);
                    continue;
                }

                String text = extractTextFromMessage(message);
                if (text == null) {
                    LOG.warn("Forecast batch: no text content for '{}'", customId);
                    errored++;
                    inlineFailureLog(context, customId, "NO_TEXT",
                            "extraction_error", "no text content blocks", null, null);
                    continue;
                }

                if (firstModelId == null) {
                    firstModelId = message.model().asString();
                }

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

                ParsedCustomId parsed;
                try {
                    parsed = CustomIdFactory.parse(customId);
                } catch (IllegalArgumentException e) {
                    LOG.warn("Forecast batch: malformed customId '{}', skipping", customId);
                    errored++;
                    inlineFailureLog(context, customId, "MALFORMED_ID",
                            "parse_error", "malformed customId", null, null);
                    continue;
                }

                ForecastIdentity identity;
                switch (parsed) {
                    case ParsedCustomId.Forecast f ->
                        identity = new ForecastIdentity(f.locationId(), f.date(), f.targetType());
                    case ParsedCustomId.Jfdi j ->
                        identity = new ForecastIdentity(j.locationId(), j.date(), j.targetType());
                    case ParsedCustomId.ForceSubmit fs -> {
                        identity = new ForecastIdentity(fs.locationId(), fs.date(), fs.targetType());
                        LOG.info("Batch result: processing force-submit result for "
                                + "locationId={} date={} event={}",
                                fs.locationId(), fs.date(), fs.targetType());
                    }
                    case ParsedCustomId.Aurora ignored -> {
                        LOG.warn("Forecast batch: aurora customId '{}' in forecast batch, "
                                + "skipping", customId);
                        errored++;
                        inlineFailureLog(context, customId, "MALFORMED_ID",
                                "parse_error", "aurora customId in forecast batch",
                                null, null);
                        continue;
                    }
                    default -> throw new IllegalStateException(
                            "Unhandled parsed custom id: " + parsed);
                }

                LocationEntity location = locationRepository.findById(identity.locationId())
                        .orElse(null);
                if (location == null) {
                    LOG.warn("Forecast batch: location {} not found for customId '{}', skipping",
                            identity.locationId(), customId);
                    errored++;
                    inlineFailureLog(context, customId, "LOCATION_NOT_FOUND",
                            "lookup_error",
                            "location " + identity.locationId() + " not found",
                            identity.date(), identity.targetType());
                    continue;
                }

                TokenUsage tokens = new TokenUsage(input, output, cacheCreate, cacheRead);
                EvaluationModel model = resolveEvaluationModel(message.model().asString());
                ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                        customId, text, tokens, model);

                var success = forecastResultHandler.parseBatchResponse(
                        location, identity, outcome, context);
                if (success.isPresent()) {
                    BatchSuccess hit = success.get();
                    byKey.computeIfAbsent(hit.cacheKey(), k -> new ArrayList<>())
                            .add(hit.result());
                    succeeded++;
                } else {
                    errored++;
                }
            }
        } catch (Exception e) {
            LOG.error("Forecast batch: failed to stream results for {}: {}",
                    batch.getAnthropicBatchId(), e.getMessage(), e);
            markFailed(batch, "Failed to stream results: " + e.getMessage());
            return;
        }

        if (!errorTypeCounts.isEmpty()) {
            String errorSummary = errorTypeCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
            LOG.warn("Forecast batch {}: error summary — [{}]",
                    batch.getAnthropicBatchId(), errorSummary);
        }

        for (Map.Entry<String, List<BriefingEvaluationResult>> entry : byKey.entrySet()) {
            forecastResultHandler.flushCacheKey(entry.getKey(), entry.getValue());
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
                    batch.getRequestCount() - succeeded,
                    toMicroDollars(batch.getEstimatedCostUsd()));
        }
    }

    /**
     * Processes a completed AURORA batch via {@link AuroraResultHandler}.
     */
    private void processAuroraBatch(ForecastBatchEntity batch) {
        String rawResponse = null;
        com.gregochr.goldenhour.entity.AlertLevel level =
                com.gregochr.goldenhour.entity.AlertLevel.QUIET;
        String auroraModelId = null;
        String customId = null;
        long totalInput = 0;
        long totalOutput = 0;
        long totalCacheRead = 0;
        long totalCacheCreate = 0;

        try (var streamResp = anthropicClient.messages().batches()
                .resultsStreaming(batch.getAnthropicBatchId())) {
            for (MessageBatchIndividualResponse response : (Iterable<MessageBatchIndividualResponse>)
                    streamResp.stream()::iterator) {
                customId = response.customId();

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

                String[] parts = customId.split("-", 3);
                if (parts.length >= 2) {
                    try {
                        level = com.gregochr.goldenhour.entity.AlertLevel.valueOf(parts[1]);
                    } catch (IllegalArgumentException e) {
                        LOG.warn("Aurora batch: unrecognised alert level '{}', defaulting to QUIET",
                                parts[1]);
                    }
                }
                break;
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

        ResultContext context = ResultContext.forBatch(
                batch.getJobRunId(), batch.getAnthropicBatchId(), null);
        EvaluationModel model = resolveEvaluationModel(auroraModelId);
        TokenUsage tokens = new TokenUsage(totalInput, totalOutput, totalCacheCreate, totalCacheRead);
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                customId, rawResponse, tokens, model);

        AuroraBatchOutcome handlerResult =
                auroraResultHandler.processBatchResponse(level, outcome, context);
        if (!handlerResult.success()) {
            markFailed(batch, handlerResult.failureReason());
            return;
        }

        LOG.info("Aurora batch complete: batchId={}, {} scores written to state cache",
                batch.getAnthropicBatchId(), handlerResult.scoredCount());

        batch.setSucceededCount(handlerResult.scoredCount());
        batch.setErroredCount(0);
        batch.setEndedAt(Instant.now());
        batch.setStatus(BatchStatus.COMPLETED);

        persistTokenUsage(batch, totalInput, totalOutput, totalCacheRead, totalCacheCreate,
                auroraModelId);

        batchRepository.save(batch);
        if (batch.getJobRunId() != null) {
            jobRunService.completeBatchRun(batch.getJobRunId(),
                    handlerResult.scoredCount(), 0,
                    toMicroDollars(batch.getEstimatedCostUsd()));
        }
    }

    /**
     * Logs an inline (pre-handler) failure where there is no resolved location entity to
     * dispatch through {@link ForecastResultHandler#parseBatchResponse}. Used for SDK-level
     * problems (no message / no text), malformed custom ids, location lookup misses, and
     * cross-type custom ids.
     */
    private void inlineFailureLog(ResultContext context, String customId, String status,
            String errorType, String errorMessage,
            LocalDate targetDate, TargetType targetType) {
        if (context == null || context.jobRunId() == null) {
            return;
        }
        try {
            jobRunService.logBatchResult(
                    context.jobRunId(), context.batchId(), customId,
                    false, status, errorType, errorMessage,
                    null, null, targetDate, targetType);
        } catch (Exception e) {
            LOG.warn("Forecast batch: failed to persist api_call_log for customId={}: {}",
                    customId, e.getMessage());
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

    /**
     * Converts a USD cost (as {@link BigDecimal}) to micro-dollars.
     *
     * @param costUsd the cost in USD, or null
     * @return cost in micro-dollars, or 0 if null
     */
    private static long toMicroDollars(BigDecimal costUsd) {
        if (costUsd == null) {
            return 0L;
        }
        return costUsd.multiply(BigDecimal.valueOf(1_000_000)).longValue();
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
