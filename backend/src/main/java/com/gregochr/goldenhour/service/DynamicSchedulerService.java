package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.exception.JobDisabledByConfigException;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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

    /**
     * The zone a cron is evaluated in when the row names none, pinned so behaviour never depends on
     * the JVM default.
     */
    private static final ZoneOffset DEFAULT_SCHEDULE_ZONE = ZoneOffset.UTC;

    /** Longest offset a solar job may use, in minutes before the event. */
    private static final int MAX_SOLAR_OFFSET_MINUTES = 720;

    /** Config source value that indicates dependency on aurora.enabled. */
    private static final String AURORA_CONFIG_SOURCE = "aurora.enabled";

    private final SchedulerJobConfigRepository repository;
    private final TaskScheduler taskScheduler;
    private final AuroraProperties auroraProperties;
    private final SolarTriggerFactory solarTriggers;
    private final SolarCatchUpPolicy solarCatchUp;
    private final Clock clock;

    private final ConcurrentHashMap<String, Consumer<Boolean>> jobTargets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledFutures =
            new ConcurrentHashMap<>();
    private final ReentrantLock scheduleLock = new ReentrantLock();

    /**
     * Constructs the dynamic scheduler service.
     *
     * @param repository       the job config repository
     * @param taskScheduler    the dedicated task scheduler bean
     * @param auroraProperties aurora configuration for checking enabled state
     * @param solarTriggers    builds the trigger for a {@code SOLAR_OFFSET} job
     * @param solarCatchUp     decides whether a solar job that missed today's fire runs at startup
     * @param clock            the application clock, used for the displayed next fire of a solar job
     */
    public DynamicSchedulerService(SchedulerJobConfigRepository repository,
            TaskScheduler taskScheduler,
            AuroraProperties auroraProperties,
            SolarTriggerFactory solarTriggers,
            SolarCatchUpPolicy solarCatchUp,
            Clock clock) {
        this.repository = repository;
        this.taskScheduler = taskScheduler;
        this.auroraProperties = auroraProperties;
        this.solarTriggers = solarTriggers;
        this.solarCatchUp = solarCatchUp;
        this.clock = clock;
    }

    /**
     * Registers a job target runnable for a given job key.
     *
     * <p>Called by owning services during {@code @PostConstruct}. The target never learns whether
     * it was fired by the schedule or by {@link #triggerNow} — use
     * {@link #registerManualAwareJobTarget(String, Consumer)} for a job that needs to tell the two
     * apart.
     *
     * <p>Deliberately not overloaded on {@link Consumer} in this same argument position: two
     * functional interfaces sharing a slot forces every caller passing a bare Mockito {@code any()}
     * matcher, or a method reference to an overloaded method, into an explicit type witness to
     * disambiguate — friction on call sites that have nothing to do with the manual/scheduled
     * distinction. A distinct method name avoids that entirely.
     *
     * @param jobKey the unique job key matching a row in scheduler_job_config
     * @param target the runnable to execute when the job fires
     */
    public void registerJobTarget(String jobKey, Runnable target) {
        putTarget(jobKey, manual -> target.run());
    }

    /**
     * Registers a job target that is told whether it was fired manually.
     *
     * <p>The one hook a job needs to behave differently on the two routes without either route
     * changing for anyone else: {@link #triggerNow} (the admin "Run now" button) invokes it with
     * {@code true}, and an ordinary schedule firing invokes it with {@code false}. A job that
     * ignores the flag behaves exactly as {@link #registerJobTarget(String, Runnable)} always has.
     *
     * @param jobKey the unique job key matching a row in scheduler_job_config
     * @param target the target; receives {@code true} for a manual trigger, {@code false} for a
     *               scheduled fire
     */
    public void registerManualAwareJobTarget(String jobKey, Consumer<Boolean> target) {
        putTarget(jobKey, target);
    }

    private void putTarget(String jobKey, Consumer<Boolean> target) {
        jobTargets.put(jobKey, target);
        LOG.debug("Registered job target for key: {}", jobKey);
    }

    /**
     * Initialises all schedules from persisted config rows.
     *
     * <p>Checks aurora config and adjusts status for aurora-dependent jobs accordingly.
     *
     * <p>Each row is isolated: a row that is misconfigured, carries a schedule type this build
     * does not know, or whose scheduling throws is logged at ERROR and left unscheduled, and the
     * remaining rows are still scheduled. Nothing a single row does escapes this method.
     *
     * <p>Invoked at startup by {@link DynamicSchedulerBootstrap} (which is profile-gated
     * with {@code @Profile("!integration-test")}). Made a plain public method so that
     * unit tests can call it directly and integration tests using the
     * {@code integration-test} profile can choose when (or whether) to bootstrap.
     */
    public void initSchedules() {
        List<SchedulerJobConfigEntity> configs = repository.findAllByOrderByIdAsc();
        int failed = 0;
        for (SchedulerJobConfigEntity config : configs) {
            // One job's bad configuration (or a failing status write) must never stop the jobs
            // after it from being scheduled: each row stands or falls alone.
            try {
                syncConfigGatedStatus(config);
                if (config.getStatus() == SchedulerJobStatus.ACTIVE) {
                    scheduleJob(config);
                    catchUpIfMissed(config);
                } else {
                    LOG.info("Skipping job '{}' — status: {}",
                            LogSanitizer.sanitize(config.getJobKey()), config.getStatus());
                }
            } catch (RuntimeException e) {
                failed++;
                LOG.error("Could not schedule job '{}' — it stays unscheduled; other jobs are "
                        + "unaffected", LogSanitizer.sanitize(config.getJobKey()), e);
            }
        }
        LOG.info("Dynamic scheduler initialised — {} job(s) configured, {} failed to schedule",
                configs.size(), failed);
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
     * <p>The replacement schedule is built and validated <em>before</em> anything is changed, and
     * the new future is installed before the old one is cancelled, so a bad request leaves the
     * previous schedule running exactly as it was and no failure leaves the job unscheduled.
     *
     * @param jobKey         the job key
     * @param cronExpression new cron expression (for CRON jobs), or null to keep current
     * @param fixedDelayMs   new fixed delay in ms (for FIXED_DELAY jobs), or null to keep current
     * @return the updated config entity
     * @throws IllegalArgumentException if the cron expression is invalid, the delay is too short,
     *         the resulting schedule would be incomplete, or the job's stored schedule type is not
     *         one this build recognises (the call changes nothing)
     * @throws java.util.NoSuchElementException if the job key is not found
     */
    public SchedulerJobConfigEntity updateSchedule(String jobKey, String cronExpression,
            Long fixedDelayMs) {
        SchedulerJobConfigEntity config = repository.findByJobKey(jobKey).orElseThrow();

        ScheduleType type = config.getScheduleType();
        if (type == null) {
            throw new IllegalArgumentException("Job '" + jobKey + "' has a schedule type this "
                    + "version does not recognise ('" + config.getScheduleTypeRaw()
                    + "'); its schedule cannot be changed here");
        }

        String newCron = config.getCronExpression();
        Long newDelay = config.getFixedDelayMs();
        if (type == ScheduleType.CRON && cronExpression != null) {
            validateCron(cronExpression);
            newCron = cronExpression;
        } else if (type == ScheduleType.SOLAR_OFFSET && cronExpression != null) {
            // A solar job's cron is its fallback and revert target; it may be corrected here.
            validateCron(cronExpression);
            newCron = cronExpression;
        } else if (type == ScheduleType.FIXED_DELAY && fixedDelayMs != null) {
            validateFixedDelay(fixedDelayMs);
            newDelay = fixedDelayMs;
        }
        // Build the replacement now, before touching the entity or the live schedule.
        Installer replacement = prepare(jobKey, type, newCron, newDelay, config.getSolarEvent(),
                config.getSolarOffsetMinutes(), config.getTimeZone());

        config.setCronExpression(newCron);
        config.setFixedDelayMs(newDelay);
        config.setUpdatedAt(Instant.now());
        repository.save(config);

        if (config.getStatus() == SchedulerJobStatus.ACTIVE) {
            install(config, replacement);
        }

        // jobKey is an admin request path variable and the cron arrives in the request body —
        // both reach the log verbatim, so neither may carry line breaks into the audit trail.
        LOG.info("Updated schedule for '{}': type={}, cron={}, delay={}ms",
                LogSanitizer.sanitize(jobKey), type,
                LogSanitizer.sanitize(config.getCronExpression()), config.getFixedDelayMs());
        return config;
    }

    /**
     * Switches a job between {@code CRON} and {@code SOLAR_OFFSET}
     * (solar-relative-refresh-plan A3). This is also how a solar phase is rolled back.
     *
     * <p>The order is the safety property: the request is validated and the new trigger is built
     * <em>first</em>, with nothing written or cancelled; then one column-scoped database update
     * writes the type and its solar fields together; then, for an ACTIVE job, the new future is
     * installed before the old one is cancelled. A request that fails validation, or a database
     * update that fails, leaves both the stored row and the live schedule exactly as they were. If
     * installing fails after the update (not expected: it has been built already) the stored row is
     * put back so a restart cannot disagree with the live schedule, and the call fails.
     *
     * <p>The stored cron is never changed by a switch: it is the solar trigger's fallback and the
     * revert target. Only the two forecast cycle jobs may become {@code SOLAR_OFFSET}.
     * {@code FIXED_DELAY} is not a supported target.
     *
     * @param jobKey        the job key
     * @param newType       {@code CRON} or {@code SOLAR_OFFSET}
     * @param event         the solar event (required for {@code SOLAR_OFFSET}, ignored otherwise)
     * @param offsetMinutes minutes before the event, 0..720 (required for {@code SOLAR_OFFSET},
     *                      ignored otherwise)
     * @return the updated config entity
     * @throws IllegalArgumentException if the target type is unsupported, the job is not allowed to
     *         be solar, the solar fields are missing or out of range, or the stored cron is not
     *         valid (nothing changes)
     * @throws java.util.NoSuchElementException if the job key is not found
     */
    public SchedulerJobConfigEntity switchScheduleType(String jobKey, ScheduleType newType,
            SolarEvent event, Integer offsetMinutes) {
        SchedulerJobConfigEntity config = repository.findByJobKey(jobKey).orElseThrow();
        if (newType != ScheduleType.CRON && newType != ScheduleType.SOLAR_OFFSET) {
            throw new IllegalArgumentException("A job can only be switched to CRON or SOLAR_OFFSET, "
                    + "not " + newType);
        }
        SolarEvent newEvent = null;
        Integer newOffset = null;
        if (newType == ScheduleType.SOLAR_OFFSET) {
            if (!ForecastCycleJobs.allowed(jobKey)) {
                throw new IllegalArgumentException("Job '" + jobKey + "' may not be switched to "
                        + "SOLAR_OFFSET; only the two forecast cycle jobs may");
            }
            newEvent = event;
            newOffset = offsetMinutes;
        }
        // Validates everything (solar fields, the stored cron) and builds the trigger; no side
        // effect on the row or the live schedule has happened yet.
        Installer replacement = prepare(jobKey, newType, config.getCronExpression(),
                config.getFixedDelayMs(), newEvent, newOffset, config.getTimeZone());

        String previousType = config.getScheduleTypeRaw();
        String previousEvent = config.getSolarEventRaw();
        Integer previousOffset = config.getSolarOffsetMinutes();

        int updated = repository.updateScheduleType(config.getId(), newType.name(),
                newEvent == null ? null : newEvent.name(), newOffset, Instant.now());
        if (updated != 1) {
            throw new IllegalStateException("Job '" + jobKey + "' schedule type was not updated");
        }
        SchedulerJobConfigEntity reloaded = repository.findByJobKey(jobKey).orElseThrow();

        if (reloaded.getStatus() == SchedulerJobStatus.ACTIVE) {
            try {
                install(reloaded, replacement);
            } catch (RuntimeException e) {
                LOG.error("Installing the new schedule for '{}' failed after the type was stored; "
                        + "restoring the stored type so a restart agrees with the live schedule",
                        LogSanitizer.sanitize(jobKey), e);
                try {
                    repository.updateScheduleType(config.getId(), previousType, previousEvent,
                            previousOffset, Instant.now());
                } catch (RuntimeException restoreFailure) {
                    LOG.error("Restoring the stored schedule type for '{}' ALSO failed; the stored "
                            + "type is now {} but the live schedule is the previous one",
                            LogSanitizer.sanitize(jobKey), newType, restoreFailure);
                    e.addSuppressed(restoreFailure);
                }
                throw e;
            }
        }
        LOG.info("Switched schedule type for '{}' to {} (event={}, offset={}min); cron kept as {}",
                LogSanitizer.sanitize(jobKey), newType, newEvent, newOffset,
                LogSanitizer.sanitize(reloaded.getCronExpression()));
        return reloaded;
    }

    /**
     * Runs a solar job once now if it missed today's fire while the application was down
     * (solar-relative-refresh-plan D1). Called once per ACTIVE job at startup, after the normal
     * schedule has been installed; nothing here can prevent that, because every failure is caught
     * and logged.
     */
    private void catchUpIfMissed(SchedulerJobConfigEntity config) {
        // Only a job whose normal schedule actually installed is considered: a solar row that was
        // refused (incomplete, bad cron) must not get an unscheduled catch-up run either.
        if (config.getScheduleType() != ScheduleType.SOLAR_OFFSET
                || !scheduledFutures.containsKey(config.getJobKey())) {
            return;
        }
        try {
            Optional<String> reason = solarCatchUp.reasonToRun(config);
            Consumer<Boolean> target = jobTargets.get(config.getJobKey());
            if (reason.isPresent() && target != null) {
                taskScheduler.schedule(wrapTarget(config.getJobKey(), target, false), Instant.now());
                LOG.info("Catch-up run scheduled for solar job '{}': {}",
                        LogSanitizer.sanitize(config.getJobKey()), reason.get());
            }
        } catch (RuntimeException e) {
            LOG.error("Catch-up check for solar job '{}' failed; its normal schedule is unaffected",
                    LogSanitizer.sanitize(config.getJobKey()), e);
        }
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

        LOG.info("Paused job '{}'", LogSanitizer.sanitize(jobKey));
        return config;
    }

    /**
     * Resumes a paused job — sets status to ACTIVE and reschedules.
     *
     * <p>A job whose schedule cannot be built (an unrecognised stored type, or an incomplete
     * cron/delay) is not resumed: ERROR is logged, its status is left as it was, nothing is
     * scheduled and nothing is thrown, so the call cannot crash or half-apply.
     *
     * @param jobKey the job key
     * @return the updated config entity
     * @throws IllegalStateException if the job is DISABLED_BY_CONFIG
     * @throws java.util.NoSuchElementException if the job key is not found
     */
    public SchedulerJobConfigEntity resume(String jobKey) {
        SchedulerJobConfigEntity config = repository.findByJobKey(jobKey).orElseThrow();
        rejectIfDisabledByConfig(config);

        try {
            prepare(config);
        } catch (IllegalArgumentException e) {
            LOG.error("Cannot resume job '{}' — {}; status left as {}",
                    LogSanitizer.sanitize(jobKey), LogSanitizer.sanitize(e.getMessage()),
                    config.getStatus());
            return config;
        }

        config.setStatus(SchedulerJobStatus.ACTIVE);
        config.setUpdatedAt(Instant.now());
        repository.save(config);
        scheduleJob(config);

        LOG.info("Resumed job '{}'", LogSanitizer.sanitize(jobKey));
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

        Consumer<Boolean> target = jobTargets.get(jobKey);
        if (target == null) {
            throw new IllegalStateException("No registered target for job: " + jobKey);
        }

        taskScheduler.schedule(wrapTarget(jobKey, target, true), Instant.now());
        LOG.info("Triggered immediate run for job '{}' by {}", LogSanitizer.sanitize(jobKey),
                LogSanitizer.sanitize(actingUsername()));
    }

    /**
     * Names the authenticated caller on the request thread that reached {@link #triggerNow} (the admin
     * "Run now" button), so a manual run of a job that deletes data is attributable.
     *
     * @return the caller's username, or {@code "unknown"} when there is no authenticated caller
     */
    private static String actingUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null ? authentication.getName() : "unknown";
    }

    /**
     * Resolves the zone a job's cron is evaluated in.
     *
     * @param config the job config
     * @return UTC when the row has no {@code time_zone}, else the named zone
     * @throws IllegalArgumentException if the stored zone id is not recognised
     */
    static ZoneId cronZone(SchedulerJobConfigEntity config) {
        return cronZone(config.getTimeZone());
    }

    /**
     * Resolves a stored zone id.
     *
     * @param timeZone the zone id, {@code null} or blank for UTC
     * @return the zone
     * @throws IllegalArgumentException if the id is not recognised
     */
    static ZoneId cronZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return DEFAULT_SCHEDULE_ZONE;
        }
        try {
            return ZoneId.of(timeZone.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "unknown time zone '" + LogSanitizer.sanitize(timeZone) + "'", e);
        }
    }

    /**
     * Computes the next fire time for a job config.
     *
     * <p>For CRON jobs, uses {@link CronExpression#next(java.time.temporal.Temporal)} in the job's
     * own zone, read from the injected clock.
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

        ScheduleType type = config.getScheduleType();
        if (type == ScheduleType.CRON && config.getCronExpression() != null) {
            try {
                CronExpression cron = CronExpression.parse(config.getCronExpression());
                ZonedDateTime next = cron.next(ZonedDateTime.now(clock.withZone(cronZone(config))));
                return next != null ? next.toInstant() : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        if (type == ScheduleType.FIXED_DELAY && config.getFixedDelayMs() != null) {
            if (config.getLastCompletionTime() != null) {
                return config.getLastCompletionTime()
                        .plusMillis(config.getFixedDelayMs());
            }
            // Not yet run — next fire is approximately now + initial delay
            long initialDelay = config.getInitialDelayMs() != null
                    ? config.getInitialDelayMs() : config.getFixedDelayMs();
            return Instant.now().plusMillis(initialDelay);
        }

        if (type == ScheduleType.SOLAR_OFFSET && config.getSolarEvent() != null
                && config.getSolarOffsetMinutes() != null) {
            try {
                // The trigger's own arithmetic, so the displayed time can never disagree with the
                // time the scheduler will use.
                return solarTriggers.create(config.getJobKey(), config.getSolarEvent(),
                        config.getSolarOffsetMinutes(), config.getCronExpression())
                        .nextAfter(clock.instant(), null).instant();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        return null;
    }

    /**
     * Schedules a job based on its config.
     *
     * <p>An unrecognised schedule type, or an incomplete or unparseable cron/delay, is logged at
     * ERROR and leaves this one job unscheduled; nothing is thrown for it. Whatever schedule the
     * job already had is left in place.
     *
     * @param config the job config with schedule details
     */
    void scheduleJob(SchedulerJobConfigEntity config) {
        Consumer<Boolean> target = jobTargets.get(config.getJobKey());
        if (target == null) {
            LOG.warn("No registered target for job '{}' — skipping schedule",
                    LogSanitizer.sanitize(config.getJobKey()));
            return;
        }

        Installer installer;
        try {
            installer = prepare(config);
        } catch (IllegalArgumentException e) {
            LOG.error("Job '{}' left unscheduled — {} (stored schedule_type='{}', cron='{}', "
                    + "delay={})", LogSanitizer.sanitize(config.getJobKey()),
                    LogSanitizer.sanitize(e.getMessage()),
                    LogSanitizer.sanitize(config.getScheduleTypeRaw()),
                    LogSanitizer.sanitize(config.getCronExpression()), config.getFixedDelayMs());
            return;
        }
        install(config, installer);
    }

    /** Installs a prepared schedule for a job. */
    @FunctionalInterface
    private interface Installer {

        /**
         * Hands the wrapped job to the task scheduler.
         *
         * @param wrapped the job body
         * @return the future that controls it
         */
        ScheduledFuture<?> install(Runnable wrapped);
    }

    private Installer prepare(SchedulerJobConfigEntity config) {
        return prepare(config.getJobKey(), config.getScheduleType(), config.getCronExpression(),
                config.getFixedDelayMs(), config.getSolarEvent(), config.getSolarOffsetMinutes(),
                config.getTimeZone());
    }

    /**
     * Builds and validates a schedule without scheduling anything.
     *
     * @param jobKey the job key, for the solar trigger's logs
     * @param type   the schedule type, {@code null} when unrecognised
     * @param cron   the cron expression (CRON; the fallback for SOLAR_OFFSET)
     * @param delay  the fixed delay in ms (FIXED_DELAY)
     * @param event  the solar event (SOLAR_OFFSET)
     * @param offset the minutes before the event (SOLAR_OFFSET)
     * @param timeZone the zone id the CRON expression is evaluated in, {@code null} for UTC
     * @return the installer for the schedule
     * @throws IllegalArgumentException if the schedule cannot be built
     */
    private Installer prepare(String jobKey, ScheduleType type, String cron, Long delay,
            SolarEvent event, Integer offset, String timeZone) {
        if (type == null) {
            throw new IllegalArgumentException("unrecognised schedule type");
        }
        return switch (type) {
            case CRON -> {
                if (cron == null || cron.isBlank()) {
                    throw new IllegalArgumentException("CRON job has no cron expression");
                }
                ZoneId zone = cronZone(timeZone);
                CronTrigger trigger;
                try {
                    trigger = new CronTrigger(cron, zone);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("unparseable cron expression", e);
                }
                yield wrapped -> taskScheduler.schedule(wrapped, trigger);
            }
            case FIXED_DELAY -> {
                if (delay == null || delay <= 0) {
                    throw new IllegalArgumentException("FIXED_DELAY job has no positive delay");
                }
                Duration fixedDelay = Duration.ofMillis(delay);
                yield wrapped -> taskScheduler.scheduleWithFixedDelay(wrapped, fixedDelay);
            }
            case SOLAR_OFFSET -> {
                if (event == null || offset == null) {
                    throw new IllegalArgumentException(
                            "SOLAR_OFFSET job needs a solar event and an offset");
                }
                if (offset < 0 || offset > MAX_SOLAR_OFFSET_MINUTES) {
                    throw new IllegalArgumentException("solar offset must be 0.."
                            + MAX_SOLAR_OFFSET_MINUTES + " minutes, got " + offset);
                }
                // The stored cron is the trigger's fallback and the revert target: a solar job
                // without a valid one could, in the worst case, be left with no fire time at all.
                if (cron == null || cron.isBlank()) {
                    throw new IllegalArgumentException(
                            "SOLAR_OFFSET job needs a stored cron expression as its fallback");
                }
                try {
                    CronExpression.parse(cron);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "SOLAR_OFFSET job's fallback cron expression is unparseable", e);
                }
                SolarOffsetTrigger trigger = solarTriggers.create(jobKey, event, offset, cron);
                yield wrapped -> taskScheduler.schedule(wrapped, trigger);
            }
        };
    }

    /**
     * Installs the new future and only then cancels the one it replaces, so a failure to install
     * leaves the previous schedule running.
     */
    private void install(SchedulerJobConfigEntity config, Installer installer) {
        Consumer<Boolean> target = jobTargets.get(config.getJobKey());
        if (target == null) {
            LOG.warn("No registered target for job '{}' — skipping schedule",
                    LogSanitizer.sanitize(config.getJobKey()));
            return;
        }
        Runnable wrapped = wrapTarget(config.getJobKey(), target, false);
        scheduleLock.lock();
        try {
            ScheduledFuture<?> future = installer.install(wrapped);
            ScheduledFuture<?> previous = scheduledFutures.put(config.getJobKey(), future);
            if (previous != null) {
                previous.cancel(false);
            }
            LOG.info("Scheduled job '{}': type={}, cron={}, delay={}ms",
                    LogSanitizer.sanitize(config.getJobKey()), config.getScheduleType(),
                    LogSanitizer.sanitize(config.getCronExpression()), config.getFixedDelayMs());
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

    private Runnable wrapTarget(String jobKey, Consumer<Boolean> target, boolean manual) {
        return () -> {
            Instant fireTime = Instant.now();
            try {
                repository.findByJobKey(jobKey).ifPresent(config -> {
                    config.setLastFireTime(fireTime);
                    repository.save(config);
                });
                target.accept(manual);
            } finally {
                repository.findByJobKey(jobKey).ifPresent(config -> {
                    config.setLastCompletionTime(Instant.now());
                    repository.save(config);
                });
            }
        };
    }

    /**
     * Aligns a job's status with the feature flag named by its {@code config_source}.
     *
     * <p>Driven by the DB column rather than a hardcoded key set. The key set only ever listed
     * {@code aurora_polling}, so {@code aurora_batch_evaluation} — which declares
     * {@code aurora.enabled} in its V73 seed — was never gated: with aurora switched off, one
     * Resume click in the Scheduler UI was all that stood between the flag and a nightly NOAA
     * fetch plus Anthropic Batch spend. ({@code doSubmitAuroraBatch} now self-gates too; this
     * makes the UI tell the truth.)
     *
     * <p>An unrecognised {@code config_source} is a no-op, not a crash — the column is free text
     * and prod rows are hand-editable.
     *
     * <p>The pre-disable status is remembered so re-enabling restores it. Restoring blindly to
     * ACTIVE would promote {@code aurora_batch_evaluation} — deliberately seeded PAUSED — into a
     * running job on the first off/on toggle, which is the very spend this method prevents.
     *
     * @param config the job configuration, updated and saved in place when the flag disagrees
     */
    private void syncConfigGatedStatus(SchedulerJobConfigEntity config) {
        BooleanSupplier gate = configGateFor(config.getConfigSource());
        if (gate == null) {
            return;
        }

        if (!gate.getAsBoolean()) {
            if (config.getStatus() != SchedulerJobStatus.DISABLED_BY_CONFIG) {
                config.setStatusBeforeConfigDisable(config.getStatus());
                config.setStatus(SchedulerJobStatus.DISABLED_BY_CONFIG);
                config.setUpdatedAt(Instant.now());
                repository.save(config);
                LOG.info("Disabled job '{}' — {}=false", config.getJobKey(),
                        config.getConfigSource());
            }
        } else if (config.getStatus() == SchedulerJobStatus.DISABLED_BY_CONFIG) {
            SchedulerJobStatus restored = config.getStatusBeforeConfigDisable() != null
                    ? config.getStatusBeforeConfigDisable()
                    : SchedulerJobStatus.ACTIVE;
            config.setStatus(restored);
            config.setStatusBeforeConfigDisable(null);
            config.setUpdatedAt(Instant.now());
            repository.save(config);
            LOG.info("Re-enabled job '{}' as {} — {}=true", config.getJobKey(), restored,
                    config.getConfigSource());
        }
    }

    /**
     * Resolves the feature flag a {@code config_source} names.
     *
     * @param configSource the DB column value, possibly null or unrecognised
     * @return the flag supplier, or {@code null} when nothing gates this job
     */
    private BooleanSupplier configGateFor(String configSource) {
        if (AURORA_CONFIG_SOURCE.equals(configSource)) {
            return auroraProperties::isEnabled;
        }
        return null;
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
            throw new JobDisabledByConfigException(
                    "Job '" + config.getJobKey() + "' is disabled by config ("
                            + config.getConfigSource() + ")");
        }
    }
}
