package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import com.gregochr.goldenhour.service.SolarOffsetTrigger;
import com.gregochr.goldenhour.service.SolarTriggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the words {@link ScheduledFiresLister} serves for how a job is triggered, and for the jobs it lists.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledFiresListerTest {

    private static final Instant AFTER = Instant.parse("2026-11-02T09:26:00Z");
    private static final Instant BEFORE = Instant.parse("2026-11-03T00:00:00Z");
    private static final Instant FIRE = Instant.parse("2026-11-02T12:39:00Z");

    @Mock
    private SchedulerJobConfigRepository configs;

    @Mock
    private DynamicSchedulerService scheduler;

    @Mock
    private SolarTriggerFactory solarTriggers;

    private ScheduledFiresLister lister;

    @BeforeEach
    void setUp() {
        lister = new ScheduledFiresLister(configs, scheduler, solarTriggers);
    }

    private static SchedulerJobConfigEntity solarJob(String key, SolarEvent event, int offset) {
        return SchedulerJobConfigEntity.builder().jobKey(key).displayName(key).scheduleType(ScheduleType.SOLAR_OFFSET)
                .solarEvent(event).solarOffsetMinutes(offset).cronExpression("0 0 12 * * *")
                .status(SchedulerJobStatus.ACTIVE).updatedAt(AFTER).build();
    }

    @Test
    @DisplayName("an offset is worded in hours and minutes")
    void offsetWording() {
        assertThat(ScheduledFiresLister.offsetText(250)).isEqualTo("4h 10m");
        assertThat(ScheduledFiresLister.offsetText(60)).isEqualTo("1h");
        assertThat(ScheduledFiresLister.offsetText(45)).isEqualTo("45m");
    }

    @Test
    @DisplayName("sunrise and zero-offset jobs read naturally, and a job the Day view cannot show is left out")
    void solarWording() {
        SchedulerJobConfigEntity sunrise = solarJob(ForecastCycleJobs.EVENING_JOB, SolarEvent.SUNRISE, 0);
        SchedulerJobConfigEntity unknown = solarJob("not_a_run_job", SolarEvent.SUNSET, 60);
        when(configs.findAllByOrderByIdAsc()).thenReturn(List.of(sunrise, unknown));
        when(scheduler.firesBetween(eq(sunrise), any(), any())).thenReturn(List.of(FIRE));
        when(solarTriggers.create(eq(ForecastCycleJobs.EVENING_JOB), eq(SolarEvent.SUNRISE), eq(0), any()))
                .thenReturn(new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 0, null,
                        (event, date) -> date.atTime(7, 12)));

        List<ScheduledFiresLister.Fire> fires = lister.fires(AFTER, BEFORE);

        assertThat(fires).hasSize(1);
        assertThat(fires.get(0).subline()).isEqualTo("Sun-relative · at sunrise (07:12)");
        assertThat(fires.get(0).name()).isEqualTo("Evening sunrise refresh");
    }

    @Test
    @DisplayName("when the sun time cannot be worked out the trigger is still described, without a time")
    void solarWordingWithoutSunTime() {
        SchedulerJobConfigEntity noEvent = solarJob(ForecastCycleJobs.INTRADAY_JOB, SolarEvent.SUNSET, 90);
        when(configs.findAllByOrderByIdAsc()).thenReturn(List.of(noEvent));
        when(scheduler.firesBetween(eq(noEvent), any(), any())).thenReturn(List.of(FIRE));
        when(solarTriggers.create(eq(ForecastCycleJobs.INTRADAY_JOB), eq(SolarEvent.SUNSET), anyInt(), any()))
                .thenThrow(new IllegalArgumentException("bad offset"));

        assertThat(lister.fires(AFTER, BEFORE).get(0).subline()).isEqualTo("Sun-relative · 1h 30m before sunset");
    }

    @Test
    @DisplayName("the populations needed are the fires', plus briefing and ask-ready only when something chains them")
    void keysNeeded() {
        ScheduledFiresLister.Fire plain = new ScheduledFiresLister.Fire("tide_refresh", "Tide data",
                StatKey.ofJob(com.gregochr.goldenhour.entity.RunType.TIDE, null), ScheduledFiresLister.Role.PLAIN,
                FIRE, "Fixed time · 12:39", null);
        ScheduledFiresLister.Fire noKey = new ScheduledFiresLister.Fire("aurora_batch_evaluation", "Aurora batch", null,
                ScheduledFiresLister.Role.PLAIN, FIRE, "Fixed time · 12:39", null);
        ScheduledFiresLister.Fire cycle = new ScheduledFiresLister.Fire(ForecastCycleJobs.NIGHTLY_JOB, "Overnight",
                StatKey.ofCycle(com.gregochr.goldenhour.entity.CycleType.NIGHTLY), ScheduledFiresLister.Role.CYCLE,
                FIRE, "Fixed time · 12:39", com.gregochr.goldenhour.entity.CycleType.NIGHTLY);

        assertThat(ScheduledFiresLister.keysNeeded(List.of(plain, noKey))).hasSize(1);
        assertThat(ScheduledFiresLister.keysNeeded(List.of(cycle))).hasSize(3);
    }

    @Test
    @DisplayName("a briefing child needs history of the briefing; without it only the cycle is listed")
    void childrenNeedHistory() {
        StatKey nightly = StatKey.ofCycle(com.gregochr.goldenhour.entity.CycleType.NIGHTLY);
        ScheduledFiresLister.Fire cycle = new ScheduledFiresLister.Fire(ForecastCycleJobs.NIGHTLY_JOB, "Overnight",
                nightly, ScheduledFiresLister.Role.CYCLE, FIRE, "Fixed time · 12:39",
                com.gregochr.goldenhour.entity.CycleType.NIGHTLY);
        List<RunStats.Sample> history = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            history.add(new RunStats.Sample(Instant.parse("2026-10-0" + (i + 1) + "T01:00:00Z"), 3000, 0L));
        }

        List<ScheduledFiresLister.Upcoming> rows = lister.rows(List.of(cycle),
                new RunStats(Map.of(nightly, history)), null, AFTER, BEFORE);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).durationSeconds()).isEqualTo(3000L);
        assertThat(rows.get(0).finish()).isEqualTo(FIRE.plusSeconds(3000));
    }

    @Test
    @DisplayName("an estimated child that would start after the end of the day is dropped")
    void childPastMidnightDropped() {
        StatKey nightly = StatKey.ofCycle(com.gregochr.goldenhour.entity.CycleType.NIGHTLY);
        StatKey briefing = StatKey.ofJob(com.gregochr.goldenhour.entity.RunType.BRIEFING, null);
        Instant late = Instant.parse("2026-11-02T23:00:00Z");
        ScheduledFiresLister.Fire cycle = new ScheduledFiresLister.Fire(ForecastCycleJobs.NIGHTLY_JOB, "Overnight",
                nightly, ScheduledFiresLister.Role.CYCLE, late, "Fixed time · 23:00",
                com.gregochr.goldenhour.entity.CycleType.NIGHTLY);
        List<RunStats.Sample> cycles = new java.util.ArrayList<>();
        List<RunStats.Sample> briefings = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Instant at = Instant.parse("2026-10-0" + (i + 1) + "T01:00:00Z");
            cycles.add(new RunStats.Sample(at, 7200, 0L));
            briefings.add(new RunStats.Sample(at, 300, 0L));
        }

        List<ScheduledFiresLister.Upcoming> rows = lister.rows(List.of(cycle),
                new RunStats(Map.of(nightly, cycles, briefing, briefings)), null, AFTER, BEFORE);

        assertThat(rows).extracting(ScheduledFiresLister.Upcoming::name).containsExactly("Overnight");
    }
}
