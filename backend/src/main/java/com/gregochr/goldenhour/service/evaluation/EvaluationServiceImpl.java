package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.batch.BatchSubmissionService;
import com.gregochr.goldenhour.service.batch.BatchSubmitResult;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Production {@link EvaluationService}.
 *
 * <p>Wires the Anthropic transports to the per-task-type
 * {@link ResultHandler}s built in Pass 3.2.
 *
 * <p>For the batch path: builds requests via {@link BatchRequestFactory} (forecast)
 * or inline (aurora — different prompt builder), then delegates to
 * {@link BatchSubmissionService#submit}. Result processing happens later via
 * {@link com.gregochr.goldenhour.service.batch.BatchPollingService} → {@link
 * com.gregochr.goldenhour.service.batch.BatchResultProcessor}, which dispatches to the
 * same handlers — so the engine doesn't poll itself.
 *
 * <p>For the synchronous path: builds the same prompts, calls
 * {@link AnthropicApiClient#createMessage}, drains the SDK response into a
 * {@link ClaudeSyncOutcome}, and dispatches to the right handler in one method call.
 */
@Service
public class EvaluationServiceImpl implements EvaluationService {

    private static final Logger LOG = LoggerFactory.getLogger(EvaluationServiceImpl.class);

    private final BatchSubmissionService batchSubmissionService;
    private final BatchRequestFactory batchRequestFactory;
    private final AnthropicApiClient anthropicApiClient;
    private final ClaudeAuroraInterpreter claudeAuroraInterpreter;
    private final JobRunService jobRunService;
    private final Map<Class<? extends EvaluationTask>, ResultHandler<?>> handlersByType;

    /**
     * Constructs the engine.
     *
     * @param batchSubmissionService     unified Anthropic batch submitter
     * @param batchRequestFactory        builds forecast batch requests
     * @param anthropicApiClient         resilient sync Anthropic client
     * @param claudeAuroraInterpreter    aurora system prompt + user message + parser
     * @param jobRunService              creates job runs for the sync path
     * @param resultHandlers             all available {@link ResultHandler} beans;
     *                                   indexed by {@link ResultHandler#taskType}
     */
    public EvaluationServiceImpl(BatchSubmissionService batchSubmissionService,
            BatchRequestFactory batchRequestFactory,
            AnthropicApiClient anthropicApiClient,
            ClaudeAuroraInterpreter claudeAuroraInterpreter,
            JobRunService jobRunService,
            List<ResultHandler<?>> resultHandlers) {
        this.batchSubmissionService = batchSubmissionService;
        this.batchRequestFactory = batchRequestFactory;
        this.anthropicApiClient = anthropicApiClient;
        this.claudeAuroraInterpreter = claudeAuroraInterpreter;
        this.jobRunService = jobRunService;
        this.handlersByType = new java.util.HashMap<>();
        for (ResultHandler<?> handler : resultHandlers) {
            handlersByType.put(handler.taskType(), handler);
        }
    }

    @Override
    public EvaluationHandle submit(List<? extends EvaluationTask> tasks,
            BatchTriggerSource trigger) {
        if (tasks == null || tasks.isEmpty()) {
            return EvaluationHandle.empty();
        }
        java.util.Objects.requireNonNull(trigger, "trigger");

        Class<? extends EvaluationTask> firstType = tasks.get(0).getClass();
        for (EvaluationTask t : tasks) {
            if (!firstType.equals(t.getClass())) {
                throw new IllegalArgumentException(
                        "Mixed-type submit not supported; first=" + firstType.getSimpleName()
                                + " other=" + t.getClass().getSimpleName());
            }
        }

        if (firstType == EvaluationTask.Forecast.class) {
            return submitForecast(castList(tasks, EvaluationTask.Forecast.class), trigger);
        }
        if (firstType == EvaluationTask.Aurora.class) {
            return submitAurora(castList(tasks, EvaluationTask.Aurora.class), trigger);
        }
        throw new IllegalStateException("Unhandled task type: " + firstType);
    }

    @Override
    public EvaluationResult evaluateNow(EvaluationTask task, BatchTriggerSource trigger) {
        java.util.Objects.requireNonNull(task, "task");
        java.util.Objects.requireNonNull(trigger, "trigger");

        return switch (task) {
            case EvaluationTask.Forecast forecast -> evaluateNowForecast(forecast, trigger);
            case EvaluationTask.Aurora aurora -> evaluateNowAurora(aurora, trigger);
        };
    }

    private EvaluationHandle submitForecast(List<EvaluationTask.Forecast> tasks,
            BatchTriggerSource trigger) {
        List<BatchCreateParams.Request> requests = new ArrayList<>(tasks.size());
        for (EvaluationTask.Forecast task : tasks) {
            String customId = CustomIdFactory.forForecast(
                    task.location().getId(), task.date(), task.targetType());
            requests.add(batchRequestFactory.buildForecastRequest(
                    customId, task.model(), task.data(), task.model().getMaxTokens()));
        }
        BatchSubmitResult result = batchSubmissionService.submit(
                requests, BatchType.FORECAST, trigger,
                "EvaluationService forecast (" + trigger + ")");
        return result == null
                ? EvaluationHandle.empty()
                : new EvaluationHandle(null, result.batchId(), result.requestCount());
    }

    private EvaluationHandle submitAurora(List<EvaluationTask.Aurora> tasks,
            BatchTriggerSource trigger) {
        // Aurora submission is "one task = one batch request listing all locations" by
        // design — see EvaluationTask.Aurora javadoc. We build N independent requests if
        // the caller hands us N tasks, but in practice production always passes a single
        // task because the batch entity is single-row per cron firing.
        List<BatchCreateParams.Request> requests = new ArrayList<>(tasks.size());
        for (EvaluationTask.Aurora task : tasks) {
            String customId = CustomIdFactory.forAurora(task.alertLevel(), task.date());
            String userMessage = claudeAuroraInterpreter.buildUserMessage(
                    task.alertLevel(), task.viableLocations(), task.cloudByLocation(),
                    task.spaceWeather(), task.triggerType(), task.tonightWindow());
            requests.add(BatchCreateParams.Request.builder()
                    .customId(customId)
                    .params(BatchCreateParams.Request.Params.builder()
                            .model(task.model().getModelId())
                            .maxTokens(1024)
                            .addUserMessage(userMessage)
                            .build())
                    .build());
        }
        BatchSubmitResult result = batchSubmissionService.submit(
                requests, BatchType.AURORA, trigger,
                "EvaluationService aurora (" + trigger + ")");
        return result == null
                ? EvaluationHandle.empty()
                : new EvaluationHandle(null, result.batchId(), result.requestCount());
    }

    private EvaluationResult evaluateNowForecast(EvaluationTask.Forecast task,
            BatchTriggerSource trigger) {
        ForecastResultHandler handler = (ForecastResultHandler)
                handlersByType.get(EvaluationTask.Forecast.class);
        if (handler == null) {
            throw new IllegalStateException("No ForecastResultHandler registered");
        }
        JobRunEntity jobRun = startSyncJobRun(RunType.SHORT_TERM, task.model());
        ResultContext context = ResultContext.forSync(
                jobRun != null ? jobRun.getId() : null, trigger);

        long start = System.currentTimeMillis();
        ClaudeSyncOutcome outcome;
        try {
            // Build the prompt the same way as the batch path: select coastal/inland by
            // tide presence, surge-overload by surge presence, system block with cache
            // control. Reuse BatchRequestFactory's selector to avoid drift.
            PromptBuilder builder = batchRequestFactory.selectBuilder(task.data());
            String userMessage = task.data().surge() != null
                    ? builder.buildUserMessage(task.data(), task.data().surge(),
                            task.data().adjustedRangeMetres(),
                            task.data().astronomicalRangeMetres())
                    : builder.buildUserMessage(task.data());
            Message response = anthropicApiClient.createMessage(
                    MessageCreateParams.builder()
                            .model(task.model().getModelId())
                            .maxTokens(task.model().getMaxTokens())
                            .systemOfTextBlockParams(List.of(
                                    TextBlockParam.builder()
                                            .text(builder.getSystemPrompt())
                                            .cacheControl(CacheControlEphemeral.builder().build())
                                            .build()))
                            .outputConfig(builder.buildOutputConfig())
                            .addUserMessage(userMessage)
                            .build());
            String text = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Claude returned no text"));
            outcome = ClaudeSyncOutcome.success(text, extractTokens(response),
                    task.model(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            LOG.warn("evaluateNow forecast {} failed: {}", task.taskKey(), e.getMessage());
            outcome = ClaudeSyncOutcome.failure(classifyError(e), e.getMessage(),
                    task.model(), System.currentTimeMillis() - start);
        }

        EvaluationResult result = handler.handleSyncResult(task, outcome, context);
        completeSyncJobRun(jobRun, outcome.succeeded());
        return result;
    }

    private EvaluationResult evaluateNowAurora(EvaluationTask.Aurora task,
            BatchTriggerSource trigger) {
        AuroraResultHandler handler = (AuroraResultHandler)
                handlersByType.get(EvaluationTask.Aurora.class);
        if (handler == null) {
            throw new IllegalStateException("No AuroraResultHandler registered");
        }
        JobRunEntity jobRun = startSyncJobRun(RunType.AURORA_EVALUATION, task.model());
        ResultContext context = ResultContext.forSync(
                jobRun != null ? jobRun.getId() : null, trigger);

        long start = System.currentTimeMillis();
        ClaudeSyncOutcome outcome;
        try {
            String userMessage = claudeAuroraInterpreter.buildUserMessage(
                    task.alertLevel(), task.viableLocations(), task.cloudByLocation(),
                    task.spaceWeather(), task.triggerType(), task.tonightWindow());
            Message response = anthropicApiClient.createMessage(
                    MessageCreateParams.builder()
                            .model(task.model().getModelId())
                            .maxTokens(1024)
                            .addUserMessage(userMessage)
                            .build());
            String text = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Claude returned no text"));
            outcome = ClaudeSyncOutcome.success(text, extractTokens(response),
                    task.model(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            LOG.warn("evaluateNow aurora {} failed: {}", task.taskKey(), e.getMessage());
            outcome = ClaudeSyncOutcome.failure(classifyError(e), e.getMessage(),
                    task.model(), System.currentTimeMillis() - start);
        }

        EvaluationResult result = handler.handleSyncResult(task, outcome, context);
        completeSyncJobRun(jobRun, outcome.succeeded());
        return result;
    }

    private JobRunEntity startSyncJobRun(RunType runType,
            com.gregochr.goldenhour.entity.EvaluationModel model) {
        try {
            return jobRunService.startRun(runType, false, model);
        } catch (Exception e) {
            LOG.warn("evaluateNow: failed to start job run: {}", e.getMessage());
            return null;
        }
    }

    private void completeSyncJobRun(JobRunEntity jobRun, boolean succeeded) {
        if (jobRun == null) {
            return;
        }
        try {
            jobRunService.completeRun(jobRun, succeeded ? 1 : 0, succeeded ? 0 : 1);
        } catch (Exception e) {
            LOG.warn("evaluateNow: failed to complete job run {}: {}",
                    jobRun.getId(), e.getMessage());
        }
    }

    private TokenUsage extractTokens(Message response) {
        var u = response.usage();
        return new TokenUsage(
                u.inputTokens(),
                u.outputTokens(),
                u.cacheCreationInputTokens().orElse(0L),
                u.cacheReadInputTokens().orElse(0L));
    }

    private String classifyError(Exception e) {
        if (e instanceof com.anthropic.errors.AnthropicServiceException svc) {
            return "anthropic_" + svc.statusCode();
        }
        return e.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static <T extends EvaluationTask> List<T> castList(
            List<? extends EvaluationTask> tasks, Class<T> type) {
        return (List<T>) tasks;
    }
}
