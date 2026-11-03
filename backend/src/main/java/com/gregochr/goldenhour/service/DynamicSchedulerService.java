package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerFireTrigger;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.exception.JobDisabledByConfigException;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
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

    /** The latest UK wall-clock time a fire counts as "later today" for {@link #firesLaterTodayUk}. */
    private static final java.time.LocalTime LAST_RELIABLE_FIRE = java.time.LocalTime.of(23, 59);

    /** The UK civil zone {@link #firesLaterTodayUk} reasons in. */
    private static final ZoneId UK_ZONE = ZoneId.of("Europe/London");

    /** The most fires {@link #firesBetween} lists for one job, a bound for a cron that fires every second. */
    static final int MAX_FIRES_LISTED = 100;

    /** A schedule that fires more than this many times in a UK day is "repeating" rather than "scheduled". */
    public static final int REPEATING_FIRES_PER_DAY = 6;

    /** The one named zone a cron may be evaluated in besides UTC. */
    public static final String UK_ZONE_ID = "Europe/London";

    /** Longest offset a solar job may use, in minutes before the event. */
    private static final int MAX_SOLAR_OFFSET_MINUTES = 720;

    /** Longest fire-log summary stored; the column is {@code VARCHAR(200)}. */
    static final int MAX_SUMMARY_LENGTH = 200;

    /** Summary stamped on a fire the previous process never finished. */
    static final String ORPHAN_SUMMARY = "Interrupted by a restart";

    /** Config source value that indicates dependency on aurora.enabled. */
    private static final String AURORA_CONFIG_SOURCE = "aurora.enabled";

    private final SchedulerJobConfigRepository repository;
    private final SchedulerFireLogRepository fireLogRepository;
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
     * @param fireLogRepository the scheduler fire-log repository
     * @param taskScheduler    the dedicated task scheduler bean
     * @param auroraProperties aurora configuration for checking enabled state
     * @param solarTriggers    builds the trigger for a {@code SOLAR_OFFSET} job
     * @param solarCatchUp     decides whether a solar job that missed today's fire runs at startup
     * @param clock            the application clock, used for the displayed next fire of a solar job
     */
    public DynamicSchedulerService(SchedulerJobConfigRepository repository,
            SchedulerFireLogRepository fireLogRepository,
            TaskScheduler taskScheduler,
            AuroraProperties auroraProperties,
            SolarTriggerFactory solarTriggers,
            SolarCatchUpPolicy solarCatchUp,
            Clock clock) {
        this.repository = repository;
        this.fireLogRepository = fireLogRepository;
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
        sweepOrphanedFires();
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
     * Marks every fire the previous process left {@code RUNNING} as {@code FAILED} ("Interrupted by a
     * restart"). Runs before anything is scheduled, so no fire of this process exists yet and the cutoff
     * "now" catches exactly the leftovers. A failure is logged and never blocks startup.
     */
    void sweepOrphanedFires() {
        try {
            int swept = fireLogRepository.markOrphans(clock.instant(), ORPHAN_SUMMARY);
            if (swept > 0) {
                LOG.info("Marked {} scheduler fire(s) left running by the previous process as FAILED", swept);
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not sweep orphaned scheduler fires; startup continues: {}",
                    LogSanitizer.sanitize(e.toString()));
        }
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
        return updateSchedule(jobKey, cronExpression, fixedDelayMs, null);
    }

    /**
     * A requested change of the zone a cron is evaluated in.
     *
     * @param timeZone {@code Europe/London}, or {@code null} / blank / {@code UTC} for UTC (stored as null)
     */
    public record ZoneChange(String timeZone) {
    }

    /**
     * Updates the schedule for a job, optionally moving the zone its cron is evaluated in, and reschedules it if
     * ACTIVE.
     *
     * <p>The replacement schedule is built and validated <em>before</em> anything is changed, and
     * the new future is installed before the old one is cancelled, so a bad request leaves the
     * previous schedule running exactly as it was and no failure leaves the job unscheduled.
     *
     * <p>{@code time_zone} is {@code updatable = false} on the entity, so a zone change is written by one scoped
     * statement together with the cron and delay ({@link SchedulerJobConfigRepository#updateScheduleAndTimeZone}):
     * the cron and the zone it is read in can never be stored out of step. A request with no
     * {@link ZoneChange} leaves the stored zone exactly as it was.
     *
     * @param jobKey         the job key
     * @param cronExpression new cron expression (for CRON jobs), or null to keep current
     * @param fixedDelayMs   new fixed delay in ms (for FIXED_DELAY jobs), or null to keep current
     * @param zoneChange     the new cron zone, or null to keep the current one
     * @return the updated config entity
     * @throws IllegalArgumentException if the cron expression is invalid, the delay is too short,
     *         the resulting schedule would be incomplete, the zone is not {@code Europe/London} or UTC or is
     *         given for a job that is not a CRON job, or the job's stored schedule type is not
     *         one this build recognises (the call changes nothing)
     * @throws java.util.NoSuchElementException if the job key is not found
     */
    public SchedulerJobConfigEntity updateSchedule(String jobKey, String cronExpression,
            Long fixedDelayMs, ZoneChange zoneChange) {
        SchedulerJobConfigEntity config = repository.findByJobKey(jobKey).orElseThrow();

        ScheduleType type = config.getScheduleType();
        if (type == null) {
            throw new IllegalArgumentException("Job '" + jobKey + "' has a schedule type this "
                    + "version does not recognise ('" + config.getScheduleTypeRaw()
                    + "'); its schedule cannot be changed here");
        }

        String newCron = config.getCronExpression();
        Long newDelay = config.getFixedDelayMs();
        String newZone = config.getTimeZone();
        if (zoneChange != null) {
            if (type != ScheduleType.CRON) {
                throw new IllegalArgumentException("A time zone can only be set on a CRON job");
            }
            newZone = normaliseScheduleZone(zoneChange.timeZone());
        }
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
                config.getSolarOffsetMinutes(), newZone);

        SchedulerJobConfigEntity stored = config;
        if (zoneChange != null) {
            int updated = repository.updateScheduleAndTimeZone(config.getId(), newCron, newDelay, newZone,
                    Instant.now());
            if (updated != 1) {
                throw new IllegalStateException("Job '" + jobKey + "' schedule was not updated");
            }
            stored = repository.findByJobKey(jobKey).orElseThrow();
        } else {
            config.setCronExpression(newCron);
            config.setFixedDelayMs(newDelay);
            config.setUpdatedAt(Instant.now());
            repository.save(config);
        }
        markScheduleChanged(jobKey);

        if (stored.getStatus() == SchedulerJobStatus.ACTIVE) {
            install(stored, replacement);
        }

        // jobKey is an admin request path variable and the cron arrives in the request body —
        // both reach the log verbatim, so neither may carry line breaks into the audit trail.
        LOG.info("Updated schedule for '{}': type={}, cron={}, delay={}ms, zone={}",
                LogSanitizer.sanitize(jobKey), type,
                LogSanitizer.sanitize(stored.getCronExpression()), stored.getFixedDelayMs(),
                LogSanitizer.sanitize(stored.getTimeZone()));
        return stored;
    }

    /**
     * Validates a proposed zone for a cron and normalises it for storage.
     *
     * @param timeZone {@code Europe/London}, or {@code null} / blank / {@code UTC}
     * @return {@code Europe/London}, or null for UTC
     * @throws IllegalArgumentException for any other zone
     */
    public static String normaliseScheduleZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank() || "UTC".equals(timeZone.trim())) {
            return null;
        }
        if (UK_ZONE_ID.equals(timeZone.trim())) {
            return UK_ZONE_ID;
        }
        throw new IllegalArgumentException("timeZone must be " + UK_ZONE_ID + " or UTC, got '"
                + LogSanitizer.sanitize(timeZone) + "'");
    }

    /**
     * Validates a proposed schedule by exactly the rules {@link #updateSchedule} and {@link #switchScheduleType}
     * apply, and builds (but never installs) its trigger. Nothing is written or scheduled.
     *
     * @param jobKey   the job key, for the solar trigger's logs
     * @param type     the proposed schedule type
     * @param cron     the cron (CRON; the stored fallback for SOLAR_OFFSET)
     * @param delay    the fixed delay in ms (FIXED_DELAY)
     * @param event    the solar event (SOLAR_OFFSET)
     * @param offset   minutes before the event (SOLAR_OFFSET)
     * @param timeZone the zone id the cron is evaluated in, null for UTC
     * @throws IllegalArgumentException if the schedule would be refused
     */
    public void validateSchedule(String jobKey, ScheduleType type, String cron, Long delay, SolarEvent event,
            Integer offset, String timeZone) {
        if (type == ScheduleType.CRON && cron != null) {
            validateCron(cron);
        } else if (type == ScheduleType.FIXED_DELAY && delay != null) {
            validateFixedDelay(delay);
        }
        prepare(jobKey, type, cron, delay, event, offset, timeZone);
    }

    /**
     * Refuses {@code SOLAR_OFFSET} for a job other than the two forecast cycles.
     *
     * @param jobKey the job key
     * @throws IllegalArgumentException if the job may not be solar
     */
    public static void requireSolarAllowed(String jobKey) {
        if (!ForecastCycleJobs.allowed(jobKey)) {
            throw new IllegalArgumentException("Job '" + jobKey + "' may not be switched to "
                    + "SOLAR_OFFSET; only the two forecast cycle jobs may");
        }
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
            requireSolarAllowed(jobKey);
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
        markScheduleChanged(jobKey);
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
                taskScheduler.schedule(
                        wrapTarget(config.getJobKey(), target, SchedulerFireTrigger.CATCH_UP), Instant.now());
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
        markScheduleChanged(jobKey);

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
        markScheduleChanged(jobKey);
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

        taskScheduler.schedule(wrapTarget(jobKey, target, SchedulerFireTrigger.MANUAL), Instant.now());
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
            return clock.instant().plusMillis(initialDelay);
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
     * The fires of a CRON or SOLAR_OFFSET job after one instant and before another, by the same arithmetic the
     * scheduler itself uses: a cron in the job's own zone (UTC when it names none), a solar job by
     * {@link SolarOffsetTrigger#nextAfter}, which applies the stored-cron fallback when a day's solar fire is
     * unusable. A paused or disabled job, a fixed-delay job, an incomplete or unparseable schedule, and any
     * failure to compute one answer an empty list.
     *
     * @param config        the job config
     * @param afterExcl     the instant after which fires count
     * @param beforeExcl    the instant before which fires count
     * @return the fires in order, at most {@value #MAX_FIRES_LISTED}
     */
    public List<Instant> firesBetween(SchedulerJobConfigEntity config, Instant afterExcl, Instant beforeExcl) {
        return firesWithFallback(config, afterExcl, beforeExcl).stream().map(ScheduledFire::at).toList();
    }

    /**
     * One expected fire of a CRON or SOLAR_OFFSET job.
     *
     * @param at       when it fires
     * @param fallback whether a solar job's fire for that day comes from its stored fallback cron
     *                 ({@link SolarOffsetTrigger.Next#fallback()}); always false for a cron job
     */
    public record ScheduledFire(Instant at, boolean fallback) {
    }

    /**
     * {@link #firesBetween} with the solar trigger's own fallback flag on each fire, so a caller can say which
     * fires came from the fallback cron without redoing the trigger's arithmetic.
     *
     * @param config     the job config
     * @param afterExcl  the instant after which fires count
     * @param beforeExcl the instant before which fires count
     * @return the fires in order, at most {@value #MAX_FIRES_LISTED}; empty exactly when {@link #firesBetween} is
     */
    public List<ScheduledFire> firesWithFallback(SchedulerJobConfigEntity config, Instant afterExcl,
            Instant beforeExcl) {
        if (config.getStatus() != SchedulerJobStatus.ACTIVE) {
            return List.of();
        }
        return scheduleFires(config, afterExcl, beforeExcl);
    }

    /**
     * Whether a job is "repeating" over a window: a fixed-delay job, or a cron that fires more than
     * {@value #REPEATING_FIRES_PER_DAY} times in it. Independent of the job's status, so a paused poller is still
     * a poller.
     *
     * @param config     the job config
     * @param afterExcl  the window start
     * @param beforeExcl the window end
     * @return true when the job repeats
     */
    public boolean isRepeating(SchedulerJobConfigEntity config, Instant afterExcl, Instant beforeExcl) {
        if (config.getScheduleType() == ScheduleType.FIXED_DELAY) {
            return true;
        }
        return exceedsRepeatingThreshold(scheduleFires(config, afterExcl, beforeExcl).size());
    }

    /**
     * The one rule that separates a repeating job from a scheduled one.
     *
     * @param firesInADay how many times the schedule fires in a day
     * @return true when that is more than {@value #REPEATING_FIRES_PER_DAY}
     */
    public static boolean exceedsRepeatingThreshold(int firesInADay) {
        return firesInADay > REPEATING_FIRES_PER_DAY;
    }

    private List<ScheduledFire> scheduleFires(SchedulerJobConfigEntity config, Instant afterExcl,
            Instant beforeExcl) {
        List<ScheduledFire> fires = new java.util.ArrayList<>();
        try {
            ScheduleType type = config.getScheduleType();
            if (type == ScheduleType.CRON && config.getCronExpression() != null) {
                CronExpression cron = CronExpression.parse(config.getCronExpression());
                ZoneId zone = cronZone(config);
                ZonedDateTime cursor = afterExcl.atZone(zone);
                while (fires.size() < MAX_FIRES_LISTED) {
                    cursor = cron.next(cursor);
                    if (cursor == null || !cursor.toInstant().isBefore(beforeExcl)) {
                        break;
                    }
                    fires.add(new ScheduledFire(cursor.toInstant(), false));
                }
            } else if (type == ScheduleType.SOLAR_OFFSET && config.getSolarEvent() != null
                    && config.getSolarOffsetMinutes() != null) {
                SolarOffsetTrigger trigger = solarTriggers.create(config.getJobKey(), config.getSolarEvent(),
                        config.getSolarOffsetMinutes(), config.getCronExpression());
                Instant cursor = afterExcl;
                while (fires.size() < MAX_FIRES_LISTED) {
                    SolarOffsetTrigger.Next next = trigger.nextAfter(cursor, null);
                    if (!next.instant().isAfter(cursor) || !next.instant().isBefore(beforeExcl)) {
                        break;
                    }
                    fires.add(new ScheduledFire(next.instant(), next.fallback()));
                    cursor = next.instant();
                }
            }
        } catch (IllegalArgumentException e) {
            LOG.debug("Could not list the fires of job '{}': {}", LogSanitizer.sanitize(config.getJobKey()),
                    LogSanitizer.sanitize(String.valueOf(e.getMessage())));
            return List.of();
        }
        return fires;
    }

    /**
     * Whether the named job is certain enough to fire later today, UK time, that a caller may rely on it
     * as a later look at some slot.
     *
     * <p>True only when the job's row exists, is {@code ACTIVE}, and {@link #calculateNextFireTime} is
     * after now and no later than 23:59 in {@code Europe/London} on today's UK civil date. A paused or
     * config-disabled job, a missing row, a re-cron or sun-relative switch that moves the fire past
     * bedtime or off today, and any exception reading the row all answer {@code false}. The reason is
     * logged once per call at INFO, so the caller needs no logging of its own.
     *
     * @param jobKey the job to ask about
     * @return {@code true} when the job will fire between now and 23:59 UK time today
     */
    public boolean firesLaterTodayUk(String jobKey) {
        String safeKey = LogSanitizer.sanitize(jobKey);
        try {
            Optional<SchedulerJobConfigEntity> found = repository.findByJobKey(jobKey);
            if (found.isEmpty()) {
                LOG.info("Job '{}' has no scheduler row — not relying on it firing tonight", safeKey);
                return false;
            }
            SchedulerJobConfigEntity config = found.get();
            if (config.getStatus() != SchedulerJobStatus.ACTIVE) {
                LOG.info("Job '{}' is {} — not relying on it firing tonight", safeKey, config.getStatus());
                return false;
            }
            Instant next = calculateNextFireTime(config);
            Instant now = clock.instant();
            if (next == null || !next.isAfter(now)) {
                LOG.info("Job '{}' has no future fire time ({}) — not relying on it firing tonight",
                        safeKey, next);
                return false;
            }
            Instant bedtime = ForecastHorizon.today(clock).atTime(LAST_RELIABLE_FIRE)
                    .atZone(UK_ZONE).toInstant();
            if (next.isAfter(bedtime)) {
                LOG.info("Job '{}' next fires at {}, after {} UK today — not relying on it firing tonight",
                        safeKey, next, LAST_RELIABLE_FIRE);
                return false;
            }
            LOG.info("Job '{}' fires at {} — due tonight", safeKey, next);
            return true;
        } catch (RuntimeException e) {
            LOG.info("Job '{}' could not be read ({}) — not relying on it firing tonight", safeKey,
                    LogSanitizer.sanitize(String.valueOf(e.getMessage())));
            return false;
        }
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
        Runnable wrapped = wrapTarget(config.getJobKey(), target, SchedulerFireTrigger.SCHEDULED);
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

    /**
     * Wraps a job target so each fire is recorded (scheduler-redesign-plan.md D1, D2).
     *
     * <p>Per fire: an insert into {@code scheduler_fire_log} (RUNNING), the job body run with a
     * {@link SchedulerFireOutcome} carrier bound, and one scoped update completing the row. The job's
     * {@code last_fire_time} and {@code last_completion_time} are scoped updates too, not a read and a
     * whole-entity save, so a Pause or schedule edit committed while the job runs cannot be reverted by the
     * fire's own write. Nothing the log does can stop or fail a job; a job that throws is recorded FAILED
     * and the exception is rethrown unchanged.
     */
    private Runnable wrapTarget(String jobKey, Consumer<Boolean> target, SchedulerFireTrigger trigger) {
        boolean manual = trigger == SchedulerFireTrigger.MANUAL;
        return () -> {
            Instant fireTime = clock.instant();
            Long logId = startFireLog(jobKey, fireTime, trigger);
            SchedulerFireOutcome.Carrier carrier = new SchedulerFireOutcome.Carrier();
            SchedulerFireStatus status = SchedulerFireStatus.SUCCEEDED;
            String summary = null;
            try {
                repository.updateLastFireTime(jobKey, fireTime);
                SchedulerFireOutcome.runBound(carrier, () -> target.accept(manual));
                if (carrier.status() != null) {
                    status = carrier.status();
                }
                summary = carrier.summary();
            } catch (RuntimeException | Error e) {
                status = SchedulerFireStatus.FAILED;
                summary = e.getClass().getSimpleName() + ": " + e.getMessage();
                throw e;
            } finally {
                try {
                    repository.updateLastCompletionTime(jobKey, clock.instant());
                } finally {
                    completeFireLog(jobKey, logId, status, summary, carrier.pipelineRunId());
                }
            }
        };
    }

    /** Inserts the RUNNING row for a fire; a failure is logged and the fire goes ahead unlogged. */
    private Long startFireLog(String jobKey, Instant fireTime, SchedulerFireTrigger trigger) {
        try {
            SchedulerFireLogEntity row = new SchedulerFireLogEntity();
            row.setJobKey(jobKey);
            row.setFiredAt(fireTime);
            row.setStatus(SchedulerFireStatus.RUNNING);
            row.setTriggerKind(trigger);
            return fireLogRepository.save(row).getId();
        } catch (RuntimeException e) {
            LOG.warn("Could not record the start of a fire of job '{}'; the job runs unlogged: {}",
                    LogSanitizer.sanitize(jobKey), LogSanitizer.sanitize(e.toString()));
            return null;
        }
    }

    /** Completes a fire's log row; a failure is logged and never thrown. */
    private void completeFireLog(String jobKey, Long logId, SchedulerFireStatus status, String summary,
            Long pipelineRunId) {
        if (logId == null) {
            return;
        }
        try {
            fireLogRepository.complete(logId, clock.instant(), status, fireSummary(summary), pipelineRunId);
        } catch (RuntimeException e) {
            LOG.warn("Could not record the end of a fire of job '{}': {}", LogSanitizer.sanitize(jobKey),
                    LogSanitizer.sanitize(e.toString()));
        }
    }

    /** Sanitises a summary for storage and cuts it to the column's {@value #MAX_SUMMARY_LENGTH} characters. */
    private static String fireSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String clean = LogSanitizer.sanitize(summary);
        return clean.length() <= MAX_SUMMARY_LENGTH ? clean : clean.substring(0, MAX_SUMMARY_LENGTH);
    }

    /** Stamps {@code schedule_changed_at}; a failure is logged, never thrown (the change itself succeeded). */
    private void markScheduleChanged(String jobKey) {
        try {
            repository.updateScheduleChangedAt(jobKey, clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("Could not stamp schedule_changed_at for job '{}': {}", LogSanitizer.sanitize(jobKey),
                    LogSanitizer.sanitize(e.toString()));
        }
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
