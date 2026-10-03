package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.model.BatchCallOutcome;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.model.CycleDisposition;
import com.gregochr.goldenhour.model.CycleScoredComponent;
import com.gregochr.goldenhour.model.CyclePlaceEvidence;
import com.gregochr.goldenhour.model.CyclePlaceEvidence.Lane;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.ParsedCustomId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reduces what one scheduled pipeline cycle recorded about each place to a
 * {@link CyclePlaceEvidence}: whether it was triaged, whether collection errored for it, and in
 * which result lanes (sky, bluebell, woodland) it got a good or a failed Claude result.
 *
 * <p>Two recorded sources are read, both keyed to the cycle. A cycle's batches (retry batch
 * included) carry its {@code pipeline_run_id} on their {@code forecast_batch} rows; and the job run
 * its dispositions were persisted on is recorded durably on the pipeline run
 * ({@code pipeline_run.disposition_job_run_id}), which is what finds a batchless cycle's anchor run
 * (see below):
 *
 * <ul>
 *   <li><b>{@code forecast_run_disposition}</b>, what collection decided per slot, anchored on the
 *       job run of the cycle's first submitted batch or, when no batch was submitted, on a
 *       disposition-only anchor run, so the job runs of the cycle's batches plus the remembered
 *       anchor are searched. {@code SKIPPED_TRIAGED} means the pipeline judged the weather and answered (the
 *       collector writes it, and so does {@code BatchRetryService} when a retry's fresh weather
 *       stands the slot down); {@code SKIPPED_ERROR} is the collector's catch-all for an exception
 *       in its loop. Every other category is no evidence either way:
 *       {@code EVALUATED}/{@code FORCE_EVALUATED} only say a request was queued, and
 *       {@code SUBMISSION_FAILED}, {@code SKIPPED_CACHED}, {@code SKIPPED_PAST_DATE},
 *       {@code SKIPPED_TRAVEL_DAY}, {@code SKIPPED_HARD_CONSTRAINT}, {@code SKIPPED_STABILITY},
 *       {@code SKIPPED_NO_PROMPT}, {@code SKIPPED_UNKNOWN_LOCATION} and
 *       {@code SKIPPED_NO_REFRESH_NEEDED} mean nothing was sent for the slot.</li>
 *   <li><b>{@code api_call_log}</b>, one row per individual batch result, with the request's
 *       {@code custom_id} (which names the place and, by its prefix, the lane) and whether it
 *       {@code succeeded}.</li>
 * </ul>
 *
 * <p><b>Success evidence has two independent sources, and failure evidence has one.</b> The
 * {@code api_call_log} rows are best-effort audit: {@code BatchSubmissionService} deliberately keeps a
 * submitted batch whose job-run bookkeeping failed (null {@code jobRunId}), {@code
 * ForecastResultHandler.persistBatchLog} then skips every result log for it, and an individual log
 * write that fails is swallowed. A place that really was scored would then have no success row. So
 * successes are also read from {@code forecast_score}, which every scored result writes through
 * {@code ForecastScoreWriter} stamped with the producing cycle's {@code pipeline_run_id}, whatever
 * happened to the job run: {@code SKY}, {@code FIERY_SKY} and {@code GOLDEN_HOUR} rows mean a sky
 * success, {@code BLUEBELL} a bluebell success, {@code WOODLAND} a woodland success. ({@code TIDAL}
 * and {@code INVERSION} rows are written from more than one lane and name none, so they are ignored.)
 * The two sources are unioned. {@code forecast_score} is itself a secondary write (flag-gated,
 * skipped for a superseded result, failure-swallowed), which is why it is a union and not a
 * replacement. {@code forecast_evaluation} was considered and rejected: its scored {@code PENDING}
 * row carries no pipeline run or batch to key it to the cycle; {@code cached_evaluation} was rejected
 * because its per-region JSON is name-keyed and lane-less, and would need the cycle's trigger time
 * and a deserialise per region.
 *
 * <p><b>The asymmetry is deliberate: a place is FAILED only on positive failure evidence</b> (a
 * {@code SKIPPED_ERROR} disposition or a failed result row). Missing success evidence alone never
 * makes a place failed, so a bookkeeping gap can only under-count, never wrongly disable.
 *
 * <p>The result is deliberately raw, per lane: {@code LocationFailureService} compares a failure
 * only with like evidence (the same lane for a Claude failure), because a fault confined to one
 * lane, a woodland parser regression for instance, must not be diluted by the many places that
 * scored or were triaged in other lanes. A stability skip is not a success: the weather was
 * fetched, but the place was not judged.
 *
 * <p><b>The batchless cycle.</b> A cycle that submits no batch (everything cached, skipped or
 * triaged away, or every submission failed, the 2026-09-29 shape) has no {@code forecast_batch}
 * row, and its dispositions sit on an anchor job run that no {@code job_run} column ties to the
 * pipeline run (and the link is not parsed out of the free-text {@code notes}). The pipeline run
 * itself records the job run its dispositions were persisted onto
 * ({@code pipeline_run.disposition_job_run_id}, written by
 * {@code ScheduledBatchEvaluationService}), so such a cycle still resolves, <b>including after a
 * restart</b>: a cycle that legitimately triaged candidates away shows them as triaged, and its
 * places get through and are reset, while a cycle whose submissions all failed shows only
 * {@code SUBMISSION_FAILED} rows, which are no evidence either way, so it still counts nobody. The
 * only way the link is absent is a failed best-effort write of it, which resolves the cycle from
 * its batches alone and so under-counts.
 *
 * <p><b>Known gap.</b> A bluebell or woodland request that failed is never retried
 * ({@code BatchRetryService.selectFailures} keeps only sky ids), so such a failure stands for the
 * cycle.
 */
