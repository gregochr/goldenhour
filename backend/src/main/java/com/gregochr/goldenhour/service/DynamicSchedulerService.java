package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates dynamic scheduling of jobs based on persisted {@link SchedulerJobConfigEntity} rows.
 *
 * <p>Each owning service registers its job target via {@link #registerJobTarget(String, Runnable)}
 * during {@code @PostConstruct}. {@link DynamicSchedulerBootstrap} (active outside the
 * {@code integration-test} profile) calls {@link #initSchedules()} on
 * {@code ApplicationReadyEvent} to load all configs from the database and schedule ACTIVE jobs.
 */
@Service
public class DynamicSchedulerService {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicSchedulerService.class);

    /** Minimum allowed fixed delay to prevent accidental tight loops. */
    private static final long MIN_FIXED_DELAY_MS = 10_000;

    /** Config source value that indicates dependency on aurora.enabled. */
    private static final String AURORA_CONFIG_SOURCE = "aurora.enabled";

    private static final Set<String> AURORA_JOB_KEYS = Set.of("aurora_polling");

    private final SchedulerJobConfigRepository repository;
    private final TaskScheduler taskScheduler;
    private final AuroraProperties auroraProperties;

    private final ConcurrentHashMap<String, Runnable> jobTargets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledFutures =
            new ConcurrentHashMap<>();
    private final ReentrantLock scheduleLock = new ReentrantLock();

    /**
     * Constructs the dynamic scheduler service.
     *
     * @param repository       the job config repository
     * @param taskScheduler    the dedicated task scheduler bean
     * @param auroraProperties aurora configuration for checking enabled state
     */
    public DynamicSchedulerService(SchedulerJobConfigRepository repository,
            TaskScheduler taskScheduler,
            AuroraProperties auroraProperties) {
        this.repository = repository;
        this.taskScheduler = taskScheduler;
        this.auroraProperties = auroraProperties;
    }

    /**
     * Registers a job target runnable for a given job key.
     *
     * <p>Called by owning services during {@code @PostConstruct}.
     *
     * @param jobKey the unique job key matching a row in scheduler_job_config
     * @param target the runnable to execute when the job fires
     */
    public void registerJobTarget(String jobKey, Runnable target) {
        jobTargets.put(jobKey, target);
        LOG.debug("Registered job target for key: {}", jobKey);
    }

    /**
     * Initialises all schedules from persisted config rows.
     *
     * <p>Checks aurora config and adjusts status for aurora-dependent jobs accordingly.
     *
     * <p>Invoked at startup by {@link DynamicSchedulerBootstrap} (which is profile-gated
     * with {@code @Profile("!integration-test")}). Made a plain public method so that
     * unit tests can call it directly and integration tests using the
     * {@code integration-test} profile can choose when (or whether) to bootstrap.
     */
    public void initSchedules() {
        List<SchedulerJobConfigEntity> configs = repository.findAllByOrderByIdAsc();
        for (SchedulerJobConfigEntity config : configs) {
            syncAuroraStatus(config);
            if (config.getStatus() == SchedulerJobStatus.ACTIVE) {
                scheduleJob(config);
            } else {
                LOG.info("Skipping job '{}' — status: {}", config.getJobKey(), config.getStatus());
            }
        }
        LOG.info("Dynamic scheduler initialised — {} job(s) configured", configs.size());
    }

    /**
     * Returns all job configs with computed next fire times.
     *
     * @return all job configs
     */
    public List<SchedulerJobConfigEntity> getAll() {
        return repository.findAllByOrderByIdAsc();
    }

    /**
     * Updates the schedule for a job and reschedules it if ACTIVE.
     *
     * @param jobKey         the job key
     * @param cronExpression new cron expression (for CRON jobs), or null to keep current
     * @param fixedDelayMs   new fixed delay in ms (for FIXED_DELAY jobs), or null to keep current
     * @return the updated config entity
     * @throws IllegalArgumentException if the cron expression is invalid or delay is too short
     * @throws java.util.NoSuchElementException if the job key is not found
     */
    public SchedulerJobConfigEntity updateSchedule(String jobKey, String cronExpression,
            Long fixedDelayMs) {
        SchedulerJobConfigEntity config = repository.findByJobKey(jobKey).orElseThrow();

        if (config.getScheduleType() == ScheduleType.CRON && cronExpression != null) {
            validateCron(cronExpression);
            config.setCronExpression(cronExpression);
        } else if (config.getScheduleType() == ScheduleType.FIXED_DELAY && fixedDelayMs != null) {
            validateFixedDelay(fixedDelayMs);
            config.setFixedDelayMs(fixedDelayMs);
        }

        config.setUpdatedAt(Instant.now());
        repository.save(config);

        if (config.getStatus() == SchedulerJobStatus.ACTIVE) {
            cancelJob(jobKey);
            scheduleJob(config);
        }

        LOG.info("Updated schedule for '{}': type={}, cron={}, delay={}ms",
                jobKey, config.getScheduleType(), config.getCronExpression(),
                config.getFixedDelayMs());
        return config;
    }

    /**
     * Pauses a job — cancels its scheduled future and sets status to PAUSED.
     *
     * @param jobKey the job key
     * @return the updated config entity
     * @throws IllegalStateException if the job is DISABLED_BY_CONFIG
     * @throws java.util.NoSuchElementException if the job key is not found
     */
    public SchedulerJobConfigEntity pause(String jobKey) {
        SchedulerJobConfigEntity config = repository.findByJobKey(jobKey).orElseThrow();
        rejectIfDisabledByConfig(config);

        cancelJob(jobKey);
        config.setStatus(SchedulerJobStatus.PAUSED);
        config.setUpdatedAt(Instant.now());
        repository.save(config);

        LOG.info("Paused job '{}'", jobKey);
        return config;
    }

    /**
     * Resumes a paused job — sets status to ACTIVE and reschedules.
     *
     * @param jobKey the job key
     * @return the updated config entity
     * @throws IllegalStateException if the job is DISABLED_BY_CONFIG
     * @throws java.util.NoSuchElementException if the job key is not found
     */
    public SchedulerJobConfigEntity resume(String jobKey) {
        SchedulerJobConfigEntity config = repository.findByJobKey(jobKey).orElseThrow();
        rejectIfDisabledByConfig(config);

        config.setStatus(SchedulerJobStatus.ACTIVE);
        config.setUpdatedAt(Instant.now());
        repository.save(config);
        scheduleJob(config);

        LOG.info("Resumed job '{}'", jobKey);
        return config;
    }

    /**
     * Triggers a job to run once immediately on the scheduler thread pool.
     *
     * @param jobKey the job key
     * @throws IllegalStateException if the job is DISABLED_BY_CONFIG or has no registered target
     * @throws java.util.NoSuchElementException if the job key is not found
     */
    public void triggerNow(String jobKey) {
        SchedulerJobConfigEntity config = repository.findByJobKey(jobKey).orElseThrow();
        rejectIfDisabledByConfig(config);

        Runnable target = jobTargets.get(jobKey);
        if (target == null) {
            throw new IllegalStateException("No registered target for job: " + jobKey);
        }

        taskScheduler.schedule(wrapTarget(jobKey, target), Instant.now());
        LOG.info("Triggered immediate run for job '{}'", jobKey);
    }

    /**
     * Computes the next fire time for a job config.
     *
     * <p>For CRON jobs, uses {@link CronExpression#next(LocalDateTime)}.
     * For FIXED_DELAY jobs, adds the delay to the last completion time.
     * Returns {@code null} if the job is paused or disabled.
     *
     * @param config the job config
     * @return the computed next fire time, or null
     */
    public Instant calculateNextFireTime(SchedulerJobConfigEntity config) {
        if (config.getStatus() != SchedulerJobStatus.ACTIVE) {
            return null;
        }

        if (config.getScheduleType() == ScheduleType.CRON && config.getCronExpression() != null) {
            CronExpression cron = CronExpression.parse(config.getCronExpression());
            LocalDateTime next = cron.next(LocalDateTime.now(ZoneOffset.UTC));
            return next != null ? next.toInstant(ZoneOffset.UTC) : null;
        }

        if (config.getScheduleType() == ScheduleType.FIXED_DELAY
                && config.getFixedDelayMs() != null) {
            if (config.getLastCompletionTime() != null) {
                return config.getLastCompletionTime()
                        .plusMillis(config.getFixedDelayMs());
            }
            // Not yet run — next fire is approximately now + initial delay
            long initialDelay = config.getInitialDelayMs() != null
                    ? config.getInitialDelayMs() : config.getFixedDelayMs();
            return Instant.now().plusMillis(initialDelay);
        }

        return null;
    }

    /**
     * Schedules a job based on its config.
     *
     * @param config the job config with schedule details
     */
    void scheduleJob(SchedulerJobConfigEntity config) {
        Runnable target = jobTargets.get(config.getJobKey());
        if (target == null) {
            LOG.warn("No registered target for job '{}' — skipping schedule", config.getJobKey());
            return;
        }

        Runnable wrapped = wrapTarget(config.getJobKey(), target);
        scheduleLock.lock();
        try {
            cancelJob(config.getJobKey());

            ScheduledFuture<?> future;
            if (config.getScheduleType() == ScheduleType.CRON) {
                future = taskScheduler.schedule(wrapped,
                        new CronTrigger(config.getCronExpression()));
            } else {
                Duration delay = Duration.ofMillis(config.getFixedDelayMs());
                future = taskScheduler.scheduleWithFixedDelay(wrapped, delay);
            }
            scheduledFutures.put(config.getJobKey(), future);
            LOG.info("Scheduled job '{}': type={}, cron={}, delay={}ms",
                    config.getJobKey(), config.getScheduleType(),
                    config.getCronExpression(), config.getFixedDelayMs());
        } finally {
            scheduleLock.unlock();
        }
    }

    private void cancelJob(String jobKey) {
        ScheduledFuture<?> existing = scheduledFutures.remove(jobKey);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    private Runnable wrapTarget(String jobKey, Runnable target) {
        return () -> {
            Instant fireTime = Instant.now();
            try {
                repository.findByJobKey(jobKey).ifPresent(config -> {
                    config.setLastFireTime(fireTime);
                    repository.save(config);
                });
                target.run();
            } finally {
                repository.findByJobKey(jobKey).ifPresent(config -> {
                    config.setLastCompletionTime(Instant.now());
                    repository.save(config);
                });
            }
        };
    }

    private void syncAuroraStatus(SchedulerJobConfigEntity config) {
        if (!AURORA_JOB_KEYS.contains(config.getJobKey())) {
            return;
        }

        if (!auroraProperties.isEnabled()) {
            if (config.getStatus() != SchedulerJobStatus.DISABLED_BY_CONFIG) {
                config.setStatus(SchedulerJobStatus.DISABLED_BY_CONFIG);
                config.setConfigSource(AURORA_CONFIG_SOURCE);
                config.setUpdatedAt(Instant.now());
                repository.save(config);
                LOG.info("Disabled job '{}' — aurora.enabled=false", config.getJobKey());
            }
        } else if (config.getStatus() == SchedulerJobStatus.DISABLED_BY_CONFIG) {
            config.setStatus(SchedulerJobStatus.ACTIVE);
            config.setUpdatedAt(Instant.now());
            repository.save(config);
            LOG.info("Re-enabled job '{}' — aurora.enabled=true", config.getJobKey());
        }
    }

    private void validateCron(String cronExpression) {
        try {
            CronExpression.parse(cronExpression);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression: " + cronExpression, e);
        }
    }

    private void validateFixedDelay(long fixedDelayMs) {
        if (fixedDelayMs < MIN_FIXED_DELAY_MS) {
            throw new IllegalArgumentException(
                    "Fixed delay must be at least " + MIN_FIXED_DELAY_MS + "ms, got: "
                            + fixedDelayMs);
        }
    }

    private void rejectIfDisabledByConfig(SchedulerJobConfigEntity config) {
        if (config.getStatus() == SchedulerJobStatus.DISABLED_BY_CONFIG) {
            throw new IllegalStateException(
                    "Job '" + config.getJobKey() + "' is disabled by config ("
                            + config.getConfigSource() + ")");
        }
    }
}
