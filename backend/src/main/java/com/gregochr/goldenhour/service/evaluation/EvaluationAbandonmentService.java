package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Closes out {@code PENDING} {@code forecast_evaluation} rows (R7 of the prompted-row
 * persistence plan) that will never reach {@code SCORED} — a batch request that failed,
 * errored, expired, or was superseded by a retry.
 *
 * <p>Two mechanisms, event-driven where cheap and a time-based backstop everywhere else:
 * <ul>
 *   <li>{@link #abandonPendingForBatch} — called once a forecast batch finishes processing
 *       (success or failure). Every request that succeeded already scored its row in place
 *       (R5); this sweep closes out whichever of the batch's still-PENDING rows are left.</li>
 *   <li>{@link #sweepBackstop} — a scheduled job catching everything the event-driven path
 *       cannot reach: a crash between submit and result, an unreconstructable retry, or any
 *       future path that forgets to call the event-driven sweep.</li>
 * </ul>
 */
@Service
public class EvaluationAbandonmentService {

    private static final Logger LOG = LoggerFactory.getLogger(EvaluationAbandonmentService.class);

    /** Job key registered with {@link DynamicSchedulerService} — see V150's seed row. */
    static final String JOB_KEY = "evaluation_abandonment_sweep";

    /**
     * Rows older than this are abandoned by the backstop sweep. Anthropic batches expire after
     * 24h; this is that plus margin, so a batch running right up to its expiry is never
     * abandoned out from under it before {@link #abandonPendingForBatch} gets a chance to run.
     */
    static final Duration BACKSTOP_AGE = Duration.ofHours(48);

    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final ApiCallLogRepository apiCallLogRepository;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;

    /**
     * Constructs the abandonment service.
     *
     * @param forecastEvaluationRepository holds the PENDING/SCORED/ABANDONED rows
     * @param apiCallLogRepository         source of a batch's custom_ids for the event-driven sweep
     * @param dynamicSchedulerService      registers the backstop job target
     * @param clock                        supplies "now" for the backstop cutoff
     */
    public EvaluationAbandonmentService(ForecastEvaluationRepository forecastEvaluationRepository,
            ApiCallLogRepository apiCallLogRepository,
            DynamicSchedulerService dynamicSchedulerService,
            Clock clock) {
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.apiCallLogRepository = apiCallLogRepository;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
    }

    /**
     * Registers the backstop sweep with the dynamic scheduler.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::sweepBackstop);
    }

    /**
     * R7(a) — stamps {@code ABANDONED} every still-{@code PENDING} row among the {@code fc-}
     * requests logged against the given batch.
     *
     * <p>Walks {@code api_call_log} for every custom_id the batch logged (succeeded and failed
     * alike), parses each as best-effort (a malformed or non-{@code fc-} id is simply skipped —
     * it carries no {@code evalRowId} to act on), and bulk-updates. The bulk update's own
     * {@code WHERE batch_state = PENDING} makes this safe to call for a row that already scored
     * (R5 beat it here) or was already abandoned by an R6 retry-precursor stamp — both are
     * no-ops.
     *
     * @param anthropicBatchId the Anthropic batch id ({@code msgbatch_*})
     */
    public void abandonPendingForBatch(String anthropicBatchId) {
        List<String> customIds = apiCallLogRepository.findCustomIdsByBatchId(anthropicBatchId);
        List<Long> rowIds = customIds.stream()
                .map(EvaluationAbandonmentService::tryParseEvalRowId)
                .filter(Objects::nonNull)
                .toList();
        if (rowIds.isEmpty()) {
            return;
        }
        int updated = forecastEvaluationRepository.abandonPending(rowIds);
        if (updated > 0) {
            LOG.info("R7: batch {} finished — stamped {} still-PENDING row(s) ABANDONED",
                    anthropicBatchId, updated);
        }
    }

    /**
     * R7(b) — the time-based backstop. Stamps {@code ABANDONED} every {@code PENDING} row whose
     * submit-time {@code forecast_run_at} is older than {@link #BACKSTOP_AGE}. Runs on the
     * {@link DynamicSchedulerService}-managed schedule (V150's seed row).
     */
    void sweepBackstop() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(BACKSTOP_AGE);
        int updated = forecastEvaluationRepository.abandonPendingOlderThan(cutoff);
        if (updated > 0) {
            LOG.info("R7: backstop sweep stamped {} PENDING row(s) older than {} ABANDONED",
                    updated, cutoff);
        }
    }

    /**
     * Best-effort extraction of a forecast custom id's embedded {@code evalRowId}. Returns
     * {@code null} for a non-{@code fc-} id, a malformed id, or an {@code fc-} id with no
     * embedded row id (pre-deploy format) — every case where there is nothing to act on.
     */
    private static Long tryParseEvalRowId(String customId) {
        if (customId == null) {
            return null;
        }
        try {
            return switch (CustomIdFactory.parse(customId)) {
                case ParsedCustomId.Forecast f -> f.evalRowId();
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
