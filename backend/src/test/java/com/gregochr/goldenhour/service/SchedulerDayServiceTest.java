package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerFireTrigger;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link SchedulerDayService} against the real scheduler arithmetic. Friday 5 March 2027 (GMT) is the ordinary
 * day; 28 March 2027 (23 hours) and 31 October 2027 (25 hours) are the clock-change days.
 */
class SchedulerDayServiceTest {

    private static final Instant NOON = Instant.parse("2027-03-05T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2027, 3, 5);
    private static final Instant DAY_START = Instant.parse("2027-03-05T00:00:00Z");
    private static final Instant DAY_END = Instant.parse("2027-03-06T00:00:00Z");
    private static final List<SchedulerFireStatus> PROBLEMS =
            List.of(SchedulerFireStatus.FAILED, SchedulerFireStatus.PARTIAL);
    private static final long RUN_ID = 77L;

    private final SolarTriggerFactory solar = new SolarTriggerFactory(new SolarService(),
            new SolarReferencePoint("54.776", "-1.575"));

    private SchedulerJobConfigRepository configs;
    private SchedulerFireLogRepository fireLog;
    private PipelineRunRepository pipelineRuns;
    private ForecastBatchRepository batchRepo;
    private final List<SchedulerJobConfigEntity> all = new ArrayList<>();

    /** A (job, status, count) row of the GROUP BY. */
    private record Count(String getJobKey, SchedulerFireStatus getStatus, long getTotal)
            implements SchedulerFireLogRepository.FireCount {
    }

    @BeforeEach
    void setUp() {
        configs = mock(SchedulerJobConfigRepository.class);
        fireLog = mock(SchedulerFireLogRepository.class);
        pipelineRuns = mock(PipelineRunRepository.class);
        batchRepo = mock(ForecastBatchRepository.class);
        when(configs.findAllByOrderByIdAsc()).thenReturn(all);
    }

    private SchedulerDayService service(Instant now, SolarTriggerFactory factory) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        DynamicSchedulerService scheduler = new DynamicSchedulerService(configs,
                mock(SchedulerFireLogRepository.class), mock(TaskScheduler.class), mock(AuroraProperties.class),
                factory, mock(SolarCatchUpPolicy.class), clock);
        ForecastCycleWatchdog watchdog = new ForecastCycleWatchdog(configs, pipelineRuns,
                batchRepo, factory, mock(AdminAlertService.class), scheduler, clock,
                Duration.ofMinutes(90));
        return new SchedulerDayService(scheduler, fireLog, pipelineRuns, watchdog, factory, clock);
    }

    private SchedulerDayService service(Instant now) {
        return service(now, solar);
    }

    private static SchedulerJobConfigEntity.SchedulerJobConfigEntityBuilder job(String key, ScheduleType type) {
        return SchedulerJobConfigEntity.builder().id((long) key.hashCode()).jobKey(key).displayName("Name of " + key)
                .scheduleType(type).status(SchedulerJobStatus.ACTIVE).updatedAt(NOON);
    }

    private SchedulerJobConfigEntity cron(String key, String cron) {
        SchedulerJobConfigEntity config = job(key, ScheduleType.CRON).cronExpression(cron).build();
        all.add(config);
        return config;
    }

    private static SchedulerFireLogEntity row(String key, Instant firedAt, SchedulerFireStatus status,
            SchedulerFireTrigger trigger) {
        SchedulerFireLogEntity row = new SchedulerFireLogEntity();
        row.setJobKey(key);
        row.setFiredAt(firedAt);
        row.setCompletedAt(status == SchedulerFireStatus.RUNNING ? null : firedAt.plusSeconds(4));
        row.setStatus(status);
        row.setTriggerKind(trigger);
        row.setSummary("summary of " + status);
        return row;
    }

    private void rowsFor(List<String> scheduledKeys, Instant from, Instant to, SchedulerFireLogEntity... rows) {
        when(fireLog.findByJobKeyInAndFiredAtGreaterThanEqualAndFiredAtLessThanOrderByFiredAtAsc(scheduledKeys,
                from, to)).thenReturn(List.of(rows));
    }

    private SchedulerDayService.DayJob jobOf(SchedulerDayService.Day day, String key) {
        return day.jobs().stream().filter(j -> j.jobKey().equals(key)).findFirst().orElseThrow();
    }

    private static List<SchedulerDayService.DayFireStatus> statuses(SchedulerDayService.DayJob job) {
        return job.fires().stream().map(SchedulerDayService.Fire::status).toList();
    }

