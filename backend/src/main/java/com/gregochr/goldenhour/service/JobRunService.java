package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Service for tracking scheduled job runs and API call timings for observability and debugging.
 */
@Service
public class JobRunService {

    private static final Logger LOG = LoggerFactory.getLogger(JobRunService.class);

    private final JobRunRepository jobRunRepository;
    private final ApiCallLogRepository apiCallLogRepository;
    private final ForecastBatchRepository forecastBatchRepository;
    private final CostCalculator costCalculator;
    private final ExchangeRateService exchangeRateService;
    private final String appVersion;

    /**
     * Constructs a {@code JobRunService}.
     *
     * @param jobRunRepository        repository for job run entities
     * @param apiCallLogRepository    repository for API call log entities
     * @param forecastBatchRepository repository for forecast batch entities
     * @param costCalculator          service for calculating API call costs
     * @param exchangeRateService     service for fetching exchange rates
     * @param appVersion              application version stamped on each run, from {@code APP_VERSION}
     */
    public JobRunService(JobRunRepository jobRunRepository,
            ApiCallLogRepository apiCallLogRepository,
            ForecastBatchRepository forecastBatchRepository,
            CostCalculator costCalculator,
            ExchangeRateService exchangeRateService,
            @Value("${APP_VERSION:dev}") String appVersion) {
        this.jobRunRepository = jobRunRepository;
        this.apiCallLogRepository = apiCallLogRepository;
        this.forecastBatchRepository = forecastBatchRepository;
        this.costCalculator = costCalculator;
        this.exchangeRateService = exchangeRateService;
        this.appVersion = appVersion;
    }

    /**
     * Starts a new job run with the given run type and optional evaluation model.
     *
     * <p>Fetches today's exchange rate and stores it on the run for historical GBP conversion.
     *
     * @param runType            the type of forecast run
     * @param triggeredManually  whether this run was triggered manually (true) or by scheduler (false)
     * @param evaluationModel    the Claude model used (null for WEATHER/TIDE)
     * @return the newly created job run entity
     */
    public JobRunEntity startRun(RunType runType, boolean triggeredManually,
            EvaluationModel evaluationModel) {
        return startRun(runType, triggeredManually, evaluationModel, null);
    }

    /**
     * Starts a new job run with the given run type, evaluation model, and active strategies.
     *
     * <p>Fetches today's exchange rate and stores it on the run for historical GBP conversion.
     *
     * @param runType            the type of forecast run
     * @param triggeredManually  whether this run was triggered manually (true) or by scheduler (false)
     * @param evaluationModel    the Claude model used (null for WEATHER/TIDE)
     * @param activeStrategies   comma-separated list of enabled strategy names, or null
     * @return the newly created job run entity
     */
    public JobRunEntity startRun(RunType runType, boolean triggeredManually,
            EvaluationModel evaluationModel, String activeStrategies) {
        Double exchangeRate = null;
        try {
            exchangeRate = exchangeRateService.getCurrentRate();
        } catch (Exception e) {
            LOG.warn("Failed to fetch exchange rate for job run: {}", e.getMessage());
        }

        JobRunEntity jobRun = JobRunEntity.builder()
                .runType(runType)
                .evaluationModel(evaluationModel)
                .startedAt(LocalDateTime.now(ZoneOffset.UTC))
                .locationsProcessed(0)
                .succeeded(0)
                .failed(0)
                .totalCostPence(0)
                .triggeredManually(triggeredManually)
                .exchangeRateGbpPerUsd(exchangeRate)
                .activeStrategies(activeStrategies)
                .appVersion(appVersion)
                .build();
        return jobRunRepository.save(jobRun);
    }

    /**
     * Records an Anthropic API call with token usage for accurate cost calculation.
     *
     * @param jobRunId       the job run ID
     * @param durationMs     duration in milliseconds
     * @param statusCode     HTTP status code
     * @param responseBody   response body on error, or null on success
     * @param succeeded      true if the call succeeded
     * @param errorMessage   brief error message if failed, or null
     * @param model          evaluation model (HAIKU, SONNET, or OPUS)
     * @param tokenUsage     token counts from the API response
     * @param isBatch        whether this was a batch API call
     * @param targetDate     target date for forecast evaluations, or null
     * @param targetType     target type (SUNRISE/SUNSET), or null
     * @return the newly created API call log entity
     */
    public ApiCallLogEntity logAnthropicApiCall(Long jobRunId,
            long durationMs, Integer statusCode, String responseBody,
            boolean succeeded, String errorMessage, EvaluationModel model,
            TokenUsage tokenUsage, boolean isBatch,
            LocalDate targetDate, TargetType targetType) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int costPence = costCalculator.calculateCost(ServiceName.ANTHROPIC, model);
        long costMicroDollars = costCalculator.calculateCostMicroDollars(model, tokenUsage, isBatch);

