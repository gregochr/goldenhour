package com.gregochr.goldenhour.service;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixture;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.BatchRequestFactory;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.ClaudeEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batched execution layer for the weekly multi-model sky-rating eval.
 *
 * <p>The synchronous {@link SkyRatingEvalService#runScheduled()} fires {@code fixtures × runs ×
 * models} (≈144) real-time Claude calls. This submits the identical set as one Anthropic
 * <em>message batch</em> — same fixtures, same per-model runs, same persisted rows — for the Batch
 * API's 50% token discount and out-of-band execution. The per-model runs are the unit the
 * deferred-batching note in the multi-model eval always intended to submit.
 *
 * <p>The eval's domain concerns (run lifecycle, band-classify, result persistence, finalisation)
 * stay in {@link SkyRatingEvalService}; this class owns only the Batch API orchestration —
 * build → submit → await → parse — and calls back into the service's package-private
 * {@link SkyRatingEvalService#persistResult} / {@link SkyRatingEvalService#finalise} so both paths
 * write the {@code sky_rating_eval_*} tables identically.
 *
 * <p>Result text is parsed with the same {@link ClaudeEvaluationStrategy#parseEvaluation} the
 * synchronous scorer and the forecast batch handler use; the HAIKU strategy is pulled from the map
 * purely as a parser handle (no Claude call is made through it).
 */
@Service
public class SkyRatingEvalBatchService {

    private static final Logger LOG = LoggerFactory.getLogger(SkyRatingEvalBatchService.class);

    /** Custom-id prefix marking a sky-rating eval batch request: {@code e_<runId>_<fixtureIdx>_<runIndex>}. */
    private static final String CUSTOM_ID_PREFIX = "e";

    private final SkyRatingEvalService evalService;
    private final SkyRatingEvalBatchClient batchClient;
    private final BatchRequestFactory batchRequestFactory;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final ClaudeEvaluationStrategy parser;
    private final ObjectMapper objectMapper;
    private final boolean batchEnabled;
    private final Duration pollTimeout;
    private final Duration pollInterval;

    /**
     * Constructs the batched eval service.
     *
     * @param evalService             owns run lifecycle + result persistence (shared with sync path)
     * @param batchClient             thin Anthropic Batch API wrapper
     * @param batchRequestFactory     builds per-fixture batch requests (same builder the pipeline uses)
     * @param dynamicSchedulerService the scheduler the weekly eval job registers with
     * @param evaluationStrategies    the strategy map; only HAIKU's {@code parseEvaluation} is borrowed
     * @param objectMapper            Jackson mapper threaded into the parser
     * @param batchEnabled            when true the weekly job submits via batch; else it runs sync
     * @param pollTimeoutSeconds      give up awaiting the batch after this many seconds
     * @param pollIntervalSeconds     seconds between batch status checks
     */
    public SkyRatingEvalBatchService(SkyRatingEvalService evalService,
            SkyRatingEvalBatchClient batchClient,
            BatchRequestFactory batchRequestFactory,
            DynamicSchedulerService dynamicSchedulerService,
            Map<EvaluationModel, EvaluationStrategy> evaluationStrategies,
            ObjectMapper objectMapper,
            @Value("${photocast.eval.batch.enabled:true}") boolean batchEnabled,
            @Value("${photocast.eval.batch.poll-timeout-seconds:3600}") long pollTimeoutSeconds,
            @Value("${photocast.eval.batch.poll-interval-seconds:20}") long pollIntervalSeconds) {
        this.evalService = evalService;
        this.batchClient = batchClient;
        this.batchRequestFactory = batchRequestFactory;
        this.dynamicSchedulerService = dynamicSchedulerService;
        EvaluationStrategy haiku = evaluationStrategies.get(EvaluationModel.HAIKU);
        if (!(haiku instanceof ClaudeEvaluationStrategy claude)) {
            throw new IllegalStateException(
                    "SkyRatingEvalBatchService requires a ClaudeEvaluationStrategy bean for HAIKU "
                            + "to reuse its JSON parser; got " + haiku);
        }
        this.parser = claude;
        this.objectMapper = objectMapper;
        this.batchEnabled = batchEnabled;
        this.pollTimeout = Duration.ofSeconds(pollTimeoutSeconds);
        this.pollInterval = Duration.ofSeconds(pollIntervalSeconds);
    }

    /**
     * Registers the weekly eval job, routing it to the batched path when
     * {@code photocast.eval.batch.enabled} is true (default) and to the synchronous path otherwise.
     * The job is seeded PAUSED (V118), so registering the runnable is harmless until an admin
     * resumes it from the Scheduler UI.
     */
    @PostConstruct
    void registerJob() {
        Runnable target = batchEnabled ? this::runScheduledBatched : evalService::runScheduled;
        dynamicSchedulerService.registerJobTarget(SkyRatingEvalService.JOB_KEY, target);
        LOG.info("Sky-rating eval weekly job registered ({} path)",
                batchEnabled ? "batched" : "synchronous");
    }

    /**
     * Scheduler entry point: creates one RUNNING run per {@link SkyRatingEvalService#SCHEDULED_MODELS
     * model}, submits all their fixture scorings as a single batch, then processes results on a
     * background virtual thread (the batch can take minutes; the scheduler thread returns at once).
     *
     * @return the started runs, one per model (their final status is persisted asynchronously)
     */
    public List<SkyRatingEvalRunEntity> runScheduledBatched() {
        Map<Long, RunContext> byRunId = new LinkedHashMap<>();
        for (EvaluationModel model : SkyRatingEvalService.SCHEDULED_MODELS) {
            SkyRatingEvalRunEntity run = evalService.startRun(
                    model, SkyRatingEvalTrigger.SCHEDULED, SkyRatingEvalService.DEFAULT_RUNS_PER_FIXTURE);
            byRunId.put(run.getId(), new RunContext(run, model));
        }
        List<SkyRatingEvalRunEntity> runs =
                byRunId.values().stream().map(RunContext::run).toList();

        List<BatchCreateParams.Request> requests = buildRequests(byRunId.values());
        LOG.info("Sky-rating eval batch: built {} request(s) across {} model run(s)",
                requests.size(), byRunId.size());

        String batchId;
        try {
            batchId = batchClient.submit(requests);
        } catch (RuntimeException e) {
            LOG.error("Sky-rating eval batch submission failed; marking all runs FAILED", e);
            long now = System.currentTimeMillis();
            byRunId.values().forEach(ctx -> evalService.finalise(
                    ctx.run(), ctx.agg(), SkyRatingEvalStatus.FAILED, e.getMessage(), now));
            return runs;
        }

        Thread.ofVirtual().name("sky-eval-batch-" + batchId).start(() -> {
            try {
                processBatch(batchId, byRunId, System.currentTimeMillis());
            } catch (RuntimeException e) {
                LOG.error("Sky-rating eval batch {} processing failed", batchId, e);
            }
        });
        return runs;
    }

    /**
     * Awaits the batch, parses every result back to its (run, fixture, run-index) origin, persists
     * each via {@link SkyRatingEvalService#persistResult}, and finalises every run. Package-private
     * so tests can drive the full result-handling path synchronously with a stubbed batch client.
     *
     * @param batchId the submitted Anthropic batch id
     * @param byRunId the run contexts keyed by run id
     * @param startMs wall-clock start used for each run's duration
     */
    void processBatch(String batchId, Map<Long, RunContext> byRunId, long startMs) {
        if (!batchClient.awaitEnded(batchId, pollTimeout, pollInterval)) {
            byRunId.values().forEach(ctx -> evalService.finalise(ctx.run(), ctx.agg(),
                    SkyRatingEvalStatus.FAILED, "Batch did not end within " + pollTimeout, startMs));
            return;
        }

        int parsedOk = 0;
        int failed = 0;
        for (ClaudeBatchOutcome outcome : batchClient.collectResults(batchId)) {
            ResultRef ref = parseCustomId(outcome.customId());
            if (ref == null) {
                LOG.warn("Sky-rating eval batch {}: unparseable customId '{}', skipping",
                        batchId, outcome.customId());
                failed++;
                continue;
            }
            RunContext ctx = byRunId.get(ref.runId());
            if (ctx == null || ref.fixtureIdx() >= SkyRatingEvalFixtures.ALL.size()) {
                LOG.warn("Sky-rating eval batch {}: customId '{}' has no live run/fixture, skipping",
                        batchId, outcome.customId());
                failed++;
                continue;
            }
            if (!outcome.succeeded()) {
                LOG.warn("Sky-rating eval batch {}: request '{}' failed ({}), skipping",
                        batchId, outcome.customId(), outcome.status());
                failed++;
                continue;
            }
            SkyRatingEvalFixture fixture = SkyRatingEvalFixtures.ALL.get(ref.fixtureIdx());
            SunsetEvaluation eval = parser.parseEvaluation(outcome.rawText(), objectMapper);
            evalService.persistResult(ctx.run(), fixture, ref.runIndex(), eval,
                    outcome.tokenUsage(), null, true, ctx.agg());
            parsedOk++;
        }

        byRunId.values().forEach(ctx ->
                evalService.finalise(ctx.run(), ctx.agg(), SkyRatingEvalStatus.COMPLETED, null, startMs));
        LOG.info("Sky-rating eval batch {} processed: {} scored, {} skipped, {} run(s) finalised",
                batchId, parsedOk, failed, byRunId.size());
    }

    /** Builds the full request set: every (run × fixture × run-index) as one batch request. */
    private List<BatchCreateParams.Request> buildRequests(Iterable<RunContext> contexts) {
        // Fixtures load identically for every model, so load each once and reuse.
        Map<Integer, AtmosphericData> dataByFixture = new LinkedHashMap<>();
        for (int i = 0; i < SkyRatingEvalFixtures.ALL.size(); i++) {
            dataByFixture.put(i, SkyRatingEvalFixtures.load(SkyRatingEvalFixtures.ALL.get(i)));
        }

        List<BatchCreateParams.Request> requests = new ArrayList<>();
        for (RunContext ctx : contexts) {
            int runsPerFixture = ctx.run().getRunsPerFixture();
            for (int fixtureIdx = 0; fixtureIdx < SkyRatingEvalFixtures.ALL.size(); fixtureIdx++) {
                AtmosphericData data = dataByFixture.get(fixtureIdx);
                for (int runIndex = 1; runIndex <= runsPerFixture; runIndex++) {
                    String customId = customId(ctx.run().getId(), fixtureIdx, runIndex);
                    requests.add(batchRequestFactory.buildForecastRequest(
                            customId, ctx.model(), data, ctx.model().getMaxTokens()));
                }
            }
        }
        return requests;
    }

    /** Builds the {@code e_<runId>_<fixtureIdx>_<runIndex>} custom id. */
    static String customId(long runId, int fixtureIdx, int runIndex) {
        return CUSTOM_ID_PREFIX + "_" + runId + "_" + fixtureIdx + "_" + runIndex;
    }

    /** Parses a custom id back to its origin, or {@code null} if it is not a well-formed eval id. */
    static ResultRef parseCustomId(String customId) {
        if (customId == null) {
            return null;
        }
        String[] parts = customId.split("_");
        if (parts.length != 4 || !CUSTOM_ID_PREFIX.equals(parts[0])) {
            return null;
        }
        try {
            return new ResultRef(
                    Long.parseLong(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A run plus the accumulator threaded through its results to finalisation. */
    record RunContext(SkyRatingEvalRunEntity run, EvaluationModel model,
            SkyRatingEvalService.Aggregate agg) {
        RunContext(SkyRatingEvalRunEntity run, EvaluationModel model) {
            this(run, model, new SkyRatingEvalService.Aggregate());
        }
    }

    /** The (run, fixture, run-index) origin decoded from a result's custom id. */
    record ResultRef(long runId, int fixtureIdx, int runIndex) {
    }
}
