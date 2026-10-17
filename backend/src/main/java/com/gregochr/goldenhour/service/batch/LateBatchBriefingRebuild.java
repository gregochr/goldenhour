package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.service.BriefingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Rebuilds the briefing when a forecast batch lands <em>after</em> its pipeline cycle has already
 * finished with it (solar-relative-refresh-plan B1).
 *
 * <p>A late batch's ratings go live the moment it lands (they are re-enriched from
 * {@code cached_evaluation} on every serve), but everything built once per briefing, the gloss
 * prose and the best-bet picks, is not: the only {@code refreshBriefing()} call is the
 * orchestrator's, and a cycle that timed out on its safety budget never reaches it. This closes that
 * gap.
 *
 * <p>The rebuild is requested when <em>all</em> of these hold, and only then:
 * <ul>
 *   <li>the batch is a FORECAST batch (aurora batches have their own path) that carries a
 *       {@code pipeline_run_id} (an ad-hoc or force-submitted batch triggers nothing);</li>
 *   <li>the batch's own status is already terminal (the caller invokes this after the result
 *       processor has persisted it);</li>
 *   <li>its pipeline run exists and is not {@code RUNNING}: a running cycle rebuilds the briefing
 *       itself when its wait ends, so a rebuild here would be a duplicate;</li>
 *   <li>no other FORECAST batch of the run is still outside {@link BatchStatus#TERMINAL}: the
 *       rebuild waits for the last one, so one slow batch among several causes one rebuild, not one
 *       per batch.</li>
 * </ul>
 *
 * <p>It asks for {@link BriefingService#refreshBriefingIfIdle()}, which neither waits for nor runs
 * alongside another build: a refused request is logged at INFO and not retried (the next cycle
 * rebuilds anyway). That is also the only guard against two runs' late batches landing together;
 * the second one is simply refused. <b>It never</b> persists the cycle's picks
 * ({@code persistPicksForCycle} belongs to the orchestrator's own tail), never dispatches the Ask
 * Ready precompute or the almanac refresh (those are the orchestrator's too), and never changes
 * {@code pipeline_run} state: it reads the run and nothing else.
 *
 * <p>The rebuild takes minutes (weather, gloss and best-bet Claude calls), so it is handed to a
 * background executor and never runs on the batch poller's thread. Nothing this class does can
 * throw into the poller.
 */
@Component
public class LateBatchBriefingRebuild {

    private static final Logger LOG = LoggerFactory.getLogger(LateBatchBriefingRebuild.class);

    private final PipelineRunRepository pipelineRunRepository;
    private final ForecastBatchRepository forecastBatchRepository;
    private final BriefingService briefingService;
    private final Executor backgroundExecutor;

    /**
     * Production constructor: rebuilds run on virtual threads.
     *
     * @param pipelineRunRepository   pipeline run lookups
     * @param forecastBatchRepository forecast batch lookups
     * @param briefingService         the briefing builder
     */
    @Autowired
    public LateBatchBriefingRebuild(PipelineRunRepository pipelineRunRepository,
            ForecastBatchRepository forecastBatchRepository, BriefingService briefingService) {
        this(pipelineRunRepository, forecastBatchRepository, briefingService,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Constructor for tests that need a deterministic executor.
     *
     * @param pipelineRunRepository   pipeline run lookups
     * @param forecastBatchRepository forecast batch lookups
     * @param briefingService         the briefing builder
     * @param backgroundExecutor      where the rebuild runs
     */
    LateBatchBriefingRebuild(PipelineRunRepository pipelineRunRepository,
            ForecastBatchRepository forecastBatchRepository, BriefingService briefingService,
            Executor backgroundExecutor) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.forecastBatchRepository = forecastBatchRepository;
        this.briefingService = briefingService;
        this.backgroundExecutor = backgroundExecutor;
    }

    /**
     * Requests a briefing rebuild if this batch was the last of a finished cycle's batches to end.
     * Never throws.
     *
     * @param batch a batch whose result processing has just returned, its status already persisted
     */
    public void onBatchEnded(ForecastBatchEntity batch) {
        try {
            if (shouldRebuild(batch)) {
                Long runId = batch.getPipelineRunId();
                LOG.info("Batch {} was the last of pipeline run {}'s forecast batches to end after "
                        + "the run finished — requesting a briefing rebuild",
                        batch.getAnthropicBatchId(), runId);
                backgroundExecutor.execute(() -> rebuild(runId));
            }
        } catch (RuntimeException e) {
            LOG.warn("Late-batch briefing rebuild check failed for batch {}: {}",
                    batch.getAnthropicBatchId(), e.getMessage());
        }
    }

    private boolean shouldRebuild(ForecastBatchEntity batch) {
        Long runId = batch.getPipelineRunId();
        if (batch.getBatchType() != BatchType.FORECAST || runId == null
                || !BatchStatus.TERMINAL.contains(batch.getStatus())) {
            return false;
        }
        boolean runFinished = pipelineRunRepository.findById(runId)
                .map(run -> run.getStatus() != PipelineRunStatus.RUNNING)
                .orElse(false);
        if (!runFinished) {
            return false;
        }
        List<ForecastBatchEntity> siblings = forecastBatchRepository.findByPipelineRunId(runId);
        return siblings.stream()
                .filter(b -> b.getBatchType() == BatchType.FORECAST)
                .filter(b -> b != batch && (b.getId() == null || !b.getId().equals(batch.getId())))
                .allMatch(b -> BatchStatus.TERMINAL.contains(b.getStatus()));
    }

    private void rebuild(Long runId) {
        try {
            if (briefingService.refreshBriefingIfIdle()) {
                LOG.info("Briefing rebuilt after the late batches of pipeline run {} landed", runId);
            } else {
                LOG.info("Briefing rebuild after the late batches of pipeline run {} refused: a "
                        + "build is already running; not retried", runId);
            }
        } catch (RuntimeException e) {
            LOG.warn("Briefing rebuild after the late batches of pipeline run {} failed: {}",
                    runId, e.getMessage(), e);
        }
    }
}