@Service
public class CycleLocationOutcomeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(CycleLocationOutcomeResolver.class);

    /**
     * The result lane each {@code forecast_score} product identifies. TIDAL and INVERSION are
     * deliberately absent: they are written from more than one lane.
     */
    private static final Map<Long, Lane> SCORED_LANE_BY_TYPE_ID = Map.of(
            ForecastType.SKY.getId(), Lane.SKY,
            ForecastType.FIERY_SKY.getId(), Lane.SKY,
            ForecastType.GOLDEN_HOUR.getId(), Lane.SKY,
            ForecastType.BLUEBELL.getId(), Lane.BLUEBELL,
            ForecastType.WOODLAND.getId(), Lane.WOODLAND);

    private final ForecastBatchRepository forecastBatchRepository;
    private final ForecastRunDispositionRepository dispositionRepository;
    private final ApiCallLogRepository apiCallLogRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final ForecastScoreRepository forecastScoreRepository;

    /**
     * Constructs the resolver.
     *
     * @param forecastBatchRepository batches tagged with a pipeline cycle
     * @param dispositionRepository   collection-time per-slot dispositions
     * @param apiCallLogRepository    per-request batch results
     * @param pipelineRunRepository   the job run each cycle's dispositions were persisted on
     * @param forecastScoreRepository the component rows each cycle wrote, as success evidence
     */
    public CycleLocationOutcomeResolver(ForecastBatchRepository forecastBatchRepository,
            ForecastRunDispositionRepository dispositionRepository,
            ApiCallLogRepository apiCallLogRepository,
            PipelineRunRepository pipelineRunRepository,
            ForecastScoreRepository forecastScoreRepository) {
        this.forecastBatchRepository = forecastBatchRepository;
        this.dispositionRepository = dispositionRepository;
        this.apiCallLogRepository = apiCallLogRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.forecastScoreRepository = forecastScoreRepository;
    }

    /**
     * Resolves every place the cycle recorded anything about.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @return evidence per location id; empty when nothing is tagged with the cycle (which is what a
     *         hand-started run, never tagged with a pipeline run, resolves to)
     */
    @Transactional(readOnly = true)
    public Map<Long, CyclePlaceEvidence> resolve(Long pipelineRunId) {
        List<ForecastBatchEntity> batches = forecastBatchRepository.findByPipelineRunId(pipelineRunId);
        List<Long> jobRunIds = new ArrayList<>(batches.stream()
                .map(ForecastBatchEntity::getJobRunId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        pipelineRunRepository.findDispositionJobRunId(pipelineRunId)
                .filter(recorded -> !jobRunIds.contains(recorded))
                .ifPresent(jobRunIds::add);
        List<String> batchIds = batches.stream()
                .filter(b -> b.getBatchType() == BatchType.FORECAST)
                .map(ForecastBatchEntity::getAnthropicBatchId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, Tally> tallies = new LinkedHashMap<>();
        if (!jobRunIds.isEmpty()) {
            for (CycleDisposition row : dispositionRepository.findCycleDispositions(jobRunIds)) {
                foldDisposition(tallies, row);
            }
        }
        if (!batchIds.isEmpty()) {
            for (BatchCallOutcome row : apiCallLogRepository.findBatchCallOutcomes(batchIds)) {
                foldBatchResult(tallies, row, pipelineRunId);
            }
        }

        if (!batches.isEmpty()) {
            for (CycleScoredComponent row : forecastScoreRepository.findScoredComponentsByPipelineRun(
                    pipelineRunId, SCORED_LANE_BY_TYPE_ID.keySet())) {
                Lane lane = SCORED_LANE_BY_TYPE_ID.get(row.forecastTypeId());
                if (lane != null) {
                    tallies.computeIfAbsent(row.locationId(), id -> new Tally()).succeeded.add(lane);
                }
            }
        }

        Map<Long, CyclePlaceEvidence> evidence = new HashMap<>();
        tallies.forEach((locationId, tally) -> evidence.put(locationId, tally.evidence()));
        return evidence;
    }

    private static void foldDisposition(Map<Long, Tally> tallies, CycleDisposition row) {
        Tally tally = tallies.computeIfAbsent(row.locationId(), id -> new Tally());
        DispositionCategory category = DispositionCategory.fromString(row.disposition())
                .orElse(null);
        if (category == DispositionCategory.SKIPPED_TRIAGED) {
            tally.triaged = true;
        } else if (category == DispositionCategory.SKIPPED_ERROR) {
            tally.collectionFailed = true;
        }
    }

    private static void foldBatchResult(Map<Long, Tally> tallies, BatchCallOutcome row,
            Long pipelineRunId) {
        ParsedCustomId parsed;
        try {
            parsed = CustomIdFactory.parse(row.customId());
        } catch (IllegalArgumentException e) {
            LOG.debug("Cycle {}: result with unparseable custom_id '{}' cannot be attributed to a "
                    + "place, ignored", pipelineRunId, row.customId());
            return;
        }
        Long locationId;
        Lane lane;
        switch (parsed) {
            case ParsedCustomId.Forecast f -> {
                locationId = f.locationId();
                lane = Lane.SKY;
            }
            case ParsedCustomId.Jfdi j -> {
                locationId = j.locationId();
                lane = Lane.SKY;
            }
            case ParsedCustomId.ForceSubmit fs -> {
                locationId = fs.locationId();
                lane = Lane.SKY;
            }
            case ParsedCustomId.Bluebell b -> {
                locationId = b.locationId();
                lane = Lane.BLUEBELL;
            }
            case ParsedCustomId.Woodland w -> {
                locationId = w.locationId();
                lane = Lane.WOODLAND;
            }
            case ParsedCustomId.Aurora ignored -> {
                return;
            }
        }
        Tally tally = tallies.computeIfAbsent(locationId, id -> new Tally());
        if (Boolean.TRUE.equals(row.succeeded())) {
            tally.succeeded.add(lane);
        } else {
            tally.failed.add(lane);
        }
    }

    /** Mutable per-place accumulator, folded into a {@link CyclePlaceEvidence} at the end. */
    private static final class Tally {
        private boolean triaged;
        private boolean collectionFailed;
        private final Set<Lane> succeeded = EnumSet.noneOf(Lane.class);
        private final Set<Lane> failed = EnumSet.noneOf(Lane.class);

        CyclePlaceEvidence evidence() {
            return new CyclePlaceEvidence(triaged, collectionFailed, succeeded, failed);
        }
    }
}
