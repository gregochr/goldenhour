package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.model.runs.NoteTone;
import com.gregochr.goldenhour.model.runs.RunDeepKind;
import com.gregochr.goldenhour.model.runs.RunDeepStatus;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.model.runs.RunTimingResponse;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import com.gregochr.goldenhour.service.SolarReferencePoint;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Runs Timing view: the percentile arithmetic, the skew flag, the two measures, the manual and
 * failed exclusions, and the Afternoon trigger and fixed-time facts. "Today" is Monday 2 November 2026; with a
 * 30-day range the first half is 4 to 18 October and the second half 19 October to 2 November.
 */
class RunsTimingServiceTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-02");
    private static final String FIRST_HALF = "2026-10-10T13:00:00Z";
    private static final String SECOND_HALF = "2026-10-25T13:00:00Z";
    private static final int MINUTE = 60;
    private static final Instant BEFORE_DAY = Instant.parse("2026-11-01T23:59:59Z");
    private static final Instant DAY_END = Instant.parse("2026-11-03T00:00:00Z");

    private RunsTimingService service;
    private List<DeepRun> rows;
    private SchedulerJobConfigRepository configs;
    private DynamicSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        RunsLoader loader = mock(RunsLoader.class);
        RunsBounds bounds = mock(RunsBounds.class);
        when(bounds.today()).thenReturn(TODAY);
        SolarReferencePoint point = mock(SolarReferencePoint.class);
        when(point.lat()).thenReturn(54.776);
        when(point.lon()).thenReturn(-1.575);
        configs = mock(SchedulerJobConfigRepository.class);
        scheduler = mock(DynamicSchedulerService.class);
        service = new RunsTimingService(loader, bounds, new SolarService(), point, configs, scheduler);
        rows = new ArrayList<>();
        // Thirty days ending today: from the UK midnight 29 days back to the UK midnight after today.
        Instant from = ForecastHorizon.ukDayStartUtc(TODAY.minusDays(29)).toInstant(ZoneOffset.UTC);
        Instant to = ForecastHorizon.ukDayStartUtc(TODAY.plusDays(1)).toInstant(ZoneOffset.UTC);
        when(loader.load(eq(from), eq(to)))
                .thenAnswer(i -> new RunsLoader.LoadedRuns(rows, new RunFx(List.of(), null)));
    }

    private DeepRun row(RunDeepType type, String start, long seconds, RunDeepStatus status, boolean manual,
            Long parent, Instant answersReady) {
        Instant at = Instant.parse(start);
        return new DeepRun((type.isCycle() ? "cycle-" : "job-") + rows.size(),
                type.isCycle() ? RunDeepKind.CYCLE : RunDeepKind.JOB, type, null, null, type.label(), null, at,
                at.plusSeconds(seconds), seconds, 0L, true, 0, status, null, NoteTone.INFO, parent, null, null,
                null, null, answersReady, null, manual);
    }

    private void add(RunDeepType type, String start, long seconds) {
        rows.add(row(type, start, seconds, RunDeepStatus.DONE, false, 1L, null));
    }

    private void addAll(RunDeepType type, String start, long... seconds) {
        for (long s : seconds) {
            add(type, start, s);
        }
    }

    private static List<Long> ascending(long... values) {
        List<Long> out = new ArrayList<>();
        for (long v : values) {
            out.add(v);
        }
        return out;
    }

    // ── Percentile maths ──────────────────────────────────────────────────────

    @Test
    @DisplayName("no runs: every statistic is null and the count is zero")
    void noRuns() {
        RunTimingResponse r = service.timing("AFTERNOON", 30, null);

        assertThat(r.runs()).isEmpty();
        assertThat(r.stats().count()).isZero();
        assertThat(r.stats().medianSeconds()).isNull();
        assertThat(r.stats().p90Seconds()).isNull();
        assertThat(r.stats().slowest()).isNull();
        assertThat(r.stats().fastest()).isNull();
        assertThat(r.stats().skewed()).isFalse();
        assertThat(r.typeMedians().get("AFTERNOON")).isNull();
    }

    @Test
    @DisplayName("one run: the median is its duration and there is no 90th percentile to serve")
    void oneRun() {
        add(RunDeepType.AFTERNOON, SECOND_HALF, 600);

        RunTimingResponse r = service.timing("AFTERNOON", 30, null);

        assertThat(r.stats().count()).isEqualTo(1);
        assertThat(r.stats().medianSeconds()).isEqualTo(600);
        assertThat(r.stats().p90Seconds()).isNull();
        assertThat(r.stats().skewed()).isFalse();
        assertThat(r.stats().slowest().seconds()).isEqualTo(600);
        assertThat(r.stats().slowest().date()).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(r.stats().changePercent()).isNull();
        assertThat(r.stats().firstHalfMedianSeconds()).isNull();
        assertThat(r.stats().secondHalfMedianSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("two runs: the median is their mean and the 90th percentile is the slower one")
    void twoRuns() {
        addAll(RunDeepType.AFTERNOON, SECOND_HALF, 600, 800);

        RunTimingResponse r = service.timing("AFTERNOON", 30, null);

        assertThat(r.stats().medianSeconds()).isEqualTo(700);
        assertThat(r.stats().p90Seconds()).isEqualTo(800);
    }

    @ParameterizedTest(name = "{0} values: median {1}, p90 {2}")
    @CsvSource({"10,5,9", "11,6,10", "20,10,18", "100,50,90"})
    @DisplayName("many runs: the nearest-rank 90th percentile and the median of 1..n seconds")
    void manyRuns(int n, long median, long p90) {
        List<Long> values = new ArrayList<>();
        for (long v = 1; v <= n; v++) {
            values.add(v);
        }
        assertThat(RunsTimingService.median(values)).isEqualTo(median);
        assertThat(RunsTimingService.p90(values)).isEqualTo(p90);
    }

    @Test
    @DisplayName("the percentile helpers handle an empty and a single-value list")
    void percentileEdges() {
        assertThat(RunsTimingService.median(ascending())).isNull();
        assertThat(RunsTimingService.p90(ascending())).isNull();
        assertThat(RunsTimingService.p90(ascending(7))).isNull();
        assertThat(RunsTimingService.median(ascending(7))).isEqualTo(7);
    }

    @Test
    @DisplayName("the shape is skewed when the 90th percentile is more than 1.35 times the median")
    void skewFlag() {
        addAll(RunDeepType.AFTERNOON, SECOND_HALF, 600, 600, 600, 600, 600, 600, 600, 600, 1500, 1500);
        assertThat(service.timing("AFTERNOON", 30, null).stats().skewed()).isTrue();

        rows.clear();
        addAll(RunDeepType.AFTERNOON, SECOND_HALF, 600, 600, 600, 600, 600, 600, 600, 600, 800, 800);
        assertThat(service.timing("AFTERNOON", 30, null).stats().skewed()).isFalse();
    }

    @Test
    @DisplayName("the halves of the range give a median each and the change between them")
    void halvesAndChange() {
        addAll(RunDeepType.EVENING, FIRST_HALF, 1000, 1200);
        addAll(RunDeepType.EVENING, SECOND_HALF, 1100, 1300);
        rows.add(row(RunDeepType.EVENING, "2026-10-05T01:00:00Z", 100, RunDeepStatus.DONE, false, null, null));

        RunTimingResponse r = service.timing("EVENING", 30, null);

        assertThat(r.stats().count()).isEqualTo(5);
        assertThat(r.stats().firstHalfMedianSeconds()).isEqualTo(1000);
        assertThat(r.stats().secondHalfMedianSeconds()).isEqualTo(1200);
        assertThat(r.stats().changePercent()).isEqualTo(20);
        assertThat(r.stats().fastest().seconds()).isEqualTo(100);
        assertThat(r.stats().fastest().date()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(r.runs()).hasSize(5);
        assertThat(r.runs().get(0).startedAt()).isEqualTo(Instant.parse("2026-10-05T01:00:00Z"));
    }

    // ── Populations ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("failed, running and manual runs are left out, and a briefing counts only with a cycle")
    void exclusions() {
        add(RunDeepType.ASK_READY, SECOND_HALF, 100);
        rows.add(row(RunDeepType.ASK_READY, SECOND_HALF, 5000, RunDeepStatus.DONE, true, null, null));
        rows.add(row(RunDeepType.ASK_READY, SECOND_HALF, 9000, RunDeepStatus.FAILED, false, null, null));
        rows.add(row(RunDeepType.ASK_READY, SECOND_HALF, 9000, RunDeepStatus.RUNNING, false, null, null));
        add(RunDeepType.BRIEFING, SECOND_HALF, 50);
        rows.add(row(RunDeepType.BRIEFING, SECOND_HALF, 7000, RunDeepStatus.DONE, false, null, null));
        add(RunDeepType.OTHER, SECOND_HALF, 3);

        RunTimingResponse ask = service.timing("ASK_READY", 30, null);
        RunTimingResponse briefing = service.timing("BRIEFING", 30, "cycle");

        assertThat(ask.stats().count()).isEqualTo(1);
        assertThat(ask.stats().manualExcluded()).isTrue();
        assertThat(briefing.stats().count()).isEqualTo(1);
        assertThat(briefing.stats().manualExcluded()).isFalse();
        assertThat(service.timing("AFTERNOON", 30, null).stats().manualExcluded()).isFalse();
        assertThat(service.timing("SKY_EVAL", 30, null).stats().manualExcluded()).isTrue();
    }

    @Test
    @DisplayName("type medians cover every timing type over the range, null where a type has no runs")
    void typeMedians() {
        addAll(RunDeepType.OVERNIGHT, SECOND_HALF, 3000, 3200);
        add(RunDeepType.SKY_EVAL, FIRST_HALF, 900);

        RunTimingResponse r = service.timing("AFTERNOON", 30, null);

        assertThat(r.typeMedians()).containsKeys("OVERNIGHT", "AFTERNOON", "EVENING", "ASK_READY", "BRIEFING",
                "SKY_EVAL").doesNotContainKey("OTHER");
        assertThat(r.typeMedians().get("OVERNIGHT")).isEqualTo(3100);
        assertThat(r.typeMedians().get("SKY_EVAL")).isEqualTo(900);
        assertThat(r.typeMedians().get("EVENING")).isNull();
    }

    @Test
    @DisplayName("each run carries its own day's sunset, not today's")
    void sunsetPerRun() {
        add(RunDeepType.AFTERNOON, FIRST_HALF, 600);
        add(RunDeepType.AFTERNOON, SECOND_HALF, 600);

        RunTimingResponse r = service.timing("AFTERNOON", 30, null);

        assertThat(r.runs().get(0).sunsetAt()).isNotEqualTo(r.runs().get(1).sunsetAt());
        assertThat(r.runs().get(0).date()).isEqualTo(LocalDate.parse("2026-10-10"));
        assertThat(r.runs().get(1).sunsetAt()).isAfter(Instant.parse("2026-10-25T15:00:00Z"))
                .isBefore(Instant.parse("2026-10-25T17:30:00Z"));
        assertThat(r.runs().get(1).id()).startsWith("cycle-");
    }

    // ── Measures ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("measure=ready runs from the cycle's start to its answers being ready; unlinked cycles drop out")
    void readyMeasure() {
        Instant start = Instant.parse(SECOND_HALF);
        rows.add(row(RunDeepType.AFTERNOON, SECOND_HALF, 600, RunDeepStatus.DONE, false, null,
                start.plusSeconds(900)));
        rows.add(row(RunDeepType.AFTERNOON, "2026-10-26T13:00:00Z", 700, RunDeepStatus.DONE, false, null,
                Instant.parse("2026-10-26T13:00:00Z").plusSeconds(1100)));
        rows.add(row(RunDeepType.AFTERNOON, "2026-10-27T13:00:00Z", 650, RunDeepStatus.DONE, false, null, null));

        RunTimingResponse ready = service.timing("AFTERNOON", 30, "ready");
        RunTimingResponse cycle = service.timing("AFTERNOON", 30, "cycle");

        assertThat(ready.measure()).isEqualTo("ready");
        assertThat(ready.stats().count()).isEqualTo(2);
        assertThat(ready.withoutAnswersLink()).isEqualTo(1);
        assertThat(ready.runs()).extracting(RunTimingResponse.TimingRun::durationSeconds).containsExactly(900L, 1100L);
        assertThat(ready.stats().medianSeconds()).isEqualTo(1000);
        assertThat(cycle.stats().count()).isEqualTo(3);
        assertThat(cycle.withoutAnswersLink()).isZero();
        assertThat(cycle.typeMedians().get("AFTERNOON")).isEqualTo(650);
        assertThat(ready.typeMedians().get("AFTERNOON")).isEqualTo(650);
    }

    @Test
    @DisplayName("an unknown type, OTHER, a bad range or measure, and ready for a non-cycle are all refused")
    void validation() {
        assertThatThrownBy(() -> service.timing(null, 30, null)).isInstanceOf(RunsRequestException.class);
        assertThatThrownBy(() -> service.timing("  ", 30, null)).isInstanceOf(RunsRequestException.class);
        assertThatThrownBy(() -> service.timing("NOPE", 30, null)).isInstanceOf(RunsRequestException.class);
        assertThatThrownBy(() -> service.timing("OTHER", 30, null)).isInstanceOf(RunsRequestException.class);
        assertThatThrownBy(() -> service.timing("AFTERNOON", 45, null)).isInstanceOf(RunsRequestException.class);
        assertThatThrownBy(() -> service.timing("AFTERNOON", 30, "fast")).isInstanceOf(RunsRequestException.class);
        assertThatThrownBy(() -> service.timing("BRIEFING", 30, "ready")).isInstanceOf(RunsRequestException.class)
                .hasMessageContaining("cycle types");
        assertThatThrownBy(() -> service.timing("SKY_EVAL", 30, "ready")).isInstanceOf(RunsRequestException.class);
        assertThat(service.timing("afternoon", null, " READY ").rangeDays()).isEqualTo(30);
    }

    // ── Afternoon trigger and fixed-time cycles ───────────────────────────────

    private SchedulerJobConfigEntity config(String key, ScheduleType type, SolarEvent event, Integer offset) {
        return SchedulerJobConfigEntity.builder().jobKey(key).displayName(key).scheduleType(type)
                .cronExpression("0 0 1 * * *").solarEvent(event).solarOffsetMinutes(offset)
                .timeZone("Europe/London").build();
    }

    @Test
    @DisplayName("the Afternoon trigger is the job's solar rule, with today's sunset beside it")
    void afternoonTriggerSolar() {
        when(configs.findByJobKey(ForecastCycleJobs.INTRADAY_JOB)).thenReturn(Optional.of(
                config(ForecastCycleJobs.INTRADAY_JOB, ScheduleType.SOLAR_OFFSET, SolarEvent.SUNSET, 250)));

        RunTimingResponse r = service.timing("AFTERNOON", 30, null);

        assertThat(r.trigger().solarEvent()).isEqualTo("SUNSET");
        assertThat(r.trigger().offsetMinutes()).isEqualTo(250);
        assertThat(r.todaySunset()).isBetween(Instant.parse("2026-11-02T15:30:00Z"),
                Instant.parse("2026-11-02T17:00:00Z"));
        assertThat(r.startsAt()).isNull();
    }

    @Test
    @DisplayName("the Afternoon trigger is null when its schedule is a fixed time or the job is missing")
    void afternoonTriggerCron() {
        when(configs.findByJobKey(ForecastCycleJobs.INTRADAY_JOB)).thenReturn(Optional.of(
                config(ForecastCycleJobs.INTRADAY_JOB, ScheduleType.CRON, SolarEvent.SUNSET, 250)));
        assertThat(service.timing("AFTERNOON", 30, null).trigger()).isNull();
        assertThat(service.timing("AFTERNOON", 30, null).todaySunset()).isNotNull();

        when(configs.findByJobKey(ForecastCycleJobs.INTRADAY_JOB)).thenReturn(Optional.empty());
        assertThat(service.timing("AFTERNOON", 30, null).trigger()).isNull();
        assertThat(service.timing("OVERNIGHT", 30, null).trigger()).isNull();
        assertThat(service.timing("OVERNIGHT", 30, null).todaySunset()).isNull();
    }

    @Test
    @DisplayName("the Overnight card gives the start, typical, 9-in-10 and worst ready times and the slack to sunrise")
    void overnightReadyBy() {
        SchedulerJobConfigEntity nightly = config(ForecastCycleJobs.NIGHTLY_JOB, ScheduleType.CRON, null, null);
        when(configs.findByJobKey(ForecastCycleJobs.NIGHTLY_JOB)).thenReturn(Optional.of(nightly));
        Instant starts = Instant.parse("2026-11-02T01:00:00Z");
        when(scheduler.firesBetween(eq(nightly), eq(BEFORE_DAY), eq(DAY_END))).thenReturn(List.of(starts));
        for (int i = 1; i <= 10; i++) {
            String day = "2026-10-" + (10 + i) + "T01:00:00Z";
            Instant at = Instant.parse(day);
            rows.add(row(RunDeepType.OVERNIGHT, day, i * MINUTE, RunDeepStatus.DONE, false, null,
                    at.plusSeconds(i * 2L * MINUTE)));
        }

        RunTimingResponse r = service.timing("OVERNIGHT", 30, null);

        assertThat(r.readyBasis()).isEqualTo("ready");
        assertThat(r.startsAt()).isEqualTo(starts);
        assertThat(r.typicalReadyAt()).isEqualTo(starts.plusSeconds(11 * MINUTE));
        assertThat(r.p90ReadyAt()).isEqualTo(starts.plusSeconds(18 * MINUTE));
        assertThat(r.worstReadyAt()).isEqualTo(starts.plusSeconds(20 * MINUTE));
        assertThat(r.sunriseToday()).isBetween(Instant.parse("2026-11-02T06:30:00Z"),
                Instant.parse("2026-11-02T08:00:00Z"));
        assertThat(r.slackAtP90Minutes()).isEqualTo(java.time.Duration.between(r.p90ReadyAt(), r.sunriseToday())
                .toMinutes());
        assertThat(r.stats().count()).isEqualTo(10);
    }

    @Test
    @DisplayName("with no answers-ready link the times are the cycle alone, and the Evening card has no sunrise")
    void readyByWithoutLinks() {
        SchedulerJobConfigEntity evening = config(ForecastCycleJobs.EVENING_JOB, ScheduleType.CRON, null, null);
        when(configs.findByJobKey(ForecastCycleJobs.EVENING_JOB)).thenReturn(Optional.of(evening));
        Instant starts = Instant.parse("2026-11-02T20:00:00Z");
        when(scheduler.firesBetween(eq(evening), eq(BEFORE_DAY), eq(DAY_END))).thenReturn(List.of(starts));
        addAll(RunDeepType.EVENING, SECOND_HALF, 600, 1200);

        RunTimingResponse r = service.timing("EVENING", 30, null);

        assertThat(r.readyBasis()).isEqualTo("cycle");
        assertThat(r.typicalReadyAt()).isEqualTo(starts.plusSeconds(900));
        assertThat(r.worstReadyAt()).isEqualTo(starts.plusSeconds(1200));
        assertThat(r.sunriseToday()).isNull();
        assertThat(r.slackAtP90Minutes()).isNull();
    }

    @Test
    @DisplayName("a fixed-time cycle with no fire today, or no history, serves no ready times")
    void readyByNoFire() {
        SchedulerJobConfigEntity nightly = config(ForecastCycleJobs.NIGHTLY_JOB, ScheduleType.CRON, null, null);
        when(configs.findByJobKey(ForecastCycleJobs.NIGHTLY_JOB)).thenReturn(Optional.of(nightly));
        when(scheduler.firesBetween(eq(nightly), eq(BEFORE_DAY), eq(DAY_END))).thenReturn(List.of());
        addAll(RunDeepType.OVERNIGHT, SECOND_HALF, 600, 700);

        RunTimingResponse paused = service.timing("OVERNIGHT", 30, null);
        assertThat(paused.startsAt()).isNull();
        assertThat(paused.typicalReadyAt()).isNull();
        assertThat(paused.slackAtP90Minutes()).isNull();

        rows.clear();
        when(scheduler.firesBetween(eq(nightly), eq(BEFORE_DAY), eq(DAY_END)))
                .thenReturn(List.of(Instant.parse("2026-11-02T01:00:00Z")));
        RunTimingResponse empty = service.timing("OVERNIGHT", 30, null);
        assertThat(empty.startsAt()).isNotNull();
        assertThat(empty.typicalReadyAt()).isNull();
    }
}
