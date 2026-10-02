package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.model.BatchCallOutcome;
import com.gregochr.goldenhour.model.CycleDisposition;
import com.gregochr.goldenhour.model.CyclePlaceOutcome;
import com.gregochr.goldenhour.model.CyclePlaceOutcome.FailureKind;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.ParsedCustomId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reduces what one scheduled pipeline cycle recorded about each place to a
 * {@link CyclePlaceOutcome}: got through, failed, or not attempted.
 *
 * <p>Two recorded sources are read, both keyed to the cycle by its {@code forecast_batch} rows
 * (every batch of a pipeline cycle, retry batch included, carries the cycle's
 * {@code pipeline_run_id}):
 *
 * <ul>
 *   <li><b>{@code forecast_run_disposition}</b> — what collection decided per slot, anchored on the
 *       job run of the cycle's first submitted batch, so the job runs of the cycle's batches are
 *       searched. {@code SKIPPED_TRIAGED} is a success (the pipeline judged the weather and
 *       answered); {@code SKIPPED_ERROR} is a failure (assembly of the place's data threw). Every
 *       other category is no evidence either way: {@code EVALUATED}/{@code FORCE_EVALUATED} only say
 *       a request was queued, and {@code SUBMISSION_FAILED}, {@code SKIPPED_CACHED},
 *       {@code SKIPPED_PAST_DATE}, {@code SKIPPED_TRAVEL_DAY}, {@code SKIPPED_HARD_CONSTRAINT},
 *       {@code SKIPPED_STABILITY}, {@code SKIPPED_NO_PROMPT}, {@code SKIPPED_UNKNOWN_LOCATION} and
 *       {@code SKIPPED_NO_REFRESH_NEEDED} mean nothing was sent for the slot.</li>
 *   <li><b>{@code api_call_log}</b> — one row per individual batch result, with the request's
 *       {@code custom_id} (which names the place) and whether it {@code succeeded}. A success is a
 *       success whichever lane (sky, bluebell, woodland) or batch (first attempt or retry) produced
 *       it; a failed row is a failure.</li>
 * </ul>
 *
 * <p>A place's outcome folds every slot and lane together: <b>any success makes it
 * {@code GOT_THROUGH}</b> (a retry that recovered a request, or a place with one failed slot and one
 * scored slot, did get through), otherwise any failure makes it {@code FAILED}, otherwise it is
 * {@code NOT_ATTEMPTED}. A stability skip is deliberately not a success: the weather was fetched,
 * but the place was not judged, and counting it would let a place whose Claude requests fail every
 * night be reset by its stability-skipped far slots.
 *
 * <p><b>Known gap.</b> A cycle that submitted no batch at all (everything cached, skipped or
 * triaged) writes its dispositions to a disposition-only anchor job run that carries no link to the
 * pipeline run, so such a cycle resolves to nothing and counts nobody. That is the safe direction:
 * nothing was sent to Claude for anyone, so there is no failure to count.
 */
@Service
public class CycleLocationOutcomeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(CycleLocationOutcomeResolver.class);

    private final ForecastBatchRepository forecastBatchRepository;
    private final ForecastRunDispositionRepository dispositionRepository;
    private final ApiCallLogRepository apiCallLogRepository;

    /**
     * Constructs the resolver.
     *
     * @param forecastBatchRepository batches tagged with a pipeline cycle
     * @param dispositionRepository   collection-time per-slot dispositions
     * @param apiCallLogRepository    per-request batch results
     */
    public CycleLocationOutcomeResolver(ForecastBatchRepository forecastBatchRepository,
            ForecastRunDispositionRepository dispositionRepository,
            ApiCallLogRepository apiCallLogRepository) {
        this.forecastBatchRepository = forecastBatchRepository;
        this.dispositionRepository = dispositionRepository;
        this.apiCallLogRepository = apiCallLogRepository;
    }

    /**
     * Resolves every place the cycle recorded anything about.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @return outcome per location id; empty when nothing is tagged with the cycle (which is what a
     *         hand-started run, never tagged with a pipeline run, resolves to)
     */
    @Transactional(readOnly = true)
    public Map<Long, CyclePlaceOutcome> resolve(Long pipelineRunId) {
        List<ForecastBatchEntity> batches = forecastBatchRepository.findByPipelineRunId(pipelineRunId);
        List<Long> jobRunIds = batches.stream()
                .map(ForecastBatchEntity::getJobRunId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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

        Map<Long, CyclePlaceOutcome> outcomes = new HashMap<>();
        tallies.forEach((locationId, tally) -> outcomes.put(locationId, tally.outcome()));
        return outcomes;
    }

    private static void foldDisposition(Map<Long, Tally> tallies, CycleDisposition row) {
        Tally tally = tallies.computeIfAbsent(row.locationId(), id -> new Tally());
        DispositionCategory category = DispositionCategory.fromString(row.disposition())
                .orElse(null);
        if (category == DispositionCategory.SKIPPED_TRIAGED) {
            tally.success = true;
        } else if (category == DispositionCategory.SKIPPED_ERROR) {
            tally.weatherFailure = true;
        }
    }

    private static void foldBatchResult(Map<Long, Tally> tallies, BatchCallOutcome row,
            Long pipelineRunId) {
        Long locationId = locationIdOf(row.customId(), pipelineRunId);
        if (locationId == null) {
            return;
        }
        Tally tally = tallies.computeIfAbsent(locationId, id -> new Tally());
        if (Boolean.TRUE.equals(row.succeeded())) {
            tally.success = true;
        } else {
            tally.evaluationFailure = true;
        }
    }

    private static Long locationIdOf(String customId, Long pipelineRunId) {
        ParsedCustomId parsed;
        try {
            parsed = CustomIdFactory.parse(customId);
        } catch (IllegalArgumentException e) {
            LOG.debug("Cycle {}: result with unparseable custom_id '{}' cannot be attributed to a "
                    + "place — ignored", pipelineRunId, customId);
            return null;
        }
        return switch (parsed) {
            case ParsedCustomId.Forecast f -> f.locationId();
            case ParsedCustomId.Bluebell b -> b.locationId();
            case ParsedCustomId.Woodland w -> w.locationId();
            case ParsedCustomId.Jfdi j -> j.locationId();
            case ParsedCustomId.ForceSubmit fs -> fs.locationId();
            case ParsedCustomId.Aurora a -> null;
        };
    }

    /** Mutable per-place accumulator, folded into a {@link CyclePlaceOutcome} at the end. */
    private static final class Tally {
        private boolean success;
        private boolean weatherFailure;
        private boolean evaluationFailure;

        CyclePlaceOutcome outcome() {
            if (success) {
                return CyclePlaceOutcome.gotThrough();
            }
            if (weatherFailure) {
                return CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA);
            }
            if (evaluationFailure) {
                return CyclePlaceOutcome.failed(FailureKind.EVALUATION);
            }
            return CyclePlaceOutcome.notAttempted();
        }
    }
}
