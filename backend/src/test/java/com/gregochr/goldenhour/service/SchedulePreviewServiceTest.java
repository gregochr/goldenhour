package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@link SchedulePreviewService} against the real scheduler arithmetic (Friday 5 March 2027, 12:00 UTC): the
 * cron a list of times becomes, the validation messages saving would give, the next fires, the solar year range and
 * the neighbouring jobs.
 */
class SchedulePreviewServiceTest {

    private static final Instant NOW = Instant.parse("2027-03-05T12:00:00Z");
    private static final String JOB = "tide_refresh";

    private SchedulerJobConfigRepository configs;
    private SchedulePreviewService service;
    private final List<SchedulerJobConfigEntity> all = new ArrayList<>();

    @BeforeEach
    void setUp() {
        configs = mock(SchedulerJobConfigRepository.class);
        build(new SolarService());
        when(configs.findAllByOrderByIdAsc()).thenReturn(all);
        add(job(JOB, ScheduleType.CRON).cronExpression("0 0 2 * * MON").build());
    }

    private void build(SolarService calculator) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SolarTriggerFactory solar = new SolarTriggerFactory(calculator, new SolarReferencePoint("54.776", "-1.575"));
        DynamicSchedulerService scheduler = new DynamicSchedulerService(configs,
                mock(SchedulerFireLogRepository.class), mock(TaskScheduler.class),
                mock(AuroraProperties.class), solar, mock(SolarCatchUpPolicy.class), clock);
        SchedulerJobOverviewService overview = new SchedulerJobOverviewService(
                mock(SchedulerFireLogRepository.class), scheduler, clock);
        SolarSchedulePreviewService solarPreview = new SolarSchedulePreviewService(configs,
                mock(PipelineRunRepository.class), solar, clock);
        service = new SchedulePreviewService(configs, scheduler, overview, solarPreview, clock);
    }

    private static SchedulerJobConfigEntity.SchedulerJobConfigEntityBuilder job(String key, ScheduleType type) {
        return SchedulerJobConfigEntity.builder().id((long) key.hashCode()).jobKey(key).displayName("Name of " + key)
                .scheduleType(type).status(SchedulerJobStatus.ACTIVE).updatedAt(NOW);
    }

    private void add(SchedulerJobConfigEntity config) {
        all.add(config);
        when(configs.findByJobKey(config.getJobKey())).thenReturn(Optional.of(config));
    }

    private static SchedulePreviewService.Proposal cron(List<String> times, List<String> days, String zone) {
        return new SchedulePreviewService.Proposal(ScheduleType.CRON, null, null, times, days, zone, null);
    }

    // -------------------------------------------------------------------------
    // CRON proposals
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("two times on Mondays in UK time become one cron, with the next three fires in that zone")
    void cron_timesAndDaysInLondon() {
        SchedulePreviewService.Preview preview = service.preview(JOB,
                cron(List.of("18:15", "06:15"), List.of("mon"), "Europe/London"));

        assertThat(preview.valid()).isTrue();
        assertThat(preview.error()).isNull();
        assertThat(preview.cronExpression()).isEqualTo("0 15 6,18 * * MON");
        assertThat(preview.timeZone()).isEqualTo("Europe/London");
        assertThat(preview.nextFires()).containsExactly(Instant.parse("2027-03-08T06:15:00Z"),
                Instant.parse("2027-03-08T18:15:00Z"), Instant.parse("2027-03-15T06:15:00Z"));
        assertThat(preview.yearRange()).isNull();
    }

    @Test
    @DisplayName("no days means every day; no zone means UTC; duplicate times collapse")
    void cron_defaults() {
        SchedulePreviewService.Preview preview = service.preview(JOB, cron(List.of("06:00", "06:00", "18:00"), null,
                null));

        assertThat(preview.cronExpression()).isEqualTo("0 0 6,18 * * *");
        assertThat(preview.timeZone()).isEqualTo("UTC");
        assertThat(preview.nextFires()).containsExactly(Instant.parse("2027-03-05T18:00:00Z"),
                Instant.parse("2027-03-06T06:00:00Z"), Instant.parse("2027-03-06T18:00:00Z"));
        assertThat(service.preview(JOB, cron(List.of("06:00"), List.of(), "UTC")).cronExpression())
                .isEqualTo("0 0 6 * * *");
    }

    @Test
    @DisplayName("times with different minutes cannot share one cron and say so")
    void cron_mixedMinutesRejected() {
        SchedulePreviewService.Preview preview = service.preview(JOB, cron(List.of("06:15", "18:45"), null, null));

        assertThat(preview.valid()).isFalse();
        assertThat(preview.error()).isEqualTo(
                "All times must share the same minutes past the hour (e.g. 06:15 and 18:15)");
        assertThat(preview.cronExpression()).isNull();
        assertThat(preview.nextFires()).isEmpty();
        assertThat(preview.neighbours()).isEmpty();
    }

    @Test
    @DisplayName("missing, malformed and out-of-range times, and bad days or zones, are invalid with a message")
    void cron_badInputs() {
        assertThat(service.preview(JOB, cron(null, null, null)).error()).isEqualTo("At least one time is required");
        assertThat(service.preview(JOB, cron(List.of(), null, null)).error())
                .isEqualTo("At least one time is required");
        assertThat(service.preview(JOB, cron(List.of("6:5"), null, null)).error())
                .isEqualTo("times must be HH:MM, got '6:5'");
        assertThat(service.preview(JOB, cron(List.of("24:00"), null, null)).error())
                .isEqualTo("times must be HH:MM, got '24:00'");
        assertThat(service.preview(JOB, cron(Arrays.asList((String) null), null, null)).error())
                .isEqualTo("times must be HH:MM, got ''");
        assertThat(service.preview(JOB, cron(List.of("06:00"), List.of("FUNDAY"), null)).error())
                .isEqualTo("daysOfWeek must be MON, TUE, WED, THU, FRI, SAT or SUN");
        assertThat(service.preview(JOB, cron(List.of("06:00"), Arrays.asList((String) null), null))
                .error()).isEqualTo("daysOfWeek must be MON, TUE, WED, THU, FRI, SAT or SUN");
        assertThat(service.preview(JOB, cron(List.of("06:00"), null, "Asia/Tokyo")).error())
                .isEqualTo("timeZone must be Europe/London or UTC, got 'Asia/Tokyo'");
    }

    // -------------------------------------------------------------------------
    // FIXED_DELAY and SOLAR_OFFSET proposals
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a fixed delay lists the next fire (from the last completion) and the delays after it, no neighbours")
    void fixedDelay_nextFires() {
        add(job("batch_result_polling", ScheduleType.FIXED_DELAY).fixedDelayMs(30_000L)
                .lastCompletionTime(NOW.minusSeconds(10)).build());

        SchedulePreviewService.Preview preview = service.preview("batch_result_polling",
                new SchedulePreviewService.Proposal(ScheduleType.FIXED_DELAY, null, null, null, null, null,
                        60_000L));

        assertThat(preview.valid()).isTrue();
        assertThat(preview.cronExpression()).isNull();
        assertThat(preview.timeZone()).isNull();
        assertThat(preview.nextFires()).containsExactly(NOW.plusSeconds(50), NOW.plusSeconds(110),
                NOW.plusSeconds(170));
        assertThat(preview.neighbours()).isEmpty();
    }

    @Test
    @DisplayName("a fixed delay under ten seconds is refused with updateSchedule's message")
    void fixedDelay_tooShort() {
        add(job("batch_result_polling", ScheduleType.FIXED_DELAY).fixedDelayMs(30_000L).build());

        SchedulePreviewService.Preview preview = service.preview("batch_result_polling",
                new SchedulePreviewService.Proposal(ScheduleType.FIXED_DELAY, null, null, null, null, null, 5_000L));

        assertThat(preview.valid()).isFalse();
        assertThat(preview.error()).isEqualTo("Fixed delay must be at least 10000ms, got: 5000");
    }

    @Test
    @DisplayName("a solar proposal for a forecast cycle gets its next fires and the year's earliest and latest time")
    void solar_yearRangeAndFires() {
        add(job(ForecastCycleJobs.NIGHTLY_JOB, ScheduleType.CRON).cronExpression("0 0 1 * * *").build());

        SchedulePreviewService.Preview preview = service.preview(ForecastCycleJobs.NIGHTLY_JOB,
                new SchedulePreviewService.Proposal(ScheduleType.SOLAR_OFFSET, SolarEvent.SUNRISE, 255, null, null,
                        null, null));

        assertThat(preview.valid()).isTrue();
        assertThat(preview.cronExpression()).isNull();
        assertThat(preview.timeZone()).isNull();
        assertThat(preview.yearRange()).isEqualTo(new SchedulePreviewService.YearRange("00:13", "04:15"));
        assertThat(preview.nextFires()).hasSize(3).isSorted();
        assertThat(preview.nextFires().get(0)).isAfter(NOW);
    }

    @Test
    @DisplayName("an out-of-range or incomplete solar proposal is refused with the switch endpoint's message")
    void solar_invalid() {
        add(job(ForecastCycleJobs.NIGHTLY_JOB, ScheduleType.CRON).cronExpression("0 0 1 * * *").build());

        assertThat(service.preview(ForecastCycleJobs.NIGHTLY_JOB, new SchedulePreviewService.Proposal(
                ScheduleType.SOLAR_OFFSET, SolarEvent.SUNRISE, 721, null, null, null, null)).error())
                .isEqualTo("solar offset must be 0..720 minutes, got 721");
        assertThat(service.preview(ForecastCycleJobs.NIGHTLY_JOB, new SchedulePreviewService.Proposal(
                ScheduleType.SOLAR_OFFSET, null, 240, null, null, null, null)).error())
                .isEqualTo("SOLAR_OFFSET job needs a solar event and an offset");
    }

    @Test
    @DisplayName("a solar proposal with no usable day in the year has no year range, and still lists its fires")
    void solar_noUsableDay() {
        build(new SolarService() {
            @Override
            public LocalDateTime sunriseUtc(double lat, double lon, LocalDate date) {
                throw new IllegalStateException("no sun");
            }
        });
        add(job(ForecastCycleJobs.NIGHTLY_JOB, ScheduleType.CRON).cronExpression("0 0 1 * * *").build());

        SchedulePreviewService.Preview preview = service.preview(ForecastCycleJobs.NIGHTLY_JOB,
                new SchedulePreviewService.Proposal(ScheduleType.SOLAR_OFFSET, SolarEvent.SUNRISE, 255, null, null,
                        null, null));

        assertThat(preview.valid()).isTrue();
        assertThat(preview.yearRange()).isNull();
        assertThat(preview.nextFires()).first().isEqualTo(Instant.parse("2027-03-06T01:00:00Z"));
    }

    // -------------------------------------------------------------------------
    // Errors
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an unknown job is a NoSuchElementException (404)")
    void unknownJob() {
        assertThatThrownBy(() -> service.preview("ghost", cron(List.of("06:00"), null, null)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("no schedule type, or a solar rule for a job that may not have one, is an IllegalArgumentException "
            + "(400), not an invalid preview")
    void refusedTypes() {
        assertThatThrownBy(() -> service.preview(JOB, new SchedulePreviewService.Proposal(null, null, null, null,
                null, null, null))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scheduleType is required");
        assertThatThrownBy(() -> service.preview(JOB, new SchedulePreviewService.Proposal(
                ScheduleType.SOLAR_OFFSET, SolarEvent.SUNSET, 240, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Job 'tide_refresh' may not be switched to SOLAR_OFFSET; only the two forecast cycle "
                        + "jobs may");
    }

    @Test
    @DisplayName("previewing never saves anything")
    void neverSaves() {
        service.preview(JOB, cron(List.of("06:15"), null, "Europe/London"));
        service.preview(JOB, cron(List.of("06:15", "18:45"), null, null));

        verify(configs, never()).save(all.get(0));
    }

    // -------------------------------------------------------------------------
    // Neighbours
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("neighbours are other active, non-repeating jobs firing within 30 minutes (inclusive) of a "
            + "previewed fire in the next 24 hours, nearest first")
    void neighbours_withinThirtyMinutes() {
        add(job("near", ScheduleType.CRON).cronExpression("0 40 12 * * *").build());
        add(job("edge", ScheduleType.CRON).cronExpression("0 30 13 * * *").build());
        add(job("just_outside", ScheduleType.CRON).cronExpression("0 31 13 * * *").build());
        add(job("far", ScheduleType.CRON).cronExpression("0 0 20 * * *").build());
        add(job("paused", ScheduleType.CRON).cronExpression("0 5 13 * * *").status(SchedulerJobStatus.PAUSED)
                .build());
        add(job("poller", ScheduleType.CRON).cronExpression("0 */5 * * * *").build());
        add(job("delay", ScheduleType.FIXED_DELAY).fixedDelayMs(60_000L).build());

        SchedulePreviewService.Preview preview = service.preview(JOB, cron(List.of("13:00"), null, null));

        assertThat(preview.neighbours()).containsExactly(
                new SchedulePreviewService.Neighbour("near", "Name of near", Instant.parse("2027-03-05T12:40:00Z"),
                        20),
                new SchedulePreviewService.Neighbour("edge", "Name of edge", Instant.parse("2027-03-05T13:30:00Z"),
                        30));
    }

    @Test
    @DisplayName("a job's own current schedule is never its own neighbour")
    void neighbours_excludeTheJobItself() {
        SchedulePreviewService.Preview preview = service.preview(JOB, cron(List.of("02:00"), null, null));

        assertThat(preview.neighbours()).isEmpty();
    }

    @Test
    @DisplayName("neighbours pick each job's closest pair of fires")
    void neighbours_closestPairPerJob() {
        add(job("twice", ScheduleType.CRON).cronExpression("0 0 12,13 * * *").build());

        SchedulePreviewService.Preview preview = service.preview(JOB, cron(List.of("13:10"), null, null));

        assertThat(preview.neighbours()).containsExactly(new SchedulePreviewService.Neighbour("twice",
                "Name of twice", Instant.parse("2027-03-05T13:00:00Z"), 10));
    }

    @Test
    @DisplayName("a proposal that itself repeats (more than six fires a day) lists no neighbours")
    void neighbours_emptyWhenTheProposalRepeats() {
        add(job("near", ScheduleType.CRON).cronExpression("0 40 12 * * *").build());

        SchedulePreviewService.Preview preview = service.preview(JOB, cron(
                List.of("12:00", "13:00", "14:00", "15:00", "16:00", "17:00", "18:00"), null, null));

        assertThat(preview.valid()).isTrue();
        assertThat(preview.neighbours()).isEmpty();
    }
}
