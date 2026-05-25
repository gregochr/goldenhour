package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.ForecastRunDispositionEntity;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence and retention for {@code forecast_run_disposition} rows.
 *
 * <p>Called by {@code ScheduledBatchEvaluationService} once the cycle's first
 * Anthropic Batch submission has returned a {@code job_run_id} — every
 * candidate disposition the {@code ForecastTaskCollector} accumulated is
 * persisted against that id in a single transactional batch insert. The cycle's
 * other bucket submissions (which create their own job runs) do not re-persist;
 * the read API surfaces the dispositions only on the first job run.
 *
 * <p>Also runs the retention prune that {@code disposition_cleanup} invokes via
 * the dynamic scheduler — see V101 for the schedule.
 */
@Service
public class ForecastDispositionService {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastDispositionService.class);

    /**
     * How long to keep disposition rows before pruning. ~2k/day × 30 = ~60k
     * steady-state, trivial for either H2 or Postgres but bounded.
     */
    static final int RETENTION_DAYS = 30;

    private final ForecastRunDispositionRepository repository;

    /**
     * Constructs the service.
     *
     * @param repository disposition repository
     */
    public ForecastDispositionService(ForecastRunDispositionRepository repository) {
        this.repository = repository;
    }

    /**
     * Persists every candidate disposition the collector recorded against the
     * given job run id. Idempotent at the call site — only invoked once per
     * cycle, after the cycle's first non-empty bucket has been submitted.
     *
     * <p>If the disposition list is empty (no briefing, all-empty cycle) or the
     * job run id is null, this is a no-op. The expectation is that the caller
     * has already filtered for these edge cases.
     *
     * @param jobRunId     job run id from the cycle's first {@code EvaluationHandle}
     * @param dispositions list of in-memory dispositions to persist
     */
    @Transactional
    public void persist(Long jobRunId, List<CandidateDisposition> dispositions) {
        if (jobRunId == null || dispositions == null || dispositions.isEmpty()) {
            return;
        }
        List<ForecastRunDispositionEntity> entities = new ArrayList<>(dispositions.size());
        for (CandidateDisposition d : dispositions) {
            entities.add(ForecastRunDispositionEntity.builder()
                    .jobRunId(jobRunId)
                    .locationId(d.locationId())
                    .locationName(d.locationName())
                    .evaluationDate(d.evaluationDate())
                    .eventType(d.eventType() != null ? d.eventType().name() : null)
                    .daysAhead(d.daysAhead())
                    .disposition(d.category().name())
                    .detail(truncate(d.detail(), 500))
                    .build());
        }
        repository.saveAll(entities);
        LOG.info("[DISPOSITION] Persisted {} disposition row(s) for jobRunId={}",
                entities.size(), jobRunId);
    }

    /**
     * Deletes disposition rows older than {@link #RETENTION_DAYS}. Invoked
     * daily by the dynamic scheduler ({@code disposition_cleanup}).
     */
    @Transactional
    public void pruneStale() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = repository.deleteByCreatedAtBefore(cutoff);
        LOG.info("[DISPOSITION] Cleanup complete — deleted {} row(s) older than {} day(s)",
                deleted, RETENTION_DAYS);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