        ApiCallLogEntity log = ApiCallLogEntity.builder()
                .jobRunId(jobRunId)
                .service(ServiceName.ANTHROPIC)
                .calledAt(now)
                .completedAt(now)
                .durationMs(durationMs)
                .requestMethod("POST")
                .requestUrl("https://api.anthropic.com/v1/messages")
                .statusCode(statusCode)
                .responseBody(responseBody)
                .succeeded(succeeded)
                .errorMessage(truncate(errorMessage, 500))
                .costPence(costPence)
                .createdAt(now)
                .evaluationModel(model)
                .targetDate(targetDate)
                .targetType(targetType)
                .inputTokens(tokenUsage.inputTokens())
                .outputTokens(tokenUsage.outputTokens())
                .cacheCreationInputTokens(tokenUsage.cacheCreationInputTokens())
                .cacheReadInputTokens(tokenUsage.cacheReadInputTokens())
                .isBatch(isBatch)
                .costMicroDollars(costMicroDollars)
                .build();
        return apiCallLogRepository.save(log);
    }

    /**
     * Records a single API call made during a job run (legacy method, for non-Anthropic services).
     *
     * @param jobRunId       the job run ID
     * @param service        the service name
     * @param requestMethod  HTTP method (GET, POST, etc.) or null
     * @param requestUrl     full request URL
     * @param requestBody    request body (JSON) or null
     * @param durationMs     duration in milliseconds
     * @param statusCode     HTTP status code or null
     * @param responseBody   response body on error, or null on success
     * @param succeeded      true if the call succeeded
     * @param errorMessage   brief error message if failed, or null
     * @param model          evaluation model for Anthropic calls, or null
     * @param targetDate     target date for forecast evaluations, or null
     * @param targetType     target type (SUNRISE/SUNSET) for forecast evaluations, or null
     * @return the newly created API call log entity
     */
    @SuppressWarnings("deprecation")
    public ApiCallLogEntity logApiCall(Long jobRunId, ServiceName service,
            String requestMethod, String requestUrl, String requestBody,
            long durationMs, Integer statusCode, String responseBody,
            boolean succeeded, String errorMessage, EvaluationModel model,
            LocalDate targetDate, TargetType targetType) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int costPence = costCalculator.calculateCost(service, model);
        long costMicroDollars = (service == ServiceName.ANTHROPIC)
                ? 0 : costCalculator.calculateFlatCostMicroDollars(service);

        ApiCallLogEntity log = ApiCallLogEntity.builder()
                .jobRunId(jobRunId)
                .service(service)
                .calledAt(now)
                .completedAt(now)
                .durationMs(durationMs)
                .requestMethod(requestMethod)
                .requestUrl(requestUrl)
                .requestBody(requestBody)
                .statusCode(statusCode)
                .responseBody(responseBody)
                .succeeded(succeeded)
                .errorMessage(truncate(errorMessage, 500))
                .costPence(costPence)
                .createdAt(now)
                .evaluationModel(model)
                .targetDate(targetDate)
                .targetType(targetType)
                .costMicroDollars(costMicroDollars)
                .build();
        return apiCallLogRepository.save(log);
    }

    /**
     * Records a single API call made during a job run (overload without target date/type).
     *
     * @param jobRunId       the job run ID
     * @param service        the service name
     * @param requestMethod  HTTP method (GET, POST, etc.) or null
     * @param requestUrl     full request URL
     * @param requestBody    request body (JSON) or null
     * @param durationMs     duration in milliseconds
     * @param statusCode     HTTP status code or null
     * @param responseBody   response body on error, or null on success
     * @param succeeded      true if the call succeeded
     * @param errorMessage   brief error message if failed, or null
     * @param model          evaluation model for Anthropic calls, or null
     * @return the newly created API call log entity
     */
    public ApiCallLogEntity logApiCall(Long jobRunId, ServiceName service,
            String requestMethod, String requestUrl, String requestBody,
            long durationMs, Integer statusCode, String responseBody,
            boolean succeeded, String errorMessage, EvaluationModel model) {
        return logApiCall(jobRunId, service, requestMethod, requestUrl, requestBody,
                durationMs, statusCode, responseBody, succeeded, errorMessage, model, null, null);
    }

    /**
     * Records a single API call made during a job run (for non-Anthropic services).
     *
     * @param jobRunId       the job run ID
     * @param service        the service name
     * @param requestMethod  HTTP method (GET, POST, etc.) or null
     * @param requestUrl     full request URL
     * @param requestBody    request body (JSON) or null
     * @param durationMs     duration in milliseconds
     * @param statusCode     HTTP status code or null
     * @param responseBody   response body on error, or null on success
     * @param succeeded      true if the call succeeded
     * @param errorMessage   brief error message if failed, or null
     * @return the newly created API call log entity
     */
    public ApiCallLogEntity logApiCall(Long jobRunId, ServiceName service,
            String requestMethod, String requestUrl, String requestBody,
            long durationMs, Integer statusCode, String responseBody,
            boolean succeeded, String errorMessage) {
        return logApiCall(jobRunId, service, requestMethod, requestUrl, requestBody,
                durationMs, statusCode, responseBody, succeeded, errorMessage, null);
    }

    /**
     * Completes a job run with success and failure counts.
     *
     * <p>Aggregates both legacy cost (pence) and new token-based cost (micro-dollars).
     *
     * @param jobRun   the job run to complete
     * @param succeeded number of successful evaluations
     * @param failed   number of failed evaluations
     * @param dates    list of target dates evaluated in this run (for date range tracking)
     */
    public void completeRun(JobRunEntity jobRun, int succeeded, int failed, List<LocalDate> dates) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long durationMs = ChronoUnit.MILLIS.between(jobRun.getStartedAt(), now);

        // Calculate total cost from all API calls
        List<ApiCallLogEntity> apiCalls = apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(jobRun.getId());
        int totalCostPence = apiCalls.stream()
                .map(ApiCallLogEntity::getCostPence)
                .filter(c -> c != null)
                .reduce(0, Integer::sum);
        long totalCostMicroDollars = apiCalls.stream()
                .map(ApiCallLogEntity::getCostMicroDollars)
                .filter(c -> c != null)
                .reduce(0L, Long::sum);

        // Set date range if dates are provided
        if (dates != null && !dates.isEmpty()) {
            LocalDate minDate = dates.stream().min(LocalDate::compareTo).orElse(null);
            LocalDate maxDate = dates.stream().max(LocalDate::compareTo).orElse(null);
            jobRun.setMinTargetDate(minDate);
            jobRun.setMaxTargetDate(maxDate);
        }

        jobRun.setCompletedAt(now);
        jobRun.setDurationMs(durationMs);
        jobRun.setSucceeded(succeeded);
        jobRun.setFailed(failed);
        jobRun.setTotalCostPence(totalCostPence);
        jobRun.setTotalCostMicroDollars(totalCostMicroDollars);
        jobRunRepository.save(jobRun);
    }

    /**
     * Completes a job run with success and failure counts (overload without date tracking).
     *
     * @param jobRun   the job run to complete
     * @param succeeded number of successful evaluations
     * @param failed   number of failed evaluations
     */
    public void completeRun(JobRunEntity jobRun, int succeeded, int failed) {
        completeRun(jobRun, succeeded, failed, null);
    }

    /**
     * Creates a job run for an Anthropic Batch API submission.
     *
     * <p>The returned entity has {@code completedAt = null} (in progress).
     * Call {@link #updateBatchRunProgress} on each poll and {@link #completeBatchRun}
     * when the batch reaches a terminal state.
     *
     * @param requestCount total number of requests submitted in this batch
     * @param batchId      the Anthropic batch ID (e.g. {@code "msgbatch_01..."})
     * @return the newly created job run entity
     */
    public JobRunEntity startBatchRun(int requestCount, String batchId) {
        Double exchangeRate = null;
        try {
            exchangeRate = exchangeRateService.getCurrentRate();
        } catch (Exception e) {
            LOG.warn("Failed to fetch exchange rate for batch job run: {}", e.getMessage());
        }

        JobRunEntity jobRun = JobRunEntity.builder()
                .runType(RunType.SCHEDULED_BATCH)
                .startedAt(LocalDateTime.now(ZoneOffset.UTC))
                .locationsProcessed(requestCount)
                .succeeded(0)
                .failed(0)
                .totalCostPence(0)
                .triggeredManually(false)
                .exchangeRateGbpPerUsd(exchangeRate)
                .notes("Anthropic batch: " + batchId)
                .appVersion(appVersion)
                .build();
        return jobRunRepository.save(jobRun);
    }

    /**
     * Updates the progress counters on an in-progress batch job run.
     *
     * <p>Called on each poll while the batch is still processing.
     *
     * @param jobRunId  the job run ID
     * @param succeeded number of requests that have succeeded so far
     * @param failed    number of requests that have errored so far
     */
    public void updateBatchRunProgress(Long jobRunId, int succeeded, int failed) {
        jobRunRepository.findById(jobRunId).ifPresent(jobRun -> {
            jobRun.setSucceeded(succeeded);
            jobRun.setFailed(failed);
            jobRunRepository.save(jobRun);
        });
    }

    /**
     * Completes a batch job run with final success and failure counts.
     *
     * <p>Sets {@code completedAt} and {@code durationMs}. Does not query
     * {@code api_call_log} — batch runs do not log individual API calls.
     *
     * @param jobRunId  the job run ID
     * @param succeeded number of requests that succeeded
     * @param failed    number of requests that errored or were skipped
     */
    public void completeBatchRun(Long jobRunId, int succeeded, int failed) {
        completeBatchRun(jobRunId, succeeded, failed, 0L);
    }

    /**
     * Completes a batch job run with final success/failure counts and cost.
     *
     * <p>Sets {@code completedAt}, {@code durationMs}, and {@code totalCostMicroDollars}.
     * Does not query {@code api_call_log} — batch runs do not log individual API calls.
     *
     * @param jobRunId          the job run ID
     * @param succeeded         number of requests that succeeded
     * @param failed            number of requests that errored or were skipped
     * @param costMicroDollars  total cost in micro-dollars from the batch
     */
    public void completeBatchRun(Long jobRunId, int succeeded, int failed, long costMicroDollars) {
        jobRunRepository.findById(jobRunId).ifPresent(jobRun -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            jobRun.setCompletedAt(now);
            jobRun.setDurationMs(ChronoUnit.MILLIS.between(jobRun.getStartedAt(), now));
            jobRun.setSucceeded(succeeded);
            jobRun.setFailed(failed);
            jobRun.setTotalCostMicroDollars(costMicroDollars);
            jobRunRepository.save(jobRun);
        });
    }

    /**
     * Returns the batch entity linked to a given job run, if any.
     *
     * @param jobRunId the job run ID
     * @return the batch entity if found
     */
    public Optional<ForecastBatchEntity> getBatchForJobRun(Long jobRunId) {
        return forecastBatchRepository.findByJobRunId(jobRunId);
    }

    /**
     * Retrieves recent job runs for a given run type, ordered by start time descending.
     *
     * @param runType the run type to filter by
     * @param limit   maximum number of runs to return
     * @return list of recent job runs
     */
    public List<JobRunEntity> getRecentRuns(RunType runType, int limit) {
        return jobRunRepository.findByRunTypeOrderByStartedAtDesc(runType,
                PageRequest.of(0, Math.max(limit, 1)));
    }

    /**
     * Retrieves all API calls for a given job run.
     *
     * @param jobRunId the job run ID
     * @return list of API call logs ordered by call time
     */
    public List<ApiCallLogEntity> getApiCallsForRun(Long jobRunId) {
        return apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(jobRunId);
    }

    /**
     * Retrieves recent job runs by all run types, ordered by start time descending.
     *
     * @param limit maximum number of runs to return (across all run types)
     * @return list of recent job runs
     */
    public List<JobRunEntity> getRecentRunsAllTypes(int limit) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
        List<JobRunEntity> runs = jobRunRepository.findByStartedAtAfterOrderByStartedAtDesc(since);
        return runs.stream().limit(limit).toList();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
