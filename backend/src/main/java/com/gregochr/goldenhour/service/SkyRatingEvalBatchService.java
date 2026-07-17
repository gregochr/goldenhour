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
import com.gregochr.goldenhour.service.evaluation.SunsetEvaluationParser;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * API's 50% token discount and out-of-band execution.
 *
 * <p><b>Restart-safe by design.</b> Submission persists the batch id onto every run
 * ({@link SkyRatingEvalService#attachBatchId}) and returns immediately; a separate scheduled job,
 * {@link #reconcilePendingBatches()} (registered as {@code sky_rating_eval_batch_poll}), reloads
 * RUNNING runs each tick and finalises the ones whose batch has ended. This mirrors the forecast
 * pipeline's DB-backed {@code batch_result_polling} loop. The earlier design awaited and finalised
 * the batch on a fire-and-forget virtual thread, so a backend restart between submit and finish
 * orphaned the RUNNING rows forever (the batch id lived only on that thread's stack) — this class
 * exists in its current shape to close that gap.
 *
 * <p>The eval's domain concerns (run lifecycle, band-classify, result persistence, finalisation)
 * stay in {@link SkyRatingEvalService}; this class owns only the Batch API orchestration —
 * build → submit → reconcile → parse — and calls back into the service's package-private
 * {@link SkyRatingEvalService#persistResult} / {@link SkyRatingEvalService#finalise} so both paths
 * write the {@code sky_rating_eval_*} tables identically.
 *
 * <p>Result text is parsed with the same {@link SunsetEvaluationParser#parseEvaluation} the
 * synchronous scorer and the forecast batch handler use; the HAIKU strategy is pulled from the map
 * purely as a parser handle (no Claude call is made through it).
 */
@Service
public class SkyRatingEvalBatchService {

    private static final Logger LOG = LoggerFactory.getLogger(SkyRatingEvalBatchService.class);

    /** Custom-id prefix marking a sky-rating eval batch request: {@code e_<runId>_<fixtureIdx>_<runIndex>}. */
    private static final String CUSTOM_ID_PREFIX = "e";

    /** Scheduler job key for the reconciler — matches the seed row in V122 and the registered runnable. */
    public static final String POLL_JOB_KEY = "sky_rating_eval_batch_poll";

    private final SkyRatingEvalService evalService;
    private final SkyRatingEvalBatchClient batchClient;
    private final BatchRequestFactory batchRequestFactory;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final SunsetEvaluationParser parser;
    private final ObjectMapper objectMapper;
    private final boolean batchEnabled;
    private final Duration pollTimeout;

    /**
     * Constructs the batched eval service.
     *
     * @param evalService             owns run lifecycle + result persistence (shared with sync path)
     * @param batchClient             thin Anthropic Batch API wrapper
     * @param batchRequestFactory     builds per-fixture batch requests (same builder the pipeline uses)
     * @param dynamicSchedulerService the scheduler the weekly eval job and reconciler register with
     * @param parser                  parses raw Batch API text into evaluations
     * @param objectMapper            Jackson mapper threaded into the parser
     * @param batchEnabled            when true the weekly job submits via batch; else it runs sync
     * @param pollTimeoutSeconds      fail a still-unfinished batch once its runs are older than this
     *                                (defaults to the Batch API's 24 h guaranteed-completion window)
     */
    public SkyRatingEvalBatchService(SkyRatingEvalService evalService,
            SkyRatingEvalBatchClient batchClient,
            BatchRequestFactory batchRequestFactory,
            DynamicSchedulerService dynamicSchedulerService,
            SunsetEvaluationParser parser,
            ObjectMapper objectMapper,
            @Value("${photocast.eval.batch.enabled:true}") boolean batchEnabled,
            @Value("${photocast.eval.batch.poll-timeout-seconds:86400}") long pollTimeoutSeconds) {
        this.evalService = evalService;
        this.batchClient = batchClient;
        this.batchRequestFactory = batchRequestFactory;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.batchEnabled = batchEnabled;
        this.pollTimeout = Duration.ofSeconds(pollTimeoutSeconds);
    }

    /**
     * Registers the weekly eval job (batched or synchronous per config) and the batch reconciler.
     *
     * <p>The reconciler is registered unconditionally: its {@code sky_rating_eval_batch_poll} config
     * row is seeded ACTIVE (V122) and must have a target regardless of {@code batch.enabled}; when
     * batching is off there are simply no batches to reconcile, so each tick is a no-op. The weekly
     * job is seeded PAUSED (V118), so registering its runnable is harmless until an admin resumes it.
     */
    @PostConstruct
    void registerJob() {
        Runnable target = batchEnabled ? this::runScheduledBatched : evalService::runScheduled;
        dynamicSchedulerService.registerJobTarget(SkyRatingEvalService.JOB_KEY, target);
        dynamicSchedulerService.registerJobTarget(POLL_JOB_KEY, this::reconcilePendingBatches);
        LOG.info("Sky-rating eval weekly job registered ({} path); batch reconciler registered",
                batchEnabled ? "batched" : "synchronous");
    }

    /**
     * Scheduler entry point: creates one RUNNING run per {@link SkyRatingEvalService#SCHEDULED_MODELS
     * model}, submits all their fixture scorings as a single batch, persists the batch id on every
     * run, and returns at once. The {@link #reconcilePendingBatches() reconciler} drives the batch to
     * completion out of band, so no thread is pinned and a restart cannot lose the batch.
     *
     * @return the started runs, one per model (their final status is persisted by the reconciler)
     */
    public List<SkyRatingEvalRunEntity> runScheduledBatched() {
        List<RunContext> contexts = new ArrayList<>();
        for (EvaluationModel model : SkyRatingEvalService.SCHEDULED_MODELS) {
            SkyRatingEvalRunEntity run = evalService.startRun(
                    model, SkyRatingEvalTrigger.SCHEDULED, SkyRatingEvalService.DEFAULT_RUNS_PER_FIXTURE);
            contexts.add(new RunContext(run, model));
        }
        List<SkyRatingEvalRunEntity> runs = contexts.stream().map(RunContext::run).toList();

        List<BatchCreateParams.Request> requests = buildRequests(contexts);
        LOG.info("Sky-rating eval batch: built {} request(s) across {} model run(s)",
                requests.size(), contexts.size());

        String batchId;
        try {
            batchId = batchClient.submit(requests);
        } catch (RuntimeException e) {
            LOG.error("Sky-rating eval batch submission failed; marking all runs FAILED", e);
            contexts.forEach(ctx -> evalService.finalise(ctx.run(), ctx.agg(),
                    SkyRatingEvalStatus.FAILED, e.getMessage(), startMillis(ctx.run())));
            return runs;
        }

        // Persist the batch id on every run so the scheduled reconciler picks the batch up even if
        // this process restarts before it ends — no blocking await, no fire-and-forget thread.
        contexts.forEach(ctx -> evalService.attachBatchId(ctx.run(), batchId));
        LOG.info("Sky-rating eval batch {} submitted for {} run(s); reconciler will finalise them",
                batchId, runs.size());
        return runs;
    }

    /**
     * Scheduled reconciler tick ({@code sky_rating_eval_batch_poll}): reloads every RUNNING run,
     * groups them by batch id, and for each batch either finalises its runs (if the batch has ended)
     * or, once past the timeout, fails them. RUNNING runs without a batch id are left alone until
     * they age past the timeout, then failed as orphans.
     *
     * <p>Deliberately swallows all errors — a {@code scheduleWithFixedDelay} task that throws is not
     * rescheduled, so a single bad tick must never take the reconciler down. The common case (no
     * in-flight batches) returns after one indexed query.
     */
    public void reconcilePendingBatches() {
        List<SkyRatingEvalRunEntity> running;
        try {
            running = evalService.findRunning();
        } catch (RuntimeException e) {
            LOG.error("Sky-rating eval reconciler: failed to load RUNNING runs; skipping tick", e);
            return;
        }
        if (running.isEmpty()) {
            return;
        }

        Map<String, List<SkyRatingEvalRunEntity>> byBatch = new LinkedHashMap<>();
        for (SkyRatingEvalRunEntity run : running) {
            String batchId = run.getBatchId();
            if (batchId == null || batchId.isBlank()) {
                try {
                    reclaimOrphanWithoutBatch(run);
                } catch (RuntimeException e) {
                    LOG.error("Sky-rating eval reconciler: reclaiming orphan run {} failed; "
                            + "will retry next tick", run.getId(), e);
                }
            } else {
                byBatch.computeIfAbsent(batchId, k -> new ArrayList<>()).add(run);
            }
        }
        for (Map.Entry<String, List<SkyRatingEvalRunEntity>> entry : byBatch.entrySet()) {
            try {
                reconcileBatch(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                LOG.error("Sky-rating eval reconciler: batch {} failed; will retry next tick",
                        entry.getKey(), e);
            }
        }
    }

    /** Checks one batch's status and either finalises (ENDED) or fails-past-deadline its runs. */
    private void reconcileBatch(String batchId, List<SkyRatingEvalRunEntity> runs) {
        boolean ended;
        try {
            ended = batchClient.isEnded(batchId);
        } catch (RuntimeException e) {
            // Transient API error, or the batch was deleted/expired at Anthropic. Retry until the
            // deadline, then give up so the runs don't hang RUNNING forever.
            if (allPastDeadline(runs)) {
                LOG.error("Sky-rating eval batch {}: status check still failing past deadline; "
                        + "failing {} run(s)", batchId, runs.size(), e);
                failAll(runs, "Batch status check failed past deadline: " + e.getMessage());
            } else {
                LOG.warn("Sky-rating eval batch {}: status check failed, will retry next tick",
                        batchId, e);
            }
            return;
        }

        if (ended) {
            processResults(batchId, runs);
        } else if (allPastDeadline(runs)) {
            LOG.warn("Sky-rating eval batch {} did not end within {}; failing {} run(s)",
                    batchId, pollTimeout, runs.size());
            failAll(runs, "Batch did not end within " + pollTimeout);
        }
        // else: still processing within the deadline — leave RUNNING for a later tick.
    }

    /**
     * Collects an ended batch's results, persists each back to its (run, fixture, run-index) origin,
     * and finalises every run COMPLETED. Package-private so tests drive it with a stubbed client.
     *
     * @param batchId the ended Anthropic batch id
     * @param runs    the runs submitted under that batch (reloaded from the DB)
     */
    void processResults(String batchId, List<SkyRatingEvalRunEntity> runs) {
        Map<Long, RunContext> byRunId = new LinkedHashMap<>();
        for (SkyRatingEvalRunEntity run : runs) {
            // Clear any rows a previously-crashed reconcile of this batch left behind, so re-persisting
            // cannot duplicate child rows (which would corrupt the fixture trend averages).
            evalService.deleteResultsForRun(run.getId());
            byRunId.put(run.getId(), new RunContext(run, run.getModel()));
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

        byRunId.values().forEach(ctx -> evalService.finalise(
                ctx.run(), ctx.agg(), SkyRatingEvalStatus.COMPLETED, null, startMillis(ctx.run())));
        LOG.info("Sky-rating eval batch {} reconciled: {} scored, {} skipped, {} run(s) finalised",
                batchId, parsedOk, failed, runs.size());
    }

    /** Fails a RUNNING run that has aged past the timeout without ever recording a batch id. */
    private void reclaimOrphanWithoutBatch(SkyRatingEvalRunEntity run) {
        if (!pastDeadline(run)) {
            // Just started; the batch id lands within milliseconds. Leave it for the next tick.
            return;
        }
        LOG.warn("Sky-rating eval run {} is RUNNING with no batch id past the {} deadline; failing it",
                run.getId(), pollTimeout);
        evalService.finalise(run, new SkyRatingEvalService.Aggregate(), SkyRatingEvalStatus.FAILED,
                "Orphaned RUNNING run with no batch id (submission likely died before the id was "
                        + "persisted)", startMillis(run));
    }

    private void failAll(List<SkyRatingEvalRunEntity> runs, String message) {
        runs.forEach(run -> evalService.finalise(run, new SkyRatingEvalService.Aggregate(),
                SkyRatingEvalStatus.FAILED, message, startMillis(run)));
    }

    private boolean allPastDeadline(List<SkyRatingEvalRunEntity> runs) {
        return runs.stream().allMatch(this::pastDeadline);
    }

    private boolean pastDeadline(SkyRatingEvalRunEntity run) {
        return System.currentTimeMillis() > startMillis(run) + pollTimeout.toMillis();
    }

    private static long startMillis(SkyRatingEvalRunEntity run) {
        LocalDateTime startedAt = run.getStartedAt();
        return startedAt == null
                ? System.currentTimeMillis()
                : startedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
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
