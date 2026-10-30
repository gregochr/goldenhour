package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The forecast-cycle watchdog: the {@code forecast_cycle_watchdog} job, every ten minutes
 * (solar-relative-refresh-plan B2).
 *
 * <p>No forecast is the worst outcome this application can have, and nothing used to say so: a cycle
 * that never fired, or one still waiting on Anthropic as the light arrives, was found by whoever
 * opened the app. For each forecast cycle job ({@link ForecastCycleJobs#allCycleJobs()}: the two
 * solar-capable main cycles and the evening sunrise refresh) it checks two things and mails the
 * admins once per occurrence:
 * <ul>
 *   <li><b>Did not start.</b> The job is ACTIVE, its most recent scheduled fire time F is more than
 *       {@value #START_GRACE_MINUTES} minutes ago, and no {@code pipeline_run} of its cycle type has
 *       {@code trigger_time >= F}.</li>
 *   <li><b>Did not finish.</b> A run of its cycle type triggered today is still {@code RUNNING}, or
 *       has a FORECAST batch outside {@link BatchStatus#TERMINAL}, when the event it serves
 *       (nightly: today's sunrise; intraday: today's sunset, at the scheduler's reference point) is
 *       {@value #FINISH_LEAD_MINUTES} minutes away or closer, and until
 *       {@value #FINISH_TAIL_MINUTES} minutes after it. <b>The evening refresh has no served
 *       event</b> (it is a CRON job with no solar rule), so its check is different: its run ended
 *       FAILED by the safety timeout, or a FORECAST batch of the run is still outside
 *       {@link BatchStatus#TERMINAL} once {@code min(forecast_batch.submitted_at)} plus the evening
 *       budget has passed (the trigger time when no batch was submitted). A run still RUNNING past
 *       its budget with every batch finished is in its briefing build and is not an alert.</li>
 * </ul>
 *
 * <p><b>Finding F.</b> {@link CronExpression} has no "previous fire", so for a {@code CRON} job the
 * expression is walked forward from {@code now - 6 h} (in the job's own time zone, UTC unless the row
 * names one, as the scheduler does) and the last
 * fire at or before now is F; a job that fires more than {@value #MAX_CRON_STEPS} times in six hours
 * is not one this watchdog can reason about and is skipped. For a {@code SOLAR_OFFSET} job F is the
 * trigger's own fire for the UK date today and yesterday ({@link SolarOffsetTrigger#fireFor}), the
 * latest usable one at or before now; when today's solar fire is unusable the scheduler falls back
 * to the stored cron, and so does the watchdog. Any other schedule type has no F and is skipped.
 *
 * <p><b>The ceilings, and why.</b> F older than {@value #MAX_FIRE_AGE_HOURS} hours is ignored: this
 * check exists to catch a cycle that is late, and an outage longer than that is already known, so it
 * must not alert forever or, after a deploy, about yesterday. The "did not finish" window closes
 * {@value #FINISH_TAIL_MINUTES} minutes after the event, once there is no photographer left for a late
 * answer to help; the late-completion rebuild (B1) and the admin screens cover what lands after it.
 *
 * <p><b>Once per occurrence.</b> Each alert is latched in memory by (job, fire time) or (job, run id),
 * in a bounded set of the latest {@value #LATCH_CAPACITY}. A restart forgets it, so a condition still
 * true after a restart alerts once more (accepted: a restart mid-incident is worth a second mail).
 *
 * <p><b>It reads only.</b> It never starts, stops or retries a run, and nothing it does can throw out
 * of the job: every failure is caught and logged. Alerts need
 * {@code notifications.admin-alerts.enabled} and working mail, like Ask's spend cap.
 */
@Service
public class ForecastCycleWatchdog {

    /** Scheduler job key, matching the migration-seeded {@code scheduler_job_config} row. */
    public static final String JOB_KEY = "forecast_cycle_watchdog";

    /** How late a fire may be before it is a problem, in minutes. */
    static final int START_GRACE_MINUTES = 30;

    /** How long before the served event a still-running cycle is a problem, in minutes. */
    static final int FINISH_LEAD_MINUTES = 20;

    /** How long after the served event the "did not finish" check stays open, in minutes. */
    static final int FINISH_TAIL_MINUTES = 60;

    /** A fire older than this is not this watchdog's business, in hours. */
    static final int MAX_FIRE_AGE_HOURS = 6;

    /** The most cron fires the watchdog will step through in the look-back. */
    static final int MAX_CRON_STEPS = 500;

    /** The failure-reason prefix {@code PipelineOrchestrator} records when the wait budget is spent. */
    static final String SAFETY_TIMEOUT_PREFIX = "Safety timeout:";

    /** How many alert keys are remembered. */
    static final int LATCH_CAPACITY = 64;

    private static final Logger LOG = LoggerFactory.getLogger(ForecastCycleWatchdog.class);

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter UK_TIME =
            DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK).withZone(LONDON);

    private final SchedulerJobConfigRepository configRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final ForecastBatchRepository forecastBatchRepository;
    private final SolarTriggerFactory triggerFactory;
    private final AdminAlertService adminAlertService;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;
    private final Duration eveningBudget;
    private final Map<String, Boolean> alerted = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > LATCH_CAPACITY;
        }
    };

    /**
     * Constructs the watchdog.
     *
     * @param configRepository        the scheduler job rows
     * @param pipelineRunRepository   the pipeline run history
     * @param forecastBatchRepository the forecast batches
     * @param triggerFactory          builds the same solar trigger the scheduler uses
     * @param adminAlertService       mails the admins
     * @param dynamicSchedulerService scheduler to register the job target against
     * @param clock                   the application clock
     * @param eveningBudget           the evening cycle's wait budget
     *                                ({@code photocast.pipeline.evening-safety-timeout})
     */
    public ForecastCycleWatchdog(SchedulerJobConfigRepository configRepository,
            PipelineRunRepository pipelineRunRepository,
            ForecastBatchRepository forecastBatchRepository, SolarTriggerFactory triggerFactory,
            AdminAlertService adminAlertService, DynamicSchedulerService dynamicSchedulerService,
            Clock clock,
            @Value("${photocast.pipeline.evening-safety-timeout:PT2H}") Duration eveningBudget) {
        this.configRepository = configRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.forecastBatchRepository = forecastBatchRepository;
        this.triggerFactory = triggerFactory;
        this.adminAlertService = adminAlertService;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
        this.eveningBudget = eveningBudget;
    }

    /** Registers the check with the dynamic scheduler, resolving the migration-seeded row. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::check);
    }

    /**
     * Runs both checks for every forecast cycle job. Never throws.
     */
    public void check() {
        for (ForecastCycleJobs.Job job : ForecastCycleJobs.allCycleJobs()) {
            try {
                checkJob(job);
            } catch (RuntimeException e) {
                LOG.warn("Forecast-cycle watchdog: check of {} failed: {}", job.key(), e.getMessage(), e);
            }
        }
    }

    private void checkJob(ForecastCycleJobs.Job job) {
        Optional<SchedulerJobConfigEntity> found = configRepository.findByJobKey(job.key());
        if (found.isEmpty() || found.get().getStatus() != SchedulerJobStatus.ACTIVE) {
            return;
        }
        SchedulerJobConfigEntity config = found.get();
        CycleType cycle = job.cycleType();
        Instant now = clock.instant();
        LocalDate today = ForecastHorizon.civilDate(now);
        // A job with no solar rule (the evening) serves no event: never ask the calculator for one.
        Instant event = job.rule() == null ? null : eventInstant(config, job.rule(), today);

        lastFire(config, now, today).ifPresent(fire -> checkStarted(config, cycle, now, fire, event));
        if (job.rule() == null) {
            checkEveningFinished(config, cycle, now, today);
        } else {
            checkFinished(config, cycle, now, today, event);
        }
    }

    private void checkStarted(SchedulerJobConfigEntity config, CycleType cycle, Instant now,
            Instant fire, Instant event) {
        if (fire.isAfter(now.minus(Duration.ofMinutes(START_GRACE_MINUTES)))) {
            return;
        }
        if (pipelineRunRepository
                .findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(cycle, fire, now)
                .isPresent()) {
            return;
        }
        if (!firstTime("start:" + config.getJobKey() + ":" + fire)) {
            return;
        }
        String detail = "The " + cycle + " forecast cycle (" + config.getJobKey() + ", scheduled "
                + config.getScheduleType() + ") should have fired at " + UK_TIME.format(fire)
                + " UK time, " + Duration.between(fire, now).toMinutes() + " minutes ago, and no "
                + cycle + " pipeline run has been triggered since."
                + servedEvent(config, event)
                + "\n\nMissing: a pipeline_run of cycle type " + cycle + " with trigger_time at or "
                + "after the fire time. Check the scheduler (Operations > Scheduler), that the "
                + "application was running, and the application log.";
        LOG.error("Forecast-cycle watchdog: {} did not start (fire {}).", config.getJobKey(), fire);
        adminAlertService.sendForecastCycleWatchdogAlert(config.getJobKey(), "did not start", detail);
    }

    private void checkFinished(SchedulerJobConfigEntity config, CycleType cycle, Instant now,
            LocalDate today, Instant event) {
        if (event == null) {
            return;
        }
        Instant opens = event.minus(Duration.ofMinutes(FINISH_LEAD_MINUTES));
        Instant closes = event.plus(Duration.ofMinutes(FINISH_TAIL_MINUTES));
        if (now.isBefore(opens) || now.isAfter(closes)) {
            return;
        }
        Instant dayStart = today.atStartOfDay(LONDON).toInstant();
        List<PipelineRunEntity> runs = pipelineRunRepository
                .findByCycleTypeAndTriggerTimeGreaterThanEqualOrderByTriggerTimeAsc(cycle, dayStart);
        for (PipelineRunEntity run : runs) {
            String missing = unfinished(run);
            if (missing != null && firstTime("finish:" + config.getJobKey() + ":" + run.getId())) {
                String detail = "The " + cycle + " forecast cycle (" + config.getJobKey() + ") run "
                        + run.getId() + ", triggered " + UK_TIME.format(run.getTriggerTime())
                        + " UK time, has not finished and " + servedEventPhrase(config, event)
                        + " is " + describeUntil(now, event) + "."
                        + "\n\nMissing: " + missing;
                LOG.error("Forecast-cycle watchdog: {} run {} did not finish: {}",
                        config.getJobKey(), run.getId(), missing);
                adminAlertService.sendForecastCycleWatchdogAlert(
                        config.getJobKey(), "did not finish", detail);
            }
        }
    }

    /**
     * The evening refresh's "did not finish": the run was failed by the safety timeout, or a forecast
     * batch is still outstanding after the budget (measured from the first batch submit, else the
     * trigger). A RUNNING run with every batch finished is building its briefing: not an alert.
     */
    private void checkEveningFinished(SchedulerJobConfigEntity config, CycleType cycle, Instant now,
            LocalDate today) {
        // The evening fires at 21:00 UK time, so its deadline (and a timeout failure) can fall after UK
        // midnight: look back far enough to include any run whose deadline is recent. The extra hour
        // covers first-submit lag and several watchdog passes; the latch prevents repeats.
        Instant dayStart = today.atStartOfDay(LONDON).toInstant();
        Instant lowerBound = Collections.min(List.of(dayStart,
                now.minus(eveningBudget).minus(Duration.ofHours(1))));
        List<PipelineRunEntity> runs = pipelineRunRepository
                .findByCycleTypeAndTriggerTimeGreaterThanEqualOrderByTriggerTimeAsc(cycle, lowerBound);
        for (PipelineRunEntity run : runs) {
            String missing = eveningUnfinished(run, now);
            if (missing != null && firstTime("finish:" + config.getJobKey() + ":" + run.getId())) {
                String detail = "The " + cycle + " forecast cycle (" + config.getJobKey() + ") run "
                        + run.getId() + ", triggered " + UK_TIME.format(run.getTriggerTime())
                        + " UK time, did not finish within its " + eveningBudget.toMinutes()
                        + "-minute budget, so tomorrow morning's sunrise may not carry the evening look."
                        + "\n\nMissing: " + missing;
                LOG.error("Forecast-cycle watchdog: {} run {} did not finish: {}",
                        config.getJobKey(), run.getId(), missing);
                adminAlertService.sendForecastCycleWatchdogAlert(
                        config.getJobKey(), "did not finish", detail);
            }
        }
    }

    /** What the evening run is still owed, or null when it is within budget or finished. */
    private String eveningUnfinished(PipelineRunEntity run, Instant now) {
        if (run.getStatus() == PipelineRunStatus.FAILED && run.getFailureReason() != null
                && run.getFailureReason().startsWith(SAFETY_TIMEOUT_PREFIX)) {
            return "the run was failed by the safety timeout (" + run.getFailureReason() + ")";
        }
        List<ForecastBatchEntity> batches = forecastBatchRepository.findByPipelineRunId(run.getId())
                .stream()
                .filter(b -> b.getBatchType() == BatchType.FORECAST)
                .toList();
        Instant start = batches.stream().map(ForecastBatchEntity::getSubmittedAt)
                .filter(Objects::nonNull).min(Comparator.naturalOrder())
                .orElse(run.getTriggerTime());
        if (now.isBefore(start.plus(eveningBudget))) {
            return null;
        }
        long pending = batches.stream().filter(b -> !BatchStatus.TERMINAL.contains(b.getStatus())).count();
        return pending == 0 ? null
                : pending + " forecast batch(es) still not finished at Anthropic after the "
                        + eveningBudget.toMinutes() + "-minute budget";
    }

    /** What is still outstanding for a run, or null when it is finished. */
    private String unfinished(PipelineRunEntity run) {
        List<String> parts = new ArrayList<>();
        if (run.getStatus() == PipelineRunStatus.RUNNING) {
            parts.add("the run is still RUNNING (phase " + run.getCurrentPhase() + ")");
        }
        List<ForecastBatchEntity> pending = forecastBatchRepository.findByPipelineRunId(run.getId())
                .stream()
                .filter(b -> b.getBatchType() == BatchType.FORECAST)
                .filter(b -> !BatchStatus.TERMINAL.contains(b.getStatus()))
                .toList();
        if (!pending.isEmpty()) {
            parts.add(pending.size() + " forecast batch(es) still not finished at Anthropic");
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /**
     * The instant of the solar event the job serves today, or null when it cannot be computed or
     * does not occur (the calculator's midnight sentinel). Timed with a zero offset, so only the
     * event itself is read; the trigger's own sane-fire-range rules are deliberately not applied.
     */
    private Instant eventInstant(SchedulerJobConfigEntity config, ForecastCycleJobs.Rule rule,
            LocalDate today) {
        SolarEvent event = rule.event();
        Instant at = triggerFactory.create(config.getJobKey(), event, 0, config.getCronExpression())
                .fireFor(today).eventInstant();
        return at == null || at.equals(today.atStartOfDay(ZoneOffset.UTC).toInstant()) ? null : at;
    }

    /**
     * The most recent scheduled fire at or before now and no older than the ceiling, or empty when
     * the schedule has none to reason about.
     */
    private Optional<Instant> lastFire(SchedulerJobConfigEntity config, Instant now, LocalDate today) {
        Instant oldest = now.minus(Duration.ofHours(MAX_FIRE_AGE_HOURS));
        if (config.getScheduleType() == ScheduleType.SOLAR_OFFSET && config.getSolarEvent() != null
                && config.getSolarOffsetMinutes() != null) {
            SolarOffsetTrigger trigger = triggerFactory.create(config.getJobKey(),
                    config.getSolarEvent(), config.getSolarOffsetMinutes(), config.getCronExpression());
            SolarOffsetTrigger.Fire todays = trigger.fireFor(today);
            if (!todays.usable()) {
                return cronLastFire(config.getCronExpression(), DynamicSchedulerService.cronZone(config), now);
            }
            Instant latest = null;
            for (SolarOffsetTrigger.Fire fire : List.of(todays, trigger.fireFor(today.minusDays(1)))) {
                if (fire.usable() && !fire.fire().isAfter(now) && !fire.fire().isBefore(oldest)
                        && (latest == null || fire.fire().isAfter(latest))) {
                    latest = fire.fire();
                }
            }
            return Optional.ofNullable(latest);
        }
        if (config.getScheduleType() == ScheduleType.CRON) {
            return cronLastFire(config.getCronExpression(), DynamicSchedulerService.cronZone(config), now);
        }
        return Optional.empty();
    }

    private static Optional<Instant> cronLastFire(String expression, ZoneId zone, Instant now) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        CronExpression cron = CronExpression.parse(expression);
        ZonedDateTime nowLocal = now.atZone(zone);
        ZonedDateTime next = cron.next(nowLocal.minusHours(MAX_FIRE_AGE_HOURS));
        ZonedDateTime last = null;
        int steps = 0;
        while (next != null && !next.isAfter(nowLocal)) {
            if (++steps > MAX_CRON_STEPS) {
                return Optional.empty();
            }
            last = next;
            next = cron.next(next);
        }
        return Optional.ofNullable(last).map(ZonedDateTime::toInstant);
    }

    private synchronized boolean firstTime(String key) {
        return alerted.putIfAbsent(key, Boolean.TRUE) == null;
    }

    private static String servedEvent(SchedulerJobConfigEntity config, Instant event) {
        return event == null ? "" : " It serves " + servedEventPhrase(config, event) + ".";
    }

    private static String servedEventPhrase(SchedulerJobConfigEntity config, Instant event) {
        String name = ForecastCycleJobs.proposedRule(config.getJobKey()).event()
                .name().toLowerCase(Locale.ROOT);
        return "today's " + name + " at " + UK_TIME.format(event) + " UK";
    }

    private static String describeUntil(Instant now, Instant event) {
        long minutes = Duration.between(now, event).toMinutes();
        return minutes >= 0 ? minutes + " minutes away" : (-minutes) + " minutes past";
    }
}
