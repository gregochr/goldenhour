package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Nightly retention prune for {@code scheduler_fire_log} — the {@code scheduler_fire_log_cleanup} job
 * (V215, 03:57 UTC; scheduler-redesign-plan.md D3).
 *
 * <p>A row is deleted when its {@code fired_at} is strictly before {@code now - 14 days}, in one bulk
 * {@code DELETE} the {@code fired_at} index serves. Shape of {@code AskLogCleanupJob}: an INFO line with
 * the cutoff and the count (zero included), and a repository failure propagates to the scheduler thread,
 * which records the fire as FAILED and retries the whole predicate the next night.
 */
@Service
public class SchedulerFireLogCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(SchedulerFireLogCleanupJob.class);

    /** Scheduler job key, matching the V215-seeded {@code scheduler_job_config} row. */
    public static final String JOB_KEY = "scheduler_fire_log_cleanup";

    /** How long a fire stays in the log. */
    static final Duration RETENTION = Duration.ofDays(14);

    private final SchedulerFireLogRepository repository;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;

    /**
     * Creates the job.
     *
     * @param repository              the {@code scheduler_fire_log} repository
     * @param dynamicSchedulerService the scheduler to register the target against
     * @param clock                   the application clock; the cutoff is {@code now - 14 days}
     */
    public SchedulerFireLogCleanupJob(SchedulerFireLogRepository repository,
            DynamicSchedulerService dynamicSchedulerService, Clock clock) {
        this.repository = repository;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
    }

    /** Registers the prune with the dynamic scheduler, resolving the V215-seeded cron row. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::prune);
    }

    /**
     * Deletes every fire older than the retention, in one statement, logs the cutoff and count, and
     * reports the count as the fire's summary.
     *
     * @return the number of rows deleted
     */
    public int prune() {
        Instant cutoff = clock.instant().minus(RETENTION);
        int deleted = repository.deleteByFiredAtBefore(cutoff);
        LOG.info("Scheduler fire log cleanup complete — deleted {} row(s) fired before {} ({} day retention)",
                deleted, cutoff, RETENTION.toDays());
        SchedulerFireOutcome.report(SchedulerFireStatus.SUCCEEDED, deleted + " rows deleted");
        return deleted;
    }
}
