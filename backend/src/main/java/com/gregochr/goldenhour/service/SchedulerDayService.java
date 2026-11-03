package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerFireTrigger;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The data behind the Scheduler "Day's jobs" picture: what every job did, or is expected to do, on one UK civil day
 * (scheduler-redesign-plan.md D6).
 *
 * <p><b>Two shapes of job.</b> A <em>scheduled</em> job (a solar job, or a cron that fires at most
 * {@value DynamicSchedulerService#REPEATING_FIRES_PER_DAY} times in the day) lists each of its fires. A
 * <em>repeating</em> job (fixed-delay, or a busier cron) lists none: it serves the schedule, how many fires were
 * expected so far, the count of its fires by status, and only its FAILED and PARTIAL fires individually.
 *
 * <p><b>Fire status.</b> Past fires come from {@code scheduler_fire_log}, read once for every scheduled job. A fire
 * linked to a pipeline run (the three forecast cycles, whose scheduler thread returns after the submit phase) takes
 * its end time and status from the run, mapped as: {@code RUNNING} to RUNNING, {@code COMPLETED} to SUCCEEDED,
 * {@code DEGRADED} (briefed, but some submissions were lost) to PARTIAL, {@code FAILED} to FAILED; a FAILED or
 * PARTIAL run's {@code failure_reason} replaces the fire's summary. Fires still to come are
 * {@link DynamicSchedulerService#firesWithFallback} fires after now, status SCHEDULED, none for a paused or
 * off-in-config job.
 *
 * <p><b>Paused jobs and ticks.</b> A paused scheduled job lists its would-be fires (status PAUSED, never MISSED) from
 * {@link DynamicSchedulerService#firesIgnoringStatus}, the same arithmetic with the status guard dropped; a job off
 * in config lists none. A repeating cron job also lists its slot instants as {@code ticks} (null for a fixed-delay job
 * or a cron too busy to list, which the client draws as a band).
 *
 * <p><b>MISSED.</b> An expected fire is MISSED when it is more than {@value #MISSED_GRACE_MINUTES} minutes in the
 * past, no log row that was not an admin "Run now" lies within {@value #MATCH_TOLERANCE_MINUTES} minutes of it, and
 * the job's {@code schedule_changed_at} (when set) is before it. A catch-up row counts like a scheduled one. ⚠️
 * Caveat: there is no schedule history, so expected fires are computed from the schedule <em>as it is now</em>;
 * the {@code schedule_changed_at} cut-off hides the days a schedule edit, type switch, pause or resume could have
 * made wrong, but a fire log trimmed by retention (14 days) simply shows no row.
 *
 * <p>Read-only; the clock is the injected real one ({@code /api/admin} is never rewound).
 */
@Service
public class SchedulerDayService {

    /** How many days either side of today the endpoint serves. */
    static final int RANGE_DAYS = 7;

    /** A slot this recently past is still within its start-up leeway, not yet MISSED. */
    static final int MISSED_GRACE_MINUTES = 2;

    /** How close a log row's start must be to an expected fire to count as that fire. */
    static final int MATCH_TOLERANCE_MINUTES = 2;

    /** The most individual FAILED/PARTIAL fires listed for one repeating job. */
    static final int MAX_PROBLEMS = 50;

    private static final Duration MISSED_GRACE = Duration.ofMinutes(MISSED_GRACE_MINUTES);
    private static final Duration MATCH_TOLERANCE = Duration.ofMinutes(MATCH_TOLERANCE_MINUTES);
    private static final ZoneId LONDON = ZoneId.of(DynamicSchedulerService.UK_ZONE_ID);
    private static final List<SchedulerFireStatus> PROBLEM_STATUSES =
            List.of(SchedulerFireStatus.FAILED, SchedulerFireStatus.PARTIAL);

    /** How a fire is shown: the stored statuses, plus the two that are never stored. */
    public enum DayFireStatus {
        /** Expected, in the future. */
        SCHEDULED,
        /** Started, not finished. */
        RUNNING,
        /** Did its work. */
        SUCCEEDED,
        /** Ran, but some work failed or was left undone. */
        PARTIAL,
        /** Threw or reported failure. */
        FAILED,
        /** Deliberately did nothing. */
        SKIPPED,
        /** Expected in the past and left no log row (served only, never stored). */
        MISSED,
        /** A paused job's would-be fire (served only, never stored); never counted as missed. */
        PAUSED
    }

    /** Whether a job lists its fires or is summarised. */
    public enum Kind {
        /** Lists each fire. */
        SCHEDULED,
        /** Lists none: serves counts and problems. */
        REPEATING
    }

    /**
     * One fire of a scheduled job.
     *
     * @param at          when it fired, or was expected to
     * @param status      how it is shown
     * @param summary     the one-line outcome, or null
     * @param startedAt   when it started, or null for a SCHEDULED or MISSED fire
     * @param completedAt when it ended, or null while running, for a SCHEDULED or MISSED fire
     * @param trigger     {@code SCHEDULED}, {@code MANUAL} or {@code CATCH_UP}; null for a SCHEDULED or MISSED fire
     * @param fallback    whether a solar job's fire for that day came from its stored fallback cron
     */
    public record Fire(Instant at, DayFireStatus status, String summary, Instant startedAt, Instant completedAt,
            String trigger, boolean fallback) {
    }

    /**
     * A repeating job's schedule.
     *
     * @param scheduleType   {@code CRON} or {@code FIXED_DELAY}
     * @param cronExpression the cron, or null
     * @param delayMs        the fixed delay in ms, or null
     * @param timeZone       the zone a cron is read in ({@code UTC} when none is stored)
     */
    public record RepeatingSchedule(String scheduleType, String cronExpression, Long delayMs, String timeZone) {
    }

    /**
     * One FAILED or PARTIAL fire of a repeating job.
     *
     * @param status  FAILED or PARTIAL
     * @param at      when it fired
     * @param summary the one-line outcome, or null
     */
    public record Problem(DayFireStatus status, Instant at, String summary) {
    }

    /**
     * One job on the day.
     *
     * @param jobKey          the job key
     * @param displayName     the job's display name
     * @param kind            SCHEDULED (lists {@code fires}) or REPEATING (serves the rest)
     * @param fires           a scheduled job's fires in time order; null for a repeating job
     * @param schedule        a repeating job's schedule; null for a scheduled job
     * @param expectedCount   how many fires a repeating job should have had so far today; null for a scheduled job
     * @param counts          a repeating job's fires by stored status (every status present); null for a scheduled job
     * @param problems        a repeating job's FAILED and PARTIAL fires, oldest first, at most
     *                        {@value #MAX_PROBLEMS} (the newest); null for a scheduled job
     * @param problemsOmitted how many older problems were left out of {@code problems}; null for a scheduled job
     * @param ticks           a repeating cron job's slot instants that day, in order, when there are fewer than
     *                        {@value DynamicSchedulerService#MAX_FIRES_LISTED} (empty for a job that is off in
     *                        config); null for a fixed-delay job, a cron too busy to list (the client draws a
     *                        band) and a scheduled job
     */
    public record DayJob(String jobKey, String displayName, Kind kind, List<Fire> fires, RepeatingSchedule schedule,
            Long expectedCount, Map<String, Long> counts, List<Problem> problems, Integer problemsOmitted,
            List<Instant> ticks) {
    }

    /**
     * The day's tally of failing repeating jobs.
     *
     * @param failedRuns       FAILED fires across every repeating job
     * @param jobsWithFailures repeating jobs with at least one FAILED fire
     */
    public record RepeatingTally(long failedRuns, int jobsWithFailures) {
    }

    /**
     * An active job with no fire on the day that does fire on other days.
     *
     * @param jobKey      the job key
     * @param displayName the display name
     * @param nextFireAt  its next fire from now
     */
    public record NotOnThisDay(String jobKey, String displayName, Instant nextFireAt) {
    }

    /**
     * The whole day.
     *
     * @param date              the UK civil date
     * @param isToday           whether it is today
     * @param now               the real clock's instant
     * @param dayStart          the start of the UK civil day (23 or 25 hours long on a clock-change day)
     * @param dayEnd            the end of the UK civil day
     * @param zoneLabelAtStart  {@code GMT} or {@code BST} at the start
     * @param zoneLabelAtEnd    {@code GMT} or {@code BST} at the end
     * @param sunrise           sunrise at the solar schedules' reference point, or null if it does not occur
     * @param sunset            sunset there, or null
     * @param jobs              every job
     * @param repeatingTally    the failed-run tally of the repeating jobs
     * @param watchdog          the forecast-cycle watchdog's windows for the day
     * @param notOnThisDay      active jobs with no fire on the day
     */
    public record Day(LocalDate date, boolean isToday, Instant now, Instant dayStart, Instant dayEnd,
            String zoneLabelAtStart, String zoneLabelAtEnd, Instant sunrise, Instant sunset, List<DayJob> jobs,
            RepeatingTally repeatingTally, List<ForecastCycleWatchdog.Deadlines> watchdog,
            List<NotOnThisDay> notOnThisDay) {
    }

    private final DynamicSchedulerService scheduler;
    private final SchedulerFireLogRepository fireLog;
    private final PipelineRunRepository pipelineRuns;
    private final ForecastCycleWatchdog watchdog;
    private final SolarTriggerFactory solar;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param scheduler    the scheduler, for its fire arithmetic and job rows
     * @param fireLog      the scheduler fire log
     * @param pipelineRuns the pipeline runs a cycle fire is linked to
     * @param watchdog     the source of the watchdog windows
     * @param solar        the sunrise and sunset calculator at the solar schedules' reference point
     * @param clock        the application clock
     */
    public SchedulerDayService(DynamicSchedulerService scheduler, SchedulerFireLogRepository fireLog,
            PipelineRunRepository pipelineRuns, ForecastCycleWatchdog watchdog, SolarTriggerFactory solar,
            Clock clock) {
        this.scheduler = scheduler;
        this.fireLog = fireLog;
        this.pipelineRuns = pipelineRuns;
        this.watchdog = watchdog;
        this.solar = solar;
        this.clock = clock;
    }

    /**
     * Builds one UK civil day.
     *
     * @param requested the UK civil date, or null for today
     * @return the day
     * @throws IllegalArgumentException if the date is more than {@value #RANGE_DAYS} days from today
     */
    public Day day(LocalDate requested) {
        Instant now = clock.instant();
        LocalDate today = ForecastHorizon.today(clock);
        LocalDate date = requested == null ? today : requested;
        if (Math.abs(ChronoUnit.DAYS.between(today, date)) > RANGE_DAYS) {
            throw new IllegalArgumentException("date must be within " + RANGE_DAYS + " days of today (" + today + ")");
        }
        Instant dayStart = date.atStartOfDay(LONDON).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(LONDON).toInstant();
        Instant dayAfter = dayStart.minusSeconds(1);

        List<SchedulerJobConfigEntity> scheduled = new ArrayList<>();
        List<SchedulerJobConfigEntity> repeating = new ArrayList<>();
        for (SchedulerJobConfigEntity config : scheduler.getAll()) {
            (scheduler.isRepeating(config, dayAfter, dayEnd) ? repeating : scheduled).add(config);
        }

        Map<String, List<SchedulerFireLogEntity>> rowsByJob = rowsByJob(scheduled, dayStart, dayEnd);
        Map<Long, PipelineRunEntity> runs = linkedRuns(rowsByJob);
        Map<String, Map<SchedulerFireStatus, Long>> counts = counts(dayStart, dayEnd);
        Map<String, List<SchedulerFireLogEntity>> problemsByJob = problemsByJob(repeating, dayStart, dayEnd);

        List<DayJob> jobs = new ArrayList<>();
        List<NotOnThisDay> notOnThisDay = new ArrayList<>();
        for (SchedulerJobConfigEntity config : scheduled) {
            List<DaySlot> slots = scheduler.firesWithFallback(config, dayAfter, dayEnd).stream()
                    .map(f -> new DaySlot(f.at(), f.fallback())).toList();
            List<DaySlot> wouldBe = config.getStatus() == SchedulerJobStatus.PAUSED
                    ? scheduler.firesIgnoringStatus(config, dayAfter, dayEnd).stream()
                            .map(f -> new DaySlot(f.at(), f.fallback())).toList()
                    : List.of();
            jobs.add(scheduledJob(config, slots, wouldBe, rowsByJob.getOrDefault(config.getJobKey(), List.of()),
                    runs, now));
            if (slots.isEmpty() && config.getStatus() == SchedulerJobStatus.ACTIVE) {
                Instant next = scheduler.calculateNextFireTime(config);
                if (next != null) {
                    notOnThisDay.add(new NotOnThisDay(config.getJobKey(), config.getDisplayName(), next));
                }
            }
        }
        long failedRuns = 0;
        int jobsWithFailures = 0;
        for (SchedulerJobConfigEntity config : repeating) {
            Map<SchedulerFireStatus, Long> byStatus = counts.getOrDefault(config.getJobKey(), Map.of());
            jobs.add(repeatingJob(config, byStatus, problemsByJob.getOrDefault(config.getJobKey(), List.of()),
                    now, dayStart, dayEnd));
            long failed = byStatus.getOrDefault(SchedulerFireStatus.FAILED, 0L);
            failedRuns += failed;
            jobsWithFailures += failed > 0 ? 1 : 0;
        }

        List<ForecastCycleWatchdog.Deadlines> windows = ForecastCycleJobs.allCycleJobs().stream()
                .flatMap(job -> watchdog.deadlines(job.key(), date).stream()).toList();
        return new Day(date, date.equals(today), now, dayStart, dayEnd, zoneLabel(dayStart),
                zoneLabel(dayEnd.minusSeconds(1)), solar.eventInstant(SolarEvent.SUNRISE, date),
                solar.eventInstant(SolarEvent.SUNSET, date), jobs, new RepeatingTally(failedRuns, jobsWithFailures),
                windows, notOnThisDay);
    }

    /** One expected fire of a scheduled job. */
    private record DaySlot(Instant at, boolean fallback) {
    }

    private static String zoneLabel(Instant instant) {
        return LONDON.getRules().isDaylightSavings(instant) ? "BST" : "GMT";
    }

    private Map<String, List<SchedulerFireLogEntity>> rowsByJob(List<SchedulerJobConfigEntity> scheduled,
            Instant dayStart, Instant dayEnd) {
        if (scheduled.isEmpty()) {
            return Map.of();
        }
        return fireLog.findByJobKeyInAndFiredAtGreaterThanEqualAndFiredAtLessThanOrderByFiredAtAsc(
                scheduled.stream().map(SchedulerJobConfigEntity::getJobKey).toList(), dayStart, dayEnd).stream()
                .collect(Collectors.groupingBy(SchedulerFireLogEntity::getJobKey));
    }

    private Map<Long, PipelineRunEntity> linkedRuns(Map<String, List<SchedulerFireLogEntity>> rowsByJob) {
        List<Long> ids = rowsByJob.values().stream().flatMap(List::stream)
                .map(SchedulerFireLogEntity::getPipelineRunId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return pipelineRuns.findAllById(ids).stream()
                .collect(Collectors.toMap(PipelineRunEntity::getId, Function.identity()));
    }

    private Map<String, Map<SchedulerFireStatus, Long>> counts(Instant dayStart, Instant dayEnd) {
        Map<String, Map<SchedulerFireStatus, Long>> result = new LinkedHashMap<>();
        for (SchedulerFireLogRepository.FireCount count : fireLog.countByJobAndStatus(dayStart, dayEnd)) {
            result.computeIfAbsent(count.getJobKey(), k -> new EnumMap<>(SchedulerFireStatus.class))
                    .put(count.getStatus(), count.getTotal());
        }
        return result;
    }

    private Map<String, List<SchedulerFireLogEntity>> problemsByJob(List<SchedulerJobConfigEntity> repeating,
            Instant dayStart, Instant dayEnd) {
        if (repeating.isEmpty()) {
            return Map.of();
        }
        return fireLog.findByJobKeyInAndStatusInAndFiredAtGreaterThanEqualAndFiredAtLessThanOrderByFiredAtAsc(
                repeating.stream().map(SchedulerJobConfigEntity::getJobKey).toList(), PROBLEM_STATUSES, dayStart,
                dayEnd).stream().collect(Collectors.groupingBy(SchedulerFireLogEntity::getJobKey));
    }

    private DayJob scheduledJob(SchedulerJobConfigEntity config, List<DaySlot> slots, List<DaySlot> wouldBe,
            List<SchedulerFireLogEntity> rows, Map<Long, PipelineRunEntity> runs, Instant now) {
        List<Fire> fires = new ArrayList<>();
        for (SchedulerFireLogEntity row : rows) {
            PipelineRunEntity run = row.getPipelineRunId() == null ? null : runs.get(row.getPipelineRunId());
            fires.add(loggedFire(row, run, fallbackOf(slots, row)));
        }
        Instant missedBefore = now.minus(MISSED_GRACE);
        Instant changedAt = config.getScheduleChangedAt();
        for (DaySlot slot : slots) {
            if (slot.at().isAfter(now)) {
                fires.add(new Fire(slot.at(), DayFireStatus.SCHEDULED, null, null, null, null, slot.fallback()));
            } else if (slot.at().isBefore(missedBefore) && !satisfied(slot, rows)
                    && (changedAt == null || slot.at().isAfter(changedAt))) {
                fires.add(new Fire(slot.at(), DayFireStatus.MISSED, null, null, null, null, slot.fallback()));
            }
        }
        // A paused job: the slots it would have had that no log row stands for, never MISSED, past or future.
        for (DaySlot slot : wouldBe) {
            if (!satisfied(slot, rows)) {
                fires.add(new Fire(slot.at(), DayFireStatus.PAUSED, null, null, null, null, slot.fallback()));
            }
        }
        fires.sort(Comparator.comparing(Fire::at));
        return new DayJob(config.getJobKey(), config.getDisplayName(), Kind.SCHEDULED, fires, null, null, null, null,
                null, null);
    }

    /** Whether a log row stands for an expected fire: not an admin "Run now", and close enough in time. */
    private static boolean satisfies(SchedulerFireLogEntity row, DaySlot slot) {
        return row.getTriggerKind() != SchedulerFireTrigger.MANUAL
                && Duration.between(slot.at(), row.getFiredAt()).abs().compareTo(MATCH_TOLERANCE) <= 0;
    }

    private static boolean satisfied(DaySlot slot, List<SchedulerFireLogEntity> rows) {
        return rows.stream().anyMatch(row -> satisfies(row, slot));
    }

    private static boolean fallbackOf(List<DaySlot> slots, SchedulerFireLogEntity row) {
        return slots.stream().anyMatch(slot -> slot.fallback() && satisfies(row, slot));
    }

    private static Fire loggedFire(SchedulerFireLogEntity row, PipelineRunEntity run, boolean fallback) {
        DayFireStatus status = DayFireStatus.valueOf(row.getStatus().name());
        Instant completedAt = row.getCompletedAt();
        String summary = row.getSummary();
        if (run != null) {
            status = fromPipeline(run.getStatus());
            completedAt = run.getCompletedAt();
            String reason = run.getFailureReason();
            if ((status == DayFireStatus.FAILED || status == DayFireStatus.PARTIAL) && reason != null
                    && !reason.isBlank()) {
                summary = reason.length() <= DynamicSchedulerService.MAX_SUMMARY_LENGTH ? reason
                        : reason.substring(0, DynamicSchedulerService.MAX_SUMMARY_LENGTH);
            }
        }
        return new Fire(row.getFiredAt(), status, summary, row.getFiredAt(), completedAt,
                row.getTriggerKind().name(), fallback);
    }

    private static DayFireStatus fromPipeline(PipelineRunStatus status) {
        return switch (status) {
            case RUNNING -> DayFireStatus.RUNNING;
            case COMPLETED -> DayFireStatus.SUCCEEDED;
            case DEGRADED -> DayFireStatus.PARTIAL;
            case FAILED -> DayFireStatus.FAILED;
        };
    }

    private DayJob repeatingJob(SchedulerJobConfigEntity config, Map<SchedulerFireStatus, Long> byStatus,
            List<SchedulerFireLogEntity> problemRows, Instant now, Instant dayStart, Instant dayEnd) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SchedulerFireStatus status : SchedulerFireStatus.values()) {
            counts.put(status.name(), byStatus.getOrDefault(status, 0L));
        }
        int omitted = Math.max(0, problemRows.size() - MAX_PROBLEMS);
        List<Problem> problems = problemRows.subList(omitted, problemRows.size()).stream()
                .map(row -> new Problem(DayFireStatus.valueOf(row.getStatus().name()), row.getFiredAt(),
                        row.getSummary()))
                .toList();
        RepeatingSchedule schedule = new RepeatingSchedule(config.getScheduleTypeRaw(), config.getCronExpression(),
                config.getFixedDelayMs(),
                config.getTimeZone() == null || config.getTimeZone().isBlank() ? "UTC" : config.getTimeZone());
        return new DayJob(config.getJobKey(), config.getDisplayName(), Kind.REPEATING, null, schedule,
                expectedCount(config, now, dayStart, dayEnd), counts, problems, omitted,
                ticks(config, dayStart, dayEnd));
    }

    /**
     * A repeating cron job's slot instants on the day, from the scheduler's own arithmetic ignoring status (a paused
     * poller still has its ticks; one that is off in config has none). Null for a fixed-delay job, and for a cron
     * whose list reached the scheduler's cap (it may have been cut short, so the client draws a band instead).
     */
    private List<Instant> ticks(SchedulerJobConfigEntity config, Instant dayStart, Instant dayEnd) {
        if (config.getScheduleType() == ScheduleType.FIXED_DELAY) {
            return null;
        }
        if (config.getStatus() == SchedulerJobStatus.DISABLED_BY_CONFIG) {
            return List.of();
        }
        List<DynamicSchedulerService.ScheduledFire> fires =
                scheduler.firesIgnoringStatus(config, dayStart.minusSeconds(1), dayEnd);
        if (fires.size() >= DynamicSchedulerService.MAX_FIRES_LISTED) {
            return null;
        }
        return fires.stream().map(DynamicSchedulerService.ScheduledFire::at).toList();
    }

    /**
     * How many fires a repeating job should have made in the part of the day that has passed, from its schedule: a
     * fixed delay by elapsed time over the delay, a cron by stepping it (uncapped, in the job's zone) so the 23 and
     * 25 hour clock-change days count what the scheduler would fire. Zero for a job that is not ACTIVE, and the
     * count starts from the job's {@code schedule_changed_at} when that is later than the day's start.
     */
    private long expectedCount(SchedulerJobConfigEntity config, Instant now, Instant dayStart, Instant dayEnd) {
        if (config.getStatus() != SchedulerJobStatus.ACTIVE) {
            return 0;
        }
        Instant from = config.getScheduleChangedAt() != null && config.getScheduleChangedAt().isAfter(dayStart)
                ? config.getScheduleChangedAt() : dayStart;
        Instant to = now.isBefore(dayEnd) ? now : dayEnd;
        if (!to.isAfter(from)) {
            return 0;
        }
        if (config.getScheduleType() == ScheduleType.FIXED_DELAY) {
            Long delay = config.getFixedDelayMs();
            return delay == null || delay <= 0 ? 0 : Duration.between(from, to).toMillis() / delay;
        }
        // Anything else repeating is a cron that parsed when it was classified (isRepeating), so it parses here.
        CronExpression cron = CronExpression.parse(config.getCronExpression());
        ZonedDateTime next = cron.next(from.minusSeconds(1).atZone(DynamicSchedulerService.cronZone(config)));
        long count = 0;
        // Up to now inclusive, but never the next day's first fire: the day's end is exclusive.
        while (next != null && !next.toInstant().isAfter(to) && next.toInstant().isBefore(dayEnd)) {
            count++;
            next = cron.next(next);
        }
        return count;
    }
}