    // -------------------------------------------------------------------------
    // The day itself
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an ordinary day: today's flags, GMT at both ends, the 24 hour axis, and the solar reference point's "
            + "sunrise and sunset")
    void ordinaryDay() {
        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(day.date()).isEqualTo(DAY);
        assertThat(day.isToday()).isTrue();
        assertThat(day.now()).isEqualTo(NOON);
        assertThat(day.dayStart()).isEqualTo(DAY_START);
        assertThat(day.dayEnd()).isEqualTo(DAY_END);
        assertThat(day.zoneLabelAtStart()).isEqualTo("GMT");
        assertThat(day.zoneLabelAtEnd()).isEqualTo("GMT");
        assertThat(day.sunrise()).isEqualTo(solar.eventInstant(SolarEvent.SUNRISE, DAY));
        assertThat(day.sunset()).isEqualTo(solar.eventInstant(SolarEvent.SUNSET, DAY));
        assertThat(day.sunrise()).isBefore(day.sunset());
    }

    @Test
    @DisplayName("a past and a future date are not today; the axis follows the requested date")
    void pastAndFutureDays() {
        SchedulerDayService service = service(NOON);

        SchedulerDayService.Day yesterday = service.day(DAY.minusDays(1));
        SchedulerDayService.Day tomorrow = service.day(DAY.plusDays(1));

        assertThat(yesterday.isToday()).isFalse();
        assertThat(yesterday.dayStart()).isEqualTo(DAY_START.minus(Duration.ofDays(1)));
        assertThat(yesterday.dayEnd()).isEqualTo(DAY_START);
        assertThat(tomorrow.isToday()).isFalse();
        assertThat(tomorrow.dayStart()).isEqualTo(DAY_END);
    }

    @Test
    @DisplayName("dates up to seven days either side of today are served; eight is refused")
    void dateRange() {
        SchedulerDayService service = service(NOON);

        assertThat(service.day(DAY.plusDays(7)).date()).isEqualTo(LocalDate.of(2027, 3, 12));
        assertThat(service.day(DAY.minusDays(7)).date()).isEqualTo(LocalDate.of(2027, 2, 26));
        assertThatThrownBy(() -> service.day(DAY.plusDays(8))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("date must be within 7 days of today (2027-03-05)");
        assertThatThrownBy(() -> service.day(DAY.minusDays(8))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("date must be within 7 days of today (2027-03-05)");
    }

    @Test
    @DisplayName("today is the UK civil date: just after midnight UTC in British summer time is still yesterday")
    void todayIsTheUkCivilDate() {
        // 2027-10-30T23:30Z is 2027-10-31 00:30 BST.
        SchedulerDayService.Day day = service(Instant.parse("2027-10-30T23:30:00Z")).day(null);

        assertThat(day.date()).isEqualTo(LocalDate.of(2027, 10, 31));
        assertThat(day.isToday()).isTrue();
    }

    @Test
    @DisplayName("the spring-forward day is 23 hours long, GMT to BST")
    void springForwardDay() {
        SchedulerDayService.Day day = service(Instant.parse("2027-03-28T12:00:00Z")).day(null);

        assertThat(day.dayStart()).isEqualTo(Instant.parse("2027-03-28T00:00:00Z"));
        assertThat(day.dayEnd()).isEqualTo(Instant.parse("2027-03-28T23:00:00Z"));
        assertThat(Duration.between(day.dayStart(), day.dayEnd())).isEqualTo(Duration.ofHours(23));
        assertThat(day.zoneLabelAtStart()).isEqualTo("GMT");
        assertThat(day.zoneLabelAtEnd()).isEqualTo("BST");
    }

    @Test
    @DisplayName("the fall-back day is 25 hours long, BST to GMT")
    void fallBackDay() {
        SchedulerDayService.Day day = service(Instant.parse("2027-10-31T12:00:00Z")).day(null);

        assertThat(day.dayStart()).isEqualTo(Instant.parse("2027-10-30T23:00:00Z"));
        assertThat(day.dayEnd()).isEqualTo(Instant.parse("2027-11-01T00:00:00Z"));
        assertThat(Duration.between(day.dayStart(), day.dayEnd())).isEqualTo(Duration.ofHours(25));
        assertThat(day.zoneLabelAtStart()).isEqualTo("BST");
        assertThat(day.zoneLabelAtEnd()).isEqualTo("GMT");
    }

    // -------------------------------------------------------------------------
    // Scheduled jobs: logged, scheduled and missed fires
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a logged fire carries its row; a later slot is SCHEDULED; the job is SCHEDULED, not repeating")
    void loggedAndFutureFires() {
        cron("tide_refresh", "0 0 2,18 * * *");
        rowsFor(List.of("tide_refresh"), DAY_START, DAY_END,
                row("tide_refresh", Instant.parse("2027-03-05T02:00:01Z"), SchedulerFireStatus.SUCCEEDED,
                        SchedulerFireTrigger.SCHEDULED));

        SchedulerDayService.DayJob job = jobOf(service(NOON).day(null), "tide_refresh");

        assertThat(job.kind()).isEqualTo(SchedulerDayService.Kind.SCHEDULED);
        assertThat(job.displayName()).isEqualTo("Name of tide_refresh");
        assertThat(job.fires()).containsExactly(
                new SchedulerDayService.Fire(Instant.parse("2027-03-05T02:00:01Z"),
                        SchedulerDayService.DayFireStatus.SUCCEEDED, "summary of SUCCEEDED",
                        Instant.parse("2027-03-05T02:00:01Z"), Instant.parse("2027-03-05T02:00:05Z"), "SCHEDULED",
                        false, false),
                new SchedulerDayService.Fire(Instant.parse("2027-03-05T18:00:00Z"),
                        SchedulerDayService.DayFireStatus.SCHEDULED, null, null, null, null, false, false));
        assertThat(job.schedule()).isNull();
        assertThat(job.counts()).isNull();
        assertThat(job.problems()).isNull();
        assertThat(job.expectedCount()).isNull();
    }

    @Test
    @DisplayName("a RUNNING fire has no end; every stored status maps to the same-named day status")
    void storedStatusesMap() {
        cron("a_job", "0 0 1 * * *");
        rowsFor(List.of("a_job"), DAY_START, DAY_END,
                row("a_job", Instant.parse("2027-03-05T01:00:00Z"), SchedulerFireStatus.RUNNING,
                        SchedulerFireTrigger.SCHEDULED),
                row("a_job", Instant.parse("2027-03-05T04:00:00Z"), SchedulerFireStatus.FAILED,
                        SchedulerFireTrigger.MANUAL),
                row("a_job", Instant.parse("2027-03-05T05:00:00Z"), SchedulerFireStatus.PARTIAL,
                        SchedulerFireTrigger.MANUAL),
                row("a_job", Instant.parse("2027-03-05T06:00:00Z"), SchedulerFireStatus.SKIPPED,
                        SchedulerFireTrigger.MANUAL));

        SchedulerDayService.DayJob job = jobOf(service(NOON).day(null), "a_job");

        assertThat(statuses(job)).containsExactly(SchedulerDayService.DayFireStatus.RUNNING,
                SchedulerDayService.DayFireStatus.FAILED, SchedulerDayService.DayFireStatus.PARTIAL,
                SchedulerDayService.DayFireStatus.SKIPPED);
        assertThat(job.fires().get(0).completedAt()).isNull();
        assertThat(job.fires().get(1).trigger()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("an expected fire more than two minutes past with no log row is MISSED")
    void missed_noRow() {
        cron("a_job", "0 0 2 * * *");
        rowsFor(List.of("a_job"), DAY_START, DAY_END);

        SchedulerDayService.DayJob job = jobOf(service(NOON).day(null), "a_job");

        assertThat(job.fires()).containsExactly(new SchedulerDayService.Fire(Instant.parse("2027-03-05T02:00:00Z"),
                SchedulerDayService.DayFireStatus.MISSED, null, null, null, null, false, false));
    }

    @Test
    @DisplayName("a row within two minutes of the slot satisfies it (no MISSED); one just outside does not and is "
            + "listed as its own fire")
    void missed_rowTolerance() {
        cron("inside", "0 0 3 * * *");
        cron("outside", "0 0 4 * * *");
        rowsFor(List.of("inside", "outside"), DAY_START, DAY_END,
                row("inside", Instant.parse("2027-03-05T03:02:00Z"), SchedulerFireStatus.SUCCEEDED,
                        SchedulerFireTrigger.SCHEDULED),
                row("outside", Instant.parse("2027-03-05T04:02:01Z"), SchedulerFireStatus.SUCCEEDED,
                        SchedulerFireTrigger.SCHEDULED));

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(statuses(jobOf(day, "inside"))).containsExactly(SchedulerDayService.DayFireStatus.SUCCEEDED);
        assertThat(jobOf(day, "outside").fires())
                .extracting(SchedulerDayService.Fire::at, SchedulerDayService.Fire::status)
                .containsExactly(
                        tuple(Instant.parse("2027-03-05T04:00:00Z"),
                                SchedulerDayService.DayFireStatus.MISSED),
                        tuple(Instant.parse("2027-03-05T04:02:01Z"),
                                SchedulerDayService.DayFireStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("a row two minutes before the slot also satisfies it")
    void missed_rowEarlyWithinTolerance() {
        cron("early", "0 0 3 * * *");
        rowsFor(List.of("early"), DAY_START, DAY_END,
                row("early", Instant.parse("2027-03-05T02:58:00Z"), SchedulerFireStatus.SUCCEEDED,
                        SchedulerFireTrigger.SCHEDULED));

        assertThat(statuses(jobOf(service(NOON).day(null), "early")))
                .containsExactly(SchedulerDayService.DayFireStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("a slot is not MISSED until two minutes have passed: at the boundary and inside the grace it is "
            + "neither missed nor listed; a minute earlier it is MISSED")
    void missed_graceWindow() {
        cron("just_missed", "0 57 11 * * *");
        cron("on_the_boundary", "0 58 11 * * *");
        cron("in_grace", "0 59 11 * * *");
        rowsFor(List.of("just_missed", "on_the_boundary", "in_grace"), DAY_START, DAY_END);

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(statuses(jobOf(day, "just_missed"))).containsExactly(SchedulerDayService.DayFireStatus.MISSED);
        assertThat(jobOf(day, "on_the_boundary").fires()).isEmpty();
        assertThat(jobOf(day, "in_grace").fires()).isEmpty();
    }

    @Test
    @DisplayName("a slot at or before schedule_changed_at is not MISSED (no schedule history); one after it is")
    void missed_scheduleChangedAt() {
        cron("never_changed", "0 0 5 * * *");
        SchedulerJobConfigEntity justBefore = job("just_before", ScheduleType.CRON).cronExpression("0 0 5 * * *")
                .scheduleChangedAt(Instant.parse("2027-03-05T04:59:59Z")).build();
        SchedulerJobConfigEntity exactly = job("exactly", ScheduleType.CRON).cronExpression("0 0 5 * * *")
                .scheduleChangedAt(Instant.parse("2027-03-05T05:00:00Z")).build();
        SchedulerJobConfigEntity after = job("after", ScheduleType.CRON).cronExpression("0 0 5 * * *")
                .scheduleChangedAt(Instant.parse("2027-03-05T05:30:00Z")).build();
        all.addAll(List.of(justBefore, exactly, after));
        rowsFor(List.of("never_changed", "just_before", "exactly", "after"), DAY_START, DAY_END);

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(statuses(jobOf(day, "never_changed"))).containsExactly(SchedulerDayService.DayFireStatus.MISSED);
        assertThat(statuses(jobOf(day, "just_before"))).containsExactly(SchedulerDayService.DayFireStatus.MISSED);
        assertThat(jobOf(day, "exactly").fires()).isEmpty();
        assertThat(jobOf(day, "after").fires()).isEmpty();
    }

    @Test
    @DisplayName("an admin Run now never satisfies a slot: the slot is MISSED and the manual fire is listed")
    void missed_manualFireDoesNotSatisfy() {
        cron("a_job", "0 0 6 * * *");
        rowsFor(List.of("a_job"), DAY_START, DAY_END,
                row("a_job", Instant.parse("2027-03-05T06:00:30Z"), SchedulerFireStatus.SUCCEEDED,
                        SchedulerFireTrigger.MANUAL));

        SchedulerDayService.DayJob job = jobOf(service(NOON).day(null), "a_job");

        assertThat(job.fires()).extracting(SchedulerDayService.Fire::status, SchedulerDayService.Fire::trigger)
                .containsExactly(
                        tuple(SchedulerDayService.DayFireStatus.MISSED, null),
                        tuple(SchedulerDayService.DayFireStatus.SUCCEEDED, "MANUAL"));
    }

    @Test
    @DisplayName("a catch-up fire within the tolerance satisfies the slot it caught up for; one outside is its "
            + "own fire")
    void missed_catchUp() {
        cron("caught_up", "0 0 7 * * *");
        cron("late_catch_up", "0 0 9 * * *");
        rowsFor(List.of("caught_up", "late_catch_up"), DAY_START, DAY_END,
                row("caught_up", Instant.parse("2027-03-05T07:01:00Z"), SchedulerFireStatus.SUCCEEDED,
                        SchedulerFireTrigger.CATCH_UP),
                row("late_catch_up", Instant.parse("2027-03-05T09:10:00Z"), SchedulerFireStatus.SUCCEEDED,
                        SchedulerFireTrigger.CATCH_UP));

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(jobOf(day, "caught_up").fires()).extracting(SchedulerDayService.Fire::status,
                SchedulerDayService.Fire::trigger).containsExactly(
                tuple(SchedulerDayService.DayFireStatus.SUCCEEDED, "CATCH_UP"));
        assertThat(statuses(jobOf(day, "late_catch_up"))).containsExactly(SchedulerDayService.DayFireStatus.MISSED,
                SchedulerDayService.DayFireStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("a paused job lists its would-be fires as PAUSED (none MISSED, none SCHEDULED); an off-in-config "
            + "job lists nothing and is not 'not on this day'")
    void pausedJobs() {
        all.add(job("paused", ScheduleType.CRON).cronExpression("0 0 2,18 * * *")
                .status(SchedulerJobStatus.PAUSED).build());
        all.add(job("off", ScheduleType.CRON).cronExpression("0 0 2,18 * * *")
                .status(SchedulerJobStatus.DISABLED_BY_CONFIG).build());
        rowsFor(List.of("paused", "off"), DAY_START, DAY_END);

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(jobOf(day, "paused").fires()).extracting(SchedulerDayService.Fire::at,
                SchedulerDayService.Fire::status).containsExactly(
                tuple(Instant.parse("2027-03-05T02:00:00Z"), SchedulerDayService.DayFireStatus.PAUSED),
                tuple(Instant.parse("2027-03-05T18:00:00Z"), SchedulerDayService.DayFireStatus.PAUSED));
        assertThat(jobOf(day, "off").fires()).isEmpty();
        assertThat(day.notOnThisDay()).isEmpty();
    }

    @Test
    @DisplayName("a paused job's slot that a log row stands for shows that run, not a would-be fire")
    void pausedJob_loggedSlotIsTheRun() {
        all.add(job("paused", ScheduleType.CRON).cronExpression("0 0 2,18 * * *")
                .status(SchedulerJobStatus.PAUSED).build());
        rowsFor(List.of("paused"), DAY_START, DAY_END, row("paused", Instant.parse("2027-03-05T02:00:00Z"),
                SchedulerFireStatus.SUCCEEDED, SchedulerFireTrigger.SCHEDULED));

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(statuses(jobOf(day, "paused"))).containsExactly(SchedulerDayService.DayFireStatus.SUCCEEDED,
                SchedulerDayService.DayFireStatus.PAUSED);
    }

    @Test
    @DisplayName("a job paused at 10:00 does not label its 06:00 slot PAUSED, only the slots after the pause")
    void pausedJob_slotsBeforeThePauseAreNotPaused() {
        all.add(job("paused", ScheduleType.CRON).cronExpression("0 0 6,18 * * *")
                .status(SchedulerJobStatus.PAUSED).scheduleChangedAt(Instant.parse("2027-03-05T10:00:00Z")).build());
        rowsFor(List.of("paused"), DAY_START, DAY_END);

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(jobOf(day, "paused").fires()).extracting(SchedulerDayService.Fire::at,
                SchedulerDayService.Fire::status).containsExactly(
                tuple(Instant.parse("2027-03-05T18:00:00Z"), SchedulerDayService.DayFireStatus.PAUSED));
    }

    @Test
    @DisplayName("a future day lists every slot as SCHEDULED; a past day lists every unlogged slot as MISSED")
    void futureAndPastDays() {
        cron("a_job", "0 0 2,18 * * *");
        Instant tomorrowStart = DAY_END;
        Instant tomorrowEnd = DAY_END.plus(Duration.ofDays(1));
        rowsFor(List.of("a_job"), tomorrowStart, tomorrowEnd);
        rowsFor(List.of("a_job"), DAY_START.minus(Duration.ofDays(1)), DAY_START,
                row("a_job", Instant.parse("2027-03-04T18:00:03Z"), SchedulerFireStatus.FAILED,
                        SchedulerFireTrigger.SCHEDULED));
        SchedulerDayService service = service(NOON);

        SchedulerDayService.DayJob tomorrow = jobOf(service.day(DAY.plusDays(1)), "a_job");
        SchedulerDayService.DayJob yesterday = jobOf(service.day(DAY.minusDays(1)), "a_job");

        assertThat(tomorrow.fires()).extracting(SchedulerDayService.Fire::at, SchedulerDayService.Fire::status)
                .containsExactly(
                        tuple(Instant.parse("2027-03-06T02:00:00Z"),
                                SchedulerDayService.DayFireStatus.SCHEDULED),
                        tuple(Instant.parse("2027-03-06T18:00:00Z"),
                                SchedulerDayService.DayFireStatus.SCHEDULED));
        assertThat(yesterday.fires()).extracting(SchedulerDayService.Fire::at, SchedulerDayService.Fire::status)
                .containsExactly(
                        tuple(Instant.parse("2027-03-04T02:00:00Z"),
                                SchedulerDayService.DayFireStatus.MISSED),
                        tuple(Instant.parse("2027-03-04T18:00:03Z"),
                                SchedulerDayService.DayFireStatus.FAILED));
    }

    // -------------------------------------------------------------------------
    // Pipeline-linked cycle fires
    // -------------------------------------------------------------------------

    private SchedulerFireLogEntity linkedRow(String key) {
        SchedulerFireLogEntity row = row(key, Instant.parse("2027-03-05T01:00:02Z"), SchedulerFireStatus.SUCCEEDED,
                SchedulerFireTrigger.SCHEDULED);
        row.setPipelineRunId(RUN_ID);
        return row;
    }

    private PipelineRunEntity run(PipelineRunStatus status, Instant completedAt, String reason) {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, Instant.parse("2027-03-05T01:00:02Z"));
        run.setId(RUN_ID);
        run.setStatus(status);
        run.setCompletedAt(completedAt);
        run.setFailureReason(reason);
        return run;
    }

    private SchedulerDayService.Fire linkedFire(PipelineRunEntity run) {
        cron(ForecastCycleJobs.NIGHTLY_JOB, "0 0 1 * * *");
        rowsFor(List.of(ForecastCycleJobs.NIGHTLY_JOB), DAY_START, DAY_END, linkedRow(ForecastCycleJobs.NIGHTLY_JOB));
        when(pipelineRuns.findAllById(List.of(RUN_ID))).thenReturn(run == null ? List.of() : List.of(run));
        return jobOf(service(NOON).day(null), ForecastCycleJobs.NIGHTLY_JOB).fires().get(0);
    }

    @Test
    @DisplayName("a linked fire takes its end time and status from the pipeline run: COMPLETED is SUCCEEDED")
    void pipeline_completed() {
        SchedulerDayService.Fire fire = linkedFire(run(PipelineRunStatus.COMPLETED,
                Instant.parse("2027-03-05T03:10:00Z"), "ignored when completed"));

        assertThat(fire.status()).isEqualTo(SchedulerDayService.DayFireStatus.SUCCEEDED);
        assertThat(fire.completedAt()).isEqualTo(Instant.parse("2027-03-05T03:10:00Z"));
        assertThat(fire.startedAt()).isEqualTo(Instant.parse("2027-03-05T01:00:02Z"));
        assertThat(fire.summary()).isEqualTo("summary of SUCCEEDED");
    }

    @Test
    @DisplayName("a linked fire whose run is still RUNNING is RUNNING with no end, whatever the fire thread said")
    void pipeline_running() {
        SchedulerDayService.Fire fire = linkedFire(run(PipelineRunStatus.RUNNING, null, null));

        assertThat(fire.status()).isEqualTo(SchedulerDayService.DayFireStatus.RUNNING);
        assertThat(fire.completedAt()).isNull();
    }

    @Test
    @DisplayName("a DEGRADED run is PARTIAL and its failure reason becomes the summary")
    void pipeline_degraded() {
        SchedulerDayService.Fire fire = linkedFire(run(PipelineRunStatus.DEGRADED,
                Instant.parse("2027-03-05T03:20:00Z"), "2 of 3 batch submissions failed"));

        assertThat(fire.status()).isEqualTo(SchedulerDayService.DayFireStatus.PARTIAL);
        assertThat(fire.summary()).isEqualTo("2 of 3 batch submissions failed");
    }

    @Test
    @DisplayName("a FAILED run is FAILED and its failure reason is cut to the summary length")
    void pipeline_failedWithLongReason() {
        String reason = "Safety timeout: " + "x".repeat(300);

        SchedulerDayService.Fire fire = linkedFire(run(PipelineRunStatus.FAILED,
                Instant.parse("2027-03-05T05:00:00Z"), reason));

        assertThat(fire.status()).isEqualTo(SchedulerDayService.DayFireStatus.FAILED);
        assertThat(fire.summary()).isEqualTo(reason.substring(0, DynamicSchedulerService.MAX_SUMMARY_LENGTH));
    }

    @Test
    @DisplayName("a FAILED run with no reason keeps the fire's own summary")
    void pipeline_failedWithoutReason() {
        SchedulerDayService.Fire fire = linkedFire(run(PipelineRunStatus.FAILED,
                Instant.parse("2027-03-05T05:00:00Z"), " "));

        assertThat(fire.status()).isEqualTo(SchedulerDayService.DayFireStatus.FAILED);
        assertThat(fire.summary()).isEqualTo("summary of SUCCEEDED");
    }

    @Test
    @DisplayName("a linked fire whose run row has gone falls back to the fire's own status and end")
    void pipeline_runMissing() {
        SchedulerDayService.Fire fire = linkedFire(null);

        assertThat(fire.status()).isEqualTo(SchedulerDayService.DayFireStatus.SUCCEEDED);
        assertThat(fire.completedAt()).isEqualTo(Instant.parse("2027-03-05T01:00:06Z"));
    }

    // -------------------------------------------------------------------------
    // Solar jobs and the fallback flag
    // -------------------------------------------------------------------------

    private void solarJob(String cronFallback) {
        all.add(job(ForecastCycleJobs.NIGHTLY_JOB, ScheduleType.SOLAR_OFFSET).solarEvent(SolarEvent.SUNRISE)
                .solarOffsetMinutes(255).cronExpression(cronFallback).build());
    }

    @Test
    @DisplayName("a usable solar day: the fire is the trigger's own, SCHEDULED before it, and not a fallback")
    void solar_futureFire() {
        solarJob("0 0 3 * * *");
        rowsFor(List.of(ForecastCycleJobs.NIGHTLY_JOB), DAY_START, DAY_END);
        Instant expected = solar.create(ForecastCycleJobs.NIGHTLY_JOB, SolarEvent.SUNRISE, 255, "0 0 3 * * *")
                .fireFor(DAY).fire();

        SchedulerDayService.DayJob job = jobOf(service(DAY_START.plusSeconds(60)).day(null),
                ForecastCycleJobs.NIGHTLY_JOB);

        assertThat(job.fires()).containsExactly(new SchedulerDayService.Fire(expected,
                SchedulerDayService.DayFireStatus.SCHEDULED, null, null, null, null, false, false));
    }

    @Test
    @DisplayName("a day the solar calculation fails uses the stored cron: its slot and its logged fire carry "
            + "fallback = true")
    void solar_fallbackDay() {
        SolarTriggerFactory brokenToday = new SolarTriggerFactory(new SolarService() {
            @Override
            public LocalDateTime sunriseUtc(double lat, double lon, LocalDate date) {
                if (date.equals(DAY)) {
                    throw new IllegalStateException("no sun today");
                }
                return super.sunriseUtc(lat, lon, date);
            }
        }, new SolarReferencePoint("54.776", "-1.575"));
        solarJob("0 0 3 * * *");
        rowsFor(List.of(ForecastCycleJobs.NIGHTLY_JOB), DAY_START, DAY_END,
                row(ForecastCycleJobs.NIGHTLY_JOB, Instant.parse("2027-03-05T03:00:02Z"),
                        SchedulerFireStatus.SUCCEEDED, SchedulerFireTrigger.SCHEDULED));

        SchedulerDayService.DayJob job = jobOf(service(NOON, brokenToday).day(null), ForecastCycleJobs.NIGHTLY_JOB);

        assertThat(job.fires()).extracting(SchedulerDayService.Fire::at, SchedulerDayService.Fire::status,
                SchedulerDayService.Fire::fallback).containsExactly(tuple(
                        Instant.parse("2027-03-05T03:00:02Z"), SchedulerDayService.DayFireStatus.SUCCEEDED, true));
    }

    @Test
    @DisplayName("a fallback slot with no log row is a MISSED fire flagged fallback")
    void solar_fallbackDayMissed() {
        SolarTriggerFactory brokenToday = new SolarTriggerFactory(new SolarService() {
            @Override
            public LocalDateTime sunriseUtc(double lat, double lon, LocalDate date) {
                throw new IllegalStateException("no sun");
            }
        }, new SolarReferencePoint("54.776", "-1.575"));
        solarJob("0 0 3 * * *");
        rowsFor(List.of(ForecastCycleJobs.NIGHTLY_JOB), DAY_START, DAY_END);

        SchedulerDayService.DayJob job = jobOf(service(NOON, brokenToday).day(null), ForecastCycleJobs.NIGHTLY_JOB);

        assertThat(job.fires()).containsExactly(new SchedulerDayService.Fire(Instant.parse("2027-03-05T03:00:00Z"),
                SchedulerDayService.DayFireStatus.MISSED, null, null, null, null, true, false));
    }

    // -------------------------------------------------------------------------
    // Repeating jobs
    // -------------------------------------------------------------------------

    private SchedulerJobConfigEntity delayJob(String key, Long delayMs) {
        SchedulerJobConfigEntity config = job(key, ScheduleType.FIXED_DELAY).fixedDelayMs(delayMs).build();
        all.add(config);
        return config;
    }

    private void problemsFor(List<String> keys, Instant from, Instant to, SchedulerFireLogEntity... rows) {
        when(fireLog.findByJobKeyInAndStatusInAndFiredAtGreaterThanEqualAndFiredAtLessThanOrderByFiredAtAsc(keys,
                PROBLEMS, from, to)).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("a fixed-delay job is REPEATING: expected count is elapsed over delay, counts by status "
            + "(every status present), only FAILED and PARTIAL listed")
    void repeating_fixedDelay() {
        delayJob("batch_result_polling", 600_000L);
        problemsFor(List.of("batch_result_polling"), DAY_START, DAY_END,
                row("batch_result_polling", Instant.parse("2027-03-05T04:00:00Z"), SchedulerFireStatus.FAILED,
                        SchedulerFireTrigger.SCHEDULED),
                row("batch_result_polling", Instant.parse("2027-03-05T09:00:00Z"), SchedulerFireStatus.PARTIAL,
                        SchedulerFireTrigger.SCHEDULED));
        when(fireLog.countByJobAndStatus(DAY_START, DAY_END)).thenReturn(List.of(
                new Count("batch_result_polling", SchedulerFireStatus.SUCCEEDED, 68),
                new Count("batch_result_polling", SchedulerFireStatus.FAILED, 1),
                new Count("batch_result_polling", SchedulerFireStatus.PARTIAL, 1),
                new Count("batch_result_polling", SchedulerFireStatus.SKIPPED, 2)));

        SchedulerDayService.Day day = service(NOON).day(null);
        SchedulerDayService.DayJob job = jobOf(day, "batch_result_polling");

        assertThat(job.kind()).isEqualTo(SchedulerDayService.Kind.REPEATING);
        assertThat(job.fires()).isNull();
        assertThat(job.schedule()).isEqualTo(new SchedulerDayService.RepeatingSchedule("FIXED_DELAY", null,
                600_000L, "UTC"));
        assertThat(job.expectedCount()).isEqualTo(72L);
        assertThat(job.counts()).containsExactly(Map.entry("RUNNING", 0L), Map.entry("SUCCEEDED", 68L),
                Map.entry("PARTIAL", 1L), Map.entry("FAILED", 1L), Map.entry("SKIPPED", 2L));
        assertThat(job.problems()).containsExactly(
                new SchedulerDayService.Problem(SchedulerDayService.DayFireStatus.FAILED,
                        Instant.parse("2027-03-05T04:00:00Z"), "summary of FAILED"),
                new SchedulerDayService.Problem(SchedulerDayService.DayFireStatus.PARTIAL,
                        Instant.parse("2027-03-05T09:00:00Z"), "summary of PARTIAL"));
        assertThat(job.problemsOmitted()).isZero();
        assertThat(day.repeatingTally()).isEqualTo(new SchedulerDayService.RepeatingTally(1, 1));
    }

    @Test
    @DisplayName("an every-five-minutes cron is REPEATING and its expected count steps the cron to now, inclusive")
    void repeating_cronToday() {
        cron("aurora_polling", "0 */5 * * * *");
        problemsFor(List.of("aurora_polling"), DAY_START, DAY_END);

        SchedulerDayService.DayJob job = jobOf(service(NOON).day(null), "aurora_polling");

        assertThat(job.kind()).isEqualTo(SchedulerDayService.Kind.REPEATING);
        assertThat(job.schedule()).isEqualTo(new SchedulerDayService.RepeatingSchedule("CRON", "0 */5 * * * *",
                null, "UTC"));
        assertThat(job.expectedCount()).isEqualTo(145L);
        assertThat(job.counts().values()).containsOnly(0L);
    }

    @Test
    @DisplayName("a cron of seven fires a day is REPEATING; six is a scheduled job")
    void repeating_cronThreshold() {
        cron("seven", "0 0 1-7 * * *");
        cron("six", "0 0 1-6 * * *");
        problemsFor(List.of("seven"), DAY_START, DAY_END);
        rowsFor(List.of("six"), DAY_START, DAY_END);

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(jobOf(day, "seven").kind()).isEqualTo(SchedulerDayService.Kind.REPEATING);
        assertThat(jobOf(day, "six").kind()).isEqualTo(SchedulerDayService.Kind.SCHEDULED);
    }

    @Test
    @DisplayName("an every-five-minutes cron counts 276 fires on the 23 hour day and 300 on the 25 hour day, "
            + "in UK time and in UTC alike")
    void repeating_cronOnClockChangeDays() {
        SchedulerJobConfigEntity london = job("london_poll", ScheduleType.CRON).cronExpression("0 */5 * * * *")
                .timeZone("Europe/London").build();
        all.add(london);
        all.add(job("utc_poll", ScheduleType.CRON).cronExpression("0 */5 * * * *").build());
        Instant springStart = Instant.parse("2027-03-28T00:00:00Z");
        Instant springEnd = Instant.parse("2027-03-28T23:00:00Z");
        Instant fallStart = Instant.parse("2027-10-30T23:00:00Z");
        Instant fallEnd = Instant.parse("2027-11-01T00:00:00Z");
        List<String> keys = List.of("london_poll", "utc_poll");
        problemsFor(keys, springStart, springEnd);
        problemsFor(keys, fallStart, fallEnd);

        SchedulerDayService.Day spring = service(Instant.parse("2027-03-30T12:00:00Z"))
                .day(LocalDate.of(2027, 3, 28));
        SchedulerDayService.Day fall = service(Instant.parse("2027-11-02T12:00:00Z"))
                .day(LocalDate.of(2027, 10, 31));

        assertThat(jobOf(spring, "london_poll").expectedCount()).isEqualTo(276L);
        assertThat(jobOf(spring, "utc_poll").expectedCount()).isEqualTo(276L);
        assertThat(jobOf(fall, "london_poll").expectedCount()).isEqualTo(300L);
        assertThat(jobOf(fall, "utc_poll").expectedCount()).isEqualTo(300L);
    }

    @Test
    @DisplayName("a fixed-delay job's expected count on a past clock-change day follows the day's length")
    void repeating_fixedDelayOnClockChangeDays() {
        delayJob("poller", 600_000L);
        problemsFor(List.of("poller"), Instant.parse("2027-03-28T00:00:00Z"), Instant.parse("2027-03-28T23:00:00Z"));
        problemsFor(List.of("poller"), Instant.parse("2027-10-30T23:00:00Z"), Instant.parse("2027-11-01T00:00:00Z"));

        assertThat(jobOf(service(Instant.parse("2027-03-30T12:00:00Z")).day(LocalDate.of(2027, 3, 28)), "poller")
                .expectedCount()).isEqualTo(138L);
        assertThat(jobOf(service(Instant.parse("2027-11-02T12:00:00Z")).day(LocalDate.of(2027, 10, 31)), "poller")
                .expectedCount()).isEqualTo(150L);
    }

    @Test
    @DisplayName("expected count: zero before the day starts, zero for a paused job, zero for a fixed delay with no "
            + "positive delay; counted from schedule_changed_at when that is later than the day's start")
    void repeating_expectedCountEdges() {
        delayJob("future", 600_000L);
        all.add(job("paused", ScheduleType.FIXED_DELAY).fixedDelayMs(600_000L).status(SchedulerJobStatus.PAUSED)
                .build());
        delayJob("no_delay", null);
        delayJob("zero_delay", 0L);
        all.add(job("changed", ScheduleType.FIXED_DELAY).fixedDelayMs(600_000L)
                .scheduleChangedAt(Instant.parse("2027-03-05T10:00:00Z")).build());
        all.add(job("changed_long_ago", ScheduleType.FIXED_DELAY).fixedDelayMs(600_000L)
                .scheduleChangedAt(Instant.parse("2027-03-01T10:00:00Z")).build());
        List<String> keys = List.of("future", "paused", "no_delay", "zero_delay", "changed", "changed_long_ago");
        problemsFor(keys, DAY_START, DAY_END);
        problemsFor(keys, DAY_END, DAY_END.plus(Duration.ofDays(1)));
        SchedulerDayService service = service(NOON);

        SchedulerDayService.Day today = service.day(null);
        SchedulerDayService.Day tomorrow = service.day(DAY.plusDays(1));

        assertThat(jobOf(tomorrow, "future").expectedCount()).isZero();
        assertThat(jobOf(today, "paused").expectedCount()).isZero();
        assertThat(jobOf(today, "paused").kind()).isEqualTo(SchedulerDayService.Kind.REPEATING);
        assertThat(jobOf(today, "no_delay").expectedCount()).isZero();
        assertThat(jobOf(today, "zero_delay").expectedCount()).isZero();
        assertThat(jobOf(today, "changed").expectedCount()).isEqualTo(12L);
        assertThat(jobOf(today, "changed_long_ago").expectedCount()).isEqualTo(72L);
    }

    @Test
    @DisplayName("only the newest 50 problems are listed; the rest are counted as omitted")
    void repeating_problemsCapped() {
        delayJob("noisy", 60_000L);
        List<SchedulerFireLogEntity> failures = new ArrayList<>();
        for (int i = 0; i < SchedulerDayService.MAX_PROBLEMS + 2; i++) {
            failures.add(row("noisy", DAY_START.plusSeconds(60L * i), SchedulerFireStatus.FAILED,
                    SchedulerFireTrigger.SCHEDULED));
        }
        problemsFor(List.of("noisy"), DAY_START, DAY_END, failures.toArray(new SchedulerFireLogEntity[0]));
        when(fireLog.countByJobAndStatus(DAY_START, DAY_END)).thenReturn(List.of(
                new Count("noisy", SchedulerFireStatus.FAILED, SchedulerDayService.MAX_PROBLEMS + 2)));

        SchedulerDayService.DayJob job = jobOf(service(NOON).day(null), "noisy");

        assertThat(job.problems()).hasSize(SchedulerDayService.MAX_PROBLEMS);
        assertThat(job.problems().get(0).at()).isEqualTo(DAY_START.plusSeconds(120));
        assertThat(job.problemsOmitted()).isEqualTo(2);
    }

    @Test
    @DisplayName("the tally counts FAILED fires of repeating jobs only, and the jobs that had any")
    void repeatingTally_acrossJobs() {
        delayJob("poll_a", 600_000L);
        delayJob("poll_b", 600_000L);
        delayJob("poll_c", 600_000L);
        cron("scheduled_failing", "0 0 1 * * *");
        problemsFor(List.of("poll_a", "poll_b", "poll_c"), DAY_START, DAY_END);
        rowsFor(List.of("scheduled_failing"), DAY_START, DAY_END);
        when(fireLog.countByJobAndStatus(DAY_START, DAY_END)).thenReturn(List.of(
                new Count("poll_a", SchedulerFireStatus.FAILED, 3),
                new Count("poll_b", SchedulerFireStatus.FAILED, 2),
                new Count("poll_b", SchedulerFireStatus.PARTIAL, 9),
                new Count("poll_c", SchedulerFireStatus.SUCCEEDED, 50),
                new Count("scheduled_failing", SchedulerFireStatus.FAILED, 4)));

        assertThat(service(NOON).day(null).repeatingTally())
                .isEqualTo(new SchedulerDayService.RepeatingTally(5, 2));
    }

    // -------------------------------------------------------------------------
    // Not on this day
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an active weekly job with no fire on the day is listed with its next fire; on its day it is not")
    void notOnThisDay_weeklyJob() {
        cron("tide_refresh", "0 0 9 * * MON");
        LocalDate monday = LocalDate.of(2027, 3, 8);
        rowsFor(List.of("tide_refresh"), DAY_START, DAY_END);
        rowsFor(List.of("tide_refresh"), Instant.parse("2027-03-08T00:00:00Z"), Instant.parse("2027-03-09T00:00:00Z"));
        SchedulerDayService service = service(NOON);

        SchedulerDayService.Day friday = service.day(null);
        SchedulerDayService.Day onMonday = service.day(monday);

        assertThat(friday.notOnThisDay()).containsExactly(new SchedulerDayService.NotOnThisDay("tide_refresh",
                "Name of tide_refresh", Instant.parse("2027-03-08T09:00:00Z")));
        assertThat(friday.jobs().get(0).fires()).isEmpty();
        assertThat(onMonday.notOnThisDay()).isEmpty();
        assertThat(onMonday.jobs().get(0).fires()).extracting(SchedulerDayService.Fire::status)
                .containsExactly(SchedulerDayService.DayFireStatus.SCHEDULED);
    }

    @Test
    @DisplayName("a job whose schedule cannot be read (unparseable cron, unrecognised type) has no fires and is not "
            + "listed as firing on other days")
    void notOnThisDay_unreadableSchedules() {
        cron("bad_cron", "not a cron");
        all.add(SchedulerJobConfigEntity.builder().id(9L).jobKey("future_job").displayName("Future")
                .scheduleTypeRaw("LUNAR_PHASE").status(SchedulerJobStatus.ACTIVE).updatedAt(NOON).build());
        rowsFor(List.of("bad_cron", "future_job"), DAY_START, DAY_END);

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(jobOf(day, "bad_cron").fires()).isEmpty();
        assertThat(jobOf(day, "future_job").fires()).isEmpty();
        assertThat(day.notOnThisDay()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Repeating ticks
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an hourly-ish repeating cron lists every slot of the day as ticks, in order")
    void repeating_ticksListed() {
        cron("hourly_poll", "0 0 * * * *");
        problemsFor(List.of("hourly_poll"), DAY_START, DAY_END);

        List<Instant> ticks = jobOf(service(NOON).day(null), "hourly_poll").ticks();

        assertThat(ticks).hasSize(24);
        assertThat(ticks.get(0)).isEqualTo(Instant.parse("2027-03-05T00:00:00Z"));
        assertThat(ticks.get(23)).isEqualTo(Instant.parse("2027-03-05T23:00:00Z"));
    }

    @Test
    @DisplayName("a cron too busy to list (every minute) has null ticks, so the client draws a band; a scheduled "
            + "job has none")
    void repeating_ticksNullWhenBusy() {
        cron("every_minute", "0 * * * * *");
        cron("scheduled_job", "0 0 2 * * *");
        problemsFor(List.of("every_minute"), DAY_START, DAY_END);
        rowsFor(List.of("scheduled_job"), DAY_START, DAY_END);

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(jobOf(day, "every_minute").ticks()).isNull();
        assertThat(jobOf(day, "scheduled_job").ticks()).isNull();
    }

    @Test
    @DisplayName("a fixed-delay job has null ticks")
    void repeating_ticksNullForFixedDelay() {
        delayJob("poller", 600_000L);
        problemsFor(List.of("poller"), DAY_START, DAY_END);

        assertThat(jobOf(service(NOON).day(null), "poller").ticks()).isNull();
    }

    @Test
    @DisplayName("a paused repeating cron stays REPEATING with expectedCount 0 and still lists its ticks; one off in "
            + "config is not served at all")
    void repeating_pausedAndOff() {
        all.add(job("paused_poll", ScheduleType.CRON).cronExpression("0 0 * * * *")
                .status(SchedulerJobStatus.PAUSED).build());
        all.add(job("off_poll", ScheduleType.CRON).cronExpression("0 0 * * * *")
                .status(SchedulerJobStatus.DISABLED_BY_CONFIG).build());
        problemsFor(List.of("paused_poll", "off_poll"), DAY_START, DAY_END);

        SchedulerDayService.Day day = service(NOON).day(null);

        assertThat(jobOf(day, "paused_poll").kind()).isEqualTo(SchedulerDayService.Kind.REPEATING);
        assertThat(jobOf(day, "paused_poll").expectedCount()).isZero();
        assertThat(jobOf(day, "paused_poll").ticks()).hasSize(24);
        assertThat(day.jobs()).extracting(SchedulerDayService.DayJob::jobKey).doesNotContain("off_poll");
    }

    @Test
    @DisplayName("the ticks follow the UK clock-change days: 23 slots on the spring day, 25 on the autumn day")
    void repeating_ticksOnClockChangeDays() {
        all.add(job("london_hourly", ScheduleType.CRON).cronExpression("0 0 * * * *").timeZone("Europe/London")
                .build());
        problemsFor(List.of("london_hourly"), Instant.parse("2027-03-28T00:00:00Z"),
                Instant.parse("2027-03-28T23:00:00Z"));
        problemsFor(List.of("london_hourly"), Instant.parse("2027-10-30T23:00:00Z"),
                Instant.parse("2027-11-01T00:00:00Z"));

        assertThat(jobOf(service(Instant.parse("2027-03-30T12:00:00Z")).day(LocalDate.of(2027, 3, 28)),
                "london_hourly").ticks()).hasSize(23);
        assertThat(jobOf(service(Instant.parse("2027-11-02T12:00:00Z")).day(LocalDate.of(2027, 10, 31)),
                "london_hourly").ticks()).hasSize(25);
    }

    // -------------------------------------------------------------------------
    // The evening refresh's served deadline
    // -------------------------------------------------------------------------

    private static final Instant EVENING_FIRE = Instant.parse("2027-03-05T21:00:02Z");

    private PipelineRunEntity eveningRun() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.EVENING, EVENING_FIRE);
        run.setId(RUN_ID);
        run.setStatus(PipelineRunStatus.COMPLETED);
        run.setCompletedAt(Instant.parse("2027-03-05T21:50:00Z"));
        return run;
    }

    private SchedulerFireLogEntity eveningRow(SchedulerFireStatus status, boolean linked) {
        SchedulerFireLogEntity row = row(ForecastCycleJobs.EVENING_JOB, EVENING_FIRE, status,
                SchedulerFireTrigger.SCHEDULED);
        if (linked) {
            row.setPipelineRunId(RUN_ID);
        }
        return row;
    }

    private ForecastBatchEntity forecastBatch(String submittedAt) {
        ForecastBatchEntity batch = new ForecastBatchEntity("msgbatch_e", ForecastBatchEntity.BatchType.FORECAST, 3,
                Instant.parse("2027-03-05T20:00:00Z"));
        batch.setSubmittedAt(Instant.parse(submittedAt));
        return batch;
    }

    private ForecastCycleWatchdog.Deadlines eveningWindow(SchedulerDayService.Day day) {
        return day.watchdog().stream().filter(w -> w.jobKey().equals(ForecastCycleJobs.EVENING_JOB)).findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("the evening deadline is served from the linked run's first forecast batch, not fire time plus budget")
    void evening_deadlineFromTheRunsFirstBatch() {
        cron(ForecastCycleJobs.EVENING_JOB, "0 0 21 * * *");
        rowsFor(List.of(ForecastCycleJobs.EVENING_JOB), DAY_START, DAY_END, eveningRow(SchedulerFireStatus.SUCCEEDED,
                true));
        when(pipelineRuns.findAllById(List.of(RUN_ID))).thenReturn(List.of(eveningRun()));
        when(batchRepo.findByPipelineRunId(RUN_ID)).thenReturn(List.of(forecastBatch("2027-03-05T21:25:00Z")));

        SchedulerDayService.Day day = service(Instant.parse("2027-03-05T23:30:00Z")).day(null);

        // 21:25 plus the 90 minute budget, not 21:00:02 plus 90 minutes.
        assertThat(eveningWindow(day).deadlineAt()).isEqualTo(Instant.parse("2027-03-05T22:55:00Z"));
        assertThat(eveningWindow(day).budgetMinutes()).isEqualTo(90L);
        assertThat(jobOf(day, ForecastCycleJobs.EVENING_JOB).fires().get(0).runLinked()).isTrue();
    }

    @Test
    @DisplayName("with no batch the evening deadline falls back to the run's trigger time plus the budget")
    void evening_deadlineFallsBackToTrigger() {
        cron(ForecastCycleJobs.EVENING_JOB, "0 0 21 * * *");
        rowsFor(List.of(ForecastCycleJobs.EVENING_JOB), DAY_START, DAY_END, eveningRow(SchedulerFireStatus.SUCCEEDED,
                true));
        when(pipelineRuns.findAllById(List.of(RUN_ID))).thenReturn(List.of(eveningRun()));
        when(batchRepo.findByPipelineRunId(RUN_ID)).thenReturn(List.of());

        SchedulerDayService.Day day = service(Instant.parse("2027-03-05T23:30:00Z")).day(null);

        assertThat(eveningWindow(day).deadlineAt()).isEqualTo(Instant.parse("2027-03-05T22:30:02Z"));
    }

    @Test
    @DisplayName("the evening deadline is null with no linked run, and from a SKIPPED fire's run")
    void evening_deadlineNullWithoutARun() {
        cron(ForecastCycleJobs.EVENING_JOB, "0 0 21 * * *");
        rowsFor(List.of(ForecastCycleJobs.EVENING_JOB), DAY_START, DAY_END,
                eveningRow(SchedulerFireStatus.FAILED, false));

        SchedulerDayService.Day unlinked = service(Instant.parse("2027-03-05T23:30:00Z")).day(null);
        assertThat(eveningWindow(unlinked).deadlineAt()).isNull();
        assertThat(jobOf(unlinked, ForecastCycleJobs.EVENING_JOB).fires().get(0).runLinked()).isFalse();

        rowsFor(List.of(ForecastCycleJobs.EVENING_JOB), DAY_START, DAY_END,
                eveningRow(SchedulerFireStatus.SKIPPED, true));
        when(pipelineRuns.findAllById(List.of(RUN_ID))).thenReturn(List.of(eveningRun()));
        assertThat(eveningWindow(service(Instant.parse("2027-03-05T23:30:00Z")).day(null)).deadlineAt()).isNull();
    }

    // -------------------------------------------------------------------------
    // Watchdog windows
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the watchdog windows are the three cycle jobs', whatever their schedule type")
    void watchdogWindows() {
        SchedulerDayService.Day day = service(NOON).day(null);

        Instant sunrise = solar.eventInstant(SolarEvent.SUNRISE, DAY);
        Instant sunset = solar.eventInstant(SolarEvent.SUNSET, DAY);
        assertThat(day.watchdog()).containsExactly(
                new ForecastCycleWatchdog.Deadlines(ForecastCycleJobs.NIGHTLY_JOB, SolarEvent.SUNRISE, sunrise,
                        sunrise.minus(Duration.ofMinutes(20)), sunrise.plus(Duration.ofMinutes(60)), 30, null, null),
                new ForecastCycleWatchdog.Deadlines(ForecastCycleJobs.INTRADAY_JOB, SolarEvent.SUNSET, sunset,
                        sunset.minus(Duration.ofMinutes(20)), sunset.plus(Duration.ofMinutes(60)), 30, null, null),
                new ForecastCycleWatchdog.Deadlines(ForecastCycleJobs.EVENING_JOB, null, null, null, null, 30,
                        90L, null));
    }
}
