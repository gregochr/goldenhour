package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.model.BatchCallOutcome;
import com.gregochr.goldenhour.model.CycleDisposition;
import com.gregochr.goldenhour.model.CyclePlaceEvidence;
import com.gregochr.goldenhour.model.CyclePlaceEvidence.Lane;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.service.batch.CycleDispositionJobRuns;
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
 * its dispositions were persisted on is also remembered by {@link CycleDispositionJobRuns}, which
 * is what finds a batchless cycle's anchor run (see below):
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
 * <p>The result is deliberately raw, per lane: {@code LocationFailureService} compares a failure
 * only with like evidence (the same lane for a Claude failure), because a fault confined to one
 * lane, a woodland parser regression for instance, must not be diluted by the many places that
 * scored or were triaged in other lanes. A stability skip is not a success: the weather was
 * fetched, but the place was not judged.
 *
 * <p><b>The batchless cycle.</b> A cycle that submits no batch (everything cached, skipped or
 * triaged away, or every submission failed, the 2026-09-29 shape) has no {@code forecast_batch}
 * row, and its dispositions sit on an anchor job run that nothing in the database links to the
 * pipeline run (no {@code job_run} column holds a pipeline run id; the link is not parsed out of
 * the free-text {@code notes}). {@link CycleDispositionJobRuns} holds the link in memory instead,
 * so such a cycle still resolves: a cycle that legitimately triaged candidates away shows them as
 * triaged, and its places get through and are reset, while a cycle whose submissions all failed
 * shows only {@code SUBMISSION_FAILED} rows, which are no evidence either way, so it still counts
 * nobody. The link is bounded and <b>lost on a restart</b>; a cycle whose link is lost resolves from
 * its batches alone (nothing, for a batchless cycle), which counts nobody and resets nobody, the
 * safe direction (it can only under-count a streak).
 *
 * <p><b>Known gap.</b> A bluebell or woodland request that failed is never retried
 * ({@code BatchRetryService.selectFailures} keeps only sky ids), so such a failure stands for the
 * cycle.
 */
@Service
public class CycleLocationOutcomeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(CycleLocationOutcomeResolver.class);

    private final ForecastBatchRepository forecastBatchRepository;
    private final ForecastRunDispositionRepository dispositionRepository;
    private final ApiCallLogRepository apiCallLogRepository;
    private final CycleDispositionJobRuns cycleDispositionJobRuns;

    /**
     * Constructs the resolver.
     *
     * @param forecastBatchRepository batches tagged with a pipeline cycle
     * @param dispositionRepository   collection-time per-slot dispositions
     * @param apiCallLogRepository    per-request batch results
     * @param cycleDispositionJobRuns the job run each cycle's dispositions were persisted on
     */
    public CycleLocationOutcomeResolver(ForecastBatchRepository forecastBatchRepository,
            ForecastRunDispositionRepository dispositionRepository,
            ApiCallLogRepository apiCallLogRepository,
            CycleDispositionJobRuns cycleDispositionJobRuns) {
        this.forecastBatchRepository = forecastBatchRepository;
        this.dispositionRepository = dispositionRepository;
        this.apiCallLogRepository = apiCallLogRepository;
        this.cycleDispositionJobRuns = cycleDispositionJobRuns;
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
        cycleDispositionJobRuns.jobRunFor(pipelineRunId)
                .filter(anchor -> !jobRunIds.contains(anchor))
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
