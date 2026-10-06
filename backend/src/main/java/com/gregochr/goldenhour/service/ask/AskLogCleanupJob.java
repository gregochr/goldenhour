package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.repository.AskLogRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Nightly retention prune for {@code ask_log} — the {@code ask_log_cleanup} job (V167, 03:55 UTC).
 *
 * <p>The log holds a normalised copy of every question the engine answered, so it is kept only as
 * long as it is useful: {@code photocast.ask.log.retention-days} (default 90, plan D-13). A row is
 * deleted when its {@code created_at} is <em>strictly before</em> {@code now - retention-days}
 * (a row exactly that old is kept; the first row deleted is a moment older), in one bulk
 * {@code DELETE} the {@code created_at} index serves.
 *
 * <p>Shape of {@code SlotAtmosphereCleanupJob} and the disposition cleanup: an INFO line with the
 * cutoff and the count (zero included), no {@code job_run} row, and a repository failure propagates
 * to the scheduler thread, which records the run and retries the whole predicate the next night.
 * Prunes no other table. It runs whether or not Ask is switched on: rows written while it was on
 * must still age out.
 */
@Service
public class AskLogCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(AskLogCleanupJob.class);

    /** Scheduler job key, matching the V167-seeded {@code scheduler_job_config} row. */
    public static final String JOB_KEY = "ask_log_cleanup";

    private final AskLogRepository repository;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;
    private final AskProperties properties;

    /**
     * Creates the job.
     *
     * @param repository              the {@code ask_log} repository
     * @param dynamicSchedulerService the scheduler to register the target against
     * @param clock                   the application clock; the cutoff is {@code now - retention}
     * @param properties              the Ask settings ({@code log.retention-days})
     */
    public AskLogCleanupJob(AskLogRepository repository, DynamicSchedulerService dynamicSchedulerService,
            Clock clock, AskProperties properties) {
        this.repository = repository;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
        this.properties = properties;
    }

    /** Registers the prune with the dynamic scheduler, resolving the V167-seeded cron row. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::prune);
    }

    /**
     * Deletes every row older than the retention, in one statement, and logs the cutoff and count.
     *
     * @return the number of rows deleted
     */
    public int prune() {
        int retentionDays = properties.getLog().getRetentionDays();
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        int deleted = repository.deleteOlderThan(cutoff);
        LOG.info("[ASK] Question log cleanup complete — deleted {} row(s) created before {} "
                + "({} day retention)", deleted, cutoff, retentionDays);
        return deleted;
    }
}
