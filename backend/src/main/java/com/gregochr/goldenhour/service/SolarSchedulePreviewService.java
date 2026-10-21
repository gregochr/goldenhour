package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the read-only preview of the two forecast cycles' solar rules
 * (solar-relative-refresh-plan §5): what they would fire, or do fire, and when.
 *
 * <p>⚠️ <b>Every figure comes from {@link SolarOffsetTrigger#fireFor}</b>, the arithmetic the
 * scheduler itself uses, so the preview can never disagree with what will fire. A job that is not
 * yet solar is shown under the plan's proposed rule ({@link ForecastCycleJobs#proposedRule}) with
 * {@code ruleSource} {@code "PROPOSED"}; a solar job under its stored one ({@code "STORED"}).
 * Times are ISO instants (the frontend formats them); the earliest and latest fire times of day are
 * UK local {@code HH:mm}. Nothing here writes anything.
 */
@Service
public class SolarSchedulePreviewService {

    /** How many UK days ahead the earliest and latest fire times are searched. */
    static final int YEAR_DAYS = 365;

    /** How many completed runs the median duration is taken over. */
    static final int DURATION_SAMPLE = 14;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final SchedulerJobConfigRepository configs;
    private final PipelineRunRepository pipelineRuns;
    private final SolarTriggerFactory triggerFactory;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param configs        the scheduler job rows
     * @param pipelineRuns   the pipeline run history
     * @param triggerFactory builds the scheduler's own trigger
     * @param clock          the application clock
     */
    public SolarSchedulePreviewService(SchedulerJobConfigRepository configs,
            PipelineRunRepository pipelineRuns, SolarTriggerFactory triggerFactory, Clock clock) {
        this.configs = configs;
        this.pipelineRuns = pipelineRuns;
        this.triggerFactory = triggerFactory;
        this.clock = clock;
    }

    /**
     * The reference point the rules are timed against.
     *
     * @param lat degrees north
     * @param lon degrees east
     */
    public record ReferencePoint(double lat, double lon) {
    }

    /**
     * One UK day's computed fire.
     *
     * @param date           the UK civil date
     * @param eventInstant   the solar event, or null when the calculation failed
     * @param fireInstant    the solar fire, or null when it is unusable
     * @param clamped        whether the fire was moved up to 00:05 UK
     * @param fallback       whether the scheduler would use the stored cron for this day instead
     * @param fallbackReason why, when {@code fallback}
     */
    public record DayFire(LocalDate date, Instant eventInstant, Instant fireInstant, boolean clamped,
            boolean fallback, String fallbackReason) {
    }

    /**
     * A day on which the fire time of day is extreme.
     *
     * @param date        the UK civil date
     * @param fireLocal   the fire time of day, UK local {@code HH:mm}
     * @param fireInstant the fire instant
     */
    public record Extreme(LocalDate date, String fireLocal, Instant fireInstant) {
    }

    /**
     * One forecast cycle's preview.
     *
     * @param jobKey                the job key
     * @param cycleType             the pipeline cycle type name
     * @param scheduleType          the job's stored schedule type string
     * @param status                the job's status, or null if the row does not exist
     * @param storedCron            the stored cron (the fallback), or null
     * @param solarEvent            the event the rule is timed against
     * @param offsetMinutes         minutes before the event
     * @param ruleSource            {@code STORED} (the job is solar) or {@code PROPOSED} (the plan's
     *                              rule, shown for a job that is not yet solar)
     * @param today                 today's computed fire
     * @param tomorrow              tomorrow's computed fire
     * @param earliest              the earliest fire time of day over the next 365 days
     * @param latest                the latest fire time of day over the next 365 days
     * @param medianDurationSeconds the median of the last 14 COMPLETED cycles' duration, null
     *                              when there are none
     */
    public record JobPreview(String jobKey, String cycleType, String scheduleType, String status,
            String storedCron, SolarEvent solarEvent, int offsetMinutes, String ruleSource,
            DayFire today, DayFire tomorrow, Extreme earliest, Extreme latest,
            Long medianDurationSeconds) {
    }

    /**
     * The whole preview.
     *
     * @param referencePoint the point the rules are timed against
     * @param generatedAt    when the preview was computed
     * @param jobs           the nightly then the intraday cycle
     */
    public record Preview(ReferencePoint referencePoint, Instant generatedAt,
            List<JobPreview> jobs) {
    }

    /**
     * Computes the preview.
     *
     * @return the preview for both forecast cycle jobs
     */
    public Preview preview() {
        Instant now = clock.instant();
        List<JobPreview> jobs = new ArrayList<>();
        for (ForecastCycleJobs.Job job : ForecastCycleJobs.solarCapableJobs()) {
            jobs.add(previewJob(job.key(), now));
        }
        SolarReferencePoint point = triggerFactory.referencePoint();
        return new Preview(new ReferencePoint(point.lat(), point.lon()), now, jobs);
    }

    private JobPreview previewJob(String jobKey, Instant now) {
        SchedulerJobConfigEntity config = configs.findByJobKey(jobKey).orElse(null);
        boolean solar = config != null && config.getScheduleType() == ScheduleType.SOLAR_OFFSET
                && config.getSolarEvent() != null && config.getSolarOffsetMinutes() != null;
        ForecastCycleJobs.Rule proposed = ForecastCycleJobs.proposedRule(jobKey);
        SolarEvent event = solar ? config.getSolarEvent() : proposed.event();
        int offset = solar ? config.getSolarOffsetMinutes() : proposed.offsetMinutes();
        String cron = config == null ? null : config.getCronExpression();

        SolarOffsetTrigger trigger = triggerFactory.create(jobKey, event, offset, cron);
        LocalDate today = ForecastHorizon.civilDate(now);

        Extreme earliest = null;
        Extreme latest = null;
        LocalTime earliestTime = null;
        LocalTime latestTime = null;
        for (int i = 0; i < YEAR_DAYS; i++) {
            SolarOffsetTrigger.Fire fire = trigger.fireFor(today.plusDays(i));
            if (!fire.usable()) {
                continue;
            }
            LocalTime local = fire.fire().atZone(SolarOffsetTrigger.LONDON).toLocalTime();
            if (earliestTime == null || local.isBefore(earliestTime)) {
                earliestTime = local;
                earliest = extreme(fire, local);
            }
            if (latestTime == null || local.isAfter(latestTime)) {
                latestTime = local;
                latest = extreme(fire, local);
            }
        }

        return new JobPreview(jobKey, ForecastCycleJobs.cycleOf(jobKey).name(),
                config == null ? null : config.getScheduleTypeRaw(),
                config == null ? null : config.getStatus().name(), cron, event, offset,
                solar ? "STORED" : "PROPOSED", dayFire(trigger.fireFor(today)),
                dayFire(trigger.fireFor(today.plusDays(1))), earliest, latest,
                medianDurationSeconds(jobKey));
    }

    private static Extreme extreme(SolarOffsetTrigger.Fire fire, LocalTime local) {
        return new Extreme(fire.day(), HH_MM.format(local), fire.fire());
    }

    private static DayFire dayFire(SolarOffsetTrigger.Fire fire) {
        return new DayFire(fire.day(), fire.eventInstant(), fire.fire(), fire.clamped(),
                !fire.usable(), fire.fallbackReason());
    }

    private Long medianDurationSeconds(String jobKey) {
        List<Long> seconds = pipelineRuns
                .findTop14ByCycleTypeAndStatusOrderByTriggerTimeDesc(
                        ForecastCycleJobs.cycleOf(jobKey), PipelineRunStatus.COMPLETED).stream()
                .filter(run -> run.getTriggerTime() != null && run.getCompletedAt() != null)
                .map(run -> Duration.between(run.getTriggerTime(), run.getCompletedAt()).getSeconds())
                .sorted(Comparator.naturalOrder())
                .toList();
        if (seconds.isEmpty()) {
            return null;
        }
        int n = seconds.size();
        return n % 2 == 1 ? seconds.get(n / 2) : (seconds.get(n / 2 - 1) + seconds.get(n / 2)) / 2;
    }
}
