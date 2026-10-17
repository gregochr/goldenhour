package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.ForecastEvaluationPromptRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Nightly retention prune for {@code forecast_evaluation_prompt} — the
 * {@code forecast_prompt_cleanup} job (seeded by V164 at 03:50 UTC).
 *
 * <p>A row is deleted when its {@code created_at} is strictly older than
 * {@code photocast.forecast-prompt.retention-days} (default {@value #DEFAULT_RETENTION_DAYS}); a
 * row exactly at the cutoff is kept. One bulk {@code DELETE}; the cutoff and the count (zero
 * included) are logged at INFO. A repository failure propagates to the scheduler. Same shape as
 * {@link SlotAtmosphereCleanupJob}.
 */
@Service
public class ForecastPromptCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastPromptCleanupJob.class);

    /** Scheduler job key, matching the V164-seeded {@code scheduler_job_config} row. */
    public static final String JOB_KEY = "forecast_prompt_cleanup";

    /** Default retention, in days. */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    private final ForecastEvaluationPromptRepository repository;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;
    private final int retentionDays;

    /**
     * Constructs the job.
     *
     * @param repository              the prompt repository
     * @param dynamicSchedulerService scheduler to register the job target against
     * @param clock                   injectable clock
     * @param retentionDays           {@code photocast.forecast-prompt.retention-days}
     * @throws IllegalStateException if {@code retentionDays} is not positive
     */
    public ForecastPromptCleanupJob(ForecastEvaluationPromptRepository repository,
            DynamicSchedulerService dynamicSchedulerService, Clock clock,
            @Value("${photocast.forecast-prompt.retention-days:" + DEFAULT_RETENTION_DAYS + "}")
            int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalStateException("photocast.forecast-prompt.retention-days="
                    + retentionDays + " must be at least 1");
        }
        this.repository = repository;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /** Registers the prune with the dynamic scheduler. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::prune);
    }

    /**
     * Deletes every prompt row older than the retention in one statement and logs the result.
     *
     * @return the number of rows deleted
     */
    public int prune() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(retentionDays));
        int deleted = repository.deleteByCreatedAtBefore(cutoff);
        LOG.info("[FORECAST PROMPT] Cleanup complete — deleted {} row(s) created before {} "
                + "({} day retention)", deleted, cutoff, retentionDays);
        return deleted;
    }
}
