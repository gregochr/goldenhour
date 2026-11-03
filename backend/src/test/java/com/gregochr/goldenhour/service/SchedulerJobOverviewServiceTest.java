package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerFireTrigger;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link SchedulerJobOverviewService}: one fire-log query for every job, the next three fires of each kind of
 * schedule, and the summary counts with their repeating-job exclusion.
 */
@ExtendWith(MockitoExtension.class)
class SchedulerJobOverviewServiceTest {

    private static final Instant NOW = Instant.parse("2027-03-05T12:00:00Z");
    private static final Instant NEAR_END = NOW.plus(SchedulerJobOverviewService.NEAR_WINDOW);
    private static final Instant FAR_END = NOW.plus(SchedulerJobOverviewService.FAR_WINDOW);
    private static final Instant DAY_END = Instant.parse("2027-03-06T12:00:00Z");

    @Mock
    private SchedulerFireLogRepository fireLog;

    @Mock
    private DynamicSchedulerService scheduler;

    private SchedulerJobOverviewService service() {
        return new SchedulerJobOverviewService(fireLog, scheduler, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SchedulerJobConfigEntity config(String key, ScheduleType type, SchedulerJobStatus status) {
        return SchedulerJobConfigEntity.builder().id(1L).jobKey(key).displayName("Name of " + key)
                .scheduleType(type).cronExpression(type == ScheduleType.FIXED_DELAY ? null : "0 0 2 * * *")
                .fixedDelayMs(type == ScheduleType.FIXED_DELAY ? 60_000L : null).status(status).build();
    }

    private static SchedulerFireLogEntity fire(long id, String key, Instant firedAt, SchedulerFireStatus status) {
        SchedulerFireLogEntity row = new SchedulerFireLogEntity();
        row.setId(id);
        row.setJobKey(key);
        row.setFiredAt(firedAt);
        row.setCompletedAt(firedAt.plusSeconds(5));
        row.setStatus(status);
        row.setSummary("summary " + id);
        row.setTriggerKind(SchedulerFireTrigger.SCHEDULED);
        return row;
    }

    // ── lastFires ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("lastFires maps every job's newest row from a single query")
    void lastFires_oneQueryForAllJobs() {
        Instant tide = NOW.minusSeconds(600);
        Instant aurora = NOW.minusSeconds(60);
        when(fireLog.findNewestPerJob()).thenReturn(List.of(
                fire(1, "tide_refresh", tide, SchedulerFireStatus.SUCCEEDED),
                fire(2, "aurora_polling", aurora, SchedulerFireStatus.FAILED)));

        Map<String, SchedulerJobOverviewService.LastFire> last = service().lastFires();

        assertThat(last).containsOnlyKeys("tide_refresh", "aurora_polling");
        assertThat(last.get("tide_refresh")).isEqualTo(new SchedulerJobOverviewService.LastFire(tide,
                tide.plusSeconds(5), "SUCCEEDED", "summary 1", "SCHEDULED"));
        assertThat(last.get("aurora_polling").status()).isEqualTo("FAILED");
        verify(fireLog).findNewestPerJob();
    }

    @Test
    @DisplayName("two rows at the same instant for one job keep the higher id")
    void lastFires_tieKeepsHigherId() {
        Instant at = NOW.minusSeconds(60);
        when(fireLog.findNewestPerJob()).thenReturn(List.of(
                fire(5, "tide_refresh", at, SchedulerFireStatus.SUCCEEDED),
                fire(9, "tide_refresh", at, SchedulerFireStatus.FAILED),
                fire(7, "tide_refresh", at, SchedulerFireStatus.PARTIAL)));

        Map<String, SchedulerJobOverviewService.LastFire> last = service().lastFires();

        assertThat(last).hasSize(1);
        assertThat(last.get("tide_refresh").summary()).isEqualTo("summary 9");
    }

    @Test
    @DisplayName("a job that has never fired has no lastFire")
    void lastFires_emptyLog() {
        when(fireLog.findNewestPerJob()).thenReturn(List.of());

        assertThat(service().lastFires()).isEmpty();
    }

    // ── nextFires ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a paused or config-disabled job has no next fires and asks the scheduler nothing")
    void nextFires_inactiveJobIsEmpty() {
        assertThat(service().nextFires(config("a", ScheduleType.CRON, SchedulerJobStatus.PAUSED))).isEmpty();
        assertThat(service().nextFires(config("b", ScheduleType.FIXED_DELAY, SchedulerJobStatus.DISABLED_BY_CONFIG)))
                .isEmpty();
        verifyNoInteractions(scheduler);
    }

    @Test
    @DisplayName("a cron with plenty of fires in the near window lists the first three and never widens")
    void nextFires_cronTakesFirstThree() {
        SchedulerJobConfigEntity hourly = config("cloud_verification_backfill", ScheduleType.CRON,
                SchedulerJobStatus.ACTIVE);
        List<Instant> fires = List.of(NOW.plusSeconds(60), NOW.plusSeconds(3660), NOW.plusSeconds(7260),
                NOW.plusSeconds(10860));
        when(scheduler.firesBetween(hourly, NOW, NEAR_END)).thenReturn(fires);

        assertThat(service().nextFires(hourly)).containsExactlyElementsOf(fires.subList(0, 3));
        verify(scheduler, never()).firesBetween(hourly, NOW, FAR_END);
    }

    @Test
    @DisplayName("a weekly cron with one fire in the near window widens to eight days")
    void nextFires_sparseCronWidens() {
        SchedulerJobConfigEntity weekly = config("tide_refresh", ScheduleType.CRON, SchedulerJobStatus.ACTIVE);
        Instant monday = Instant.parse("2027-03-08T02:00:00Z");
        when(scheduler.firesBetween(weekly, NOW, NEAR_END)).thenReturn(List.of());
        when(scheduler.firesBetween(weekly, NOW, FAR_END)).thenReturn(List.of(monday));

        assertThat(service().nextFires(weekly)).containsExactly(monday);
    }

    @Test
    @DisplayName("a solar job is listed by the same arithmetic as a cron")
    void nextFires_solarJob() {
        SchedulerJobConfigEntity solar = config("near_term_batch_evaluation", ScheduleType.SOLAR_OFFSET,
                SchedulerJobStatus.ACTIVE);
        List<Instant> fires = List.of(NOW.plusSeconds(3600), NOW.plusSeconds(90000));
        when(scheduler.firesBetween(solar, NOW, NEAR_END)).thenReturn(fires);
        when(scheduler.firesBetween(solar, NOW, FAR_END)).thenReturn(
                List.of(fires.get(0), fires.get(1), NOW.plusSeconds(176000), NOW.plusSeconds(262000)));

        assertThat(service().nextFires(solar)).containsExactly(fires.get(0), fires.get(1), NOW.plusSeconds(176000));
    }

    @Test
    @DisplayName("a fixed-delay job lists its next fire and the two delays after it")
    void nextFires_fixedDelay() {
        SchedulerJobConfigEntity poll = config("batch_result_polling", ScheduleType.FIXED_DELAY,
                SchedulerJobStatus.ACTIVE);
        Instant next = NOW.plusSeconds(20);
        when(scheduler.calculateNextFireTime(poll)).thenReturn(next);

        assertThat(service().nextFires(poll)).containsExactly(next, next.plusSeconds(60), next.plusSeconds(120));
    }

    @Test
    @DisplayName("a fixed-delay job with no computable next fire, or no delay, degrades without throwing")
    void nextFires_fixedDelayEdgeCases() {
        SchedulerJobConfigEntity noNext = config("a", ScheduleType.FIXED_DELAY, SchedulerJobStatus.ACTIVE);
        when(scheduler.calculateNextFireTime(noNext)).thenReturn(null);
        assertThat(service().nextFires(noNext)).isEmpty();

        SchedulerJobConfigEntity noDelay = SchedulerJobConfigEntity.builder().id(2L).jobKey("b").displayName("b")
                .scheduleType(ScheduleType.FIXED_DELAY).status(SchedulerJobStatus.ACTIVE).build();
        when(scheduler.calculateNextFireTime(noDelay)).thenReturn(NOW.plusSeconds(5));
        assertThat(service().nextFires(noDelay)).containsExactly(NOW.plusSeconds(5));
    }

    // ── summary ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the summary counts by status, type, Claude use and failed last fire")
    void summary_counts() {
        SchedulerJobConfigEntity nightly = config("near_term_batch_evaluation", ScheduleType.SOLAR_OFFSET,
                SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity tide = config("tide_refresh", ScheduleType.CRON, SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity dormant = config("dormant_account_sweep", ScheduleType.CRON,
                SchedulerJobStatus.PAUSED);
        SchedulerJobConfigEntity aurora = config("aurora_polling", ScheduleType.FIXED_DELAY,
                SchedulerJobStatus.DISABLED_BY_CONFIG);
        SchedulerJobConfigEntity sky = config("sky_rating_eval", ScheduleType.CRON, SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity handAdded = config("hand_added_job", ScheduleType.CRON, SchedulerJobStatus.ACTIVE);
        when(scheduler.getAll()).thenReturn(List.of(nightly, tide, dormant, aurora, sky, handAdded));
        when(fireLog.findNewestPerJob()).thenReturn(List.of(
                fire(1, "near_term_batch_evaluation", NOW.minusSeconds(60), SchedulerFireStatus.FAILED),
                fire(2, "tide_refresh", NOW.minusSeconds(60), SchedulerFireStatus.SUCCEEDED),
                fire(3, "hand_added_job", NOW.minusSeconds(60), SchedulerFireStatus.FAILED),
                fire(4, "removed_job", NOW.minusSeconds(60), SchedulerFireStatus.FAILED)));

        SchedulerJobOverviewService.Summary summary = service().summary();

        assertThat(summary.total()).isEqualTo(6);
        assertThat(summary.byStatus()).containsExactly(Map.entry("ACTIVE", 4), Map.entry("PAUSED", 1),
                Map.entry("DISABLED_BY_CONFIG", 1));
        assertThat(summary.byType()).containsExactly(Map.entry("CRON", 4), Map.entry("FIXED_DELAY", 1),
                Map.entry("SOLAR_OFFSET", 1));
        assertThat(summary.usesClaude()).isEqualTo(2);
        assertThat(summary.lastFireFailed()).isEqualTo(2);
        verify(fireLog).findNewestPerJob();
    }

    @Test
    @DisplayName("nextScheduled is the soonest fire among ACTIVE jobs, leaving out fixed-delay and busy crons")
    void summary_nextScheduledExcludesRepeaters() {
        SchedulerJobConfigEntity poller = config("batch_result_polling", ScheduleType.FIXED_DELAY,
                SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity hourly = config("cloud_verification_backfill", ScheduleType.CRON,
                SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity astro = config("astro_conditions_refresh", ScheduleType.CRON,
                SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity tide = config("tide_refresh", ScheduleType.CRON, SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity paused = config("dormant_account_sweep", ScheduleType.CRON,
                SchedulerJobStatus.PAUSED);
        when(scheduler.getAll()).thenReturn(List.of(poller, hourly, astro, tide, paused));
        when(fireLog.findNewestPerJob()).thenReturn(List.of());
        List<Instant> twentyFour = new ArrayList<>();
        for (int i = 1; i <= 24; i++) {
            twentyFour.add(NOW.plusSeconds(3600L * i));
        }
        when(scheduler.firesBetween(hourly, NOW, DAY_END)).thenReturn(twentyFour);
        when(scheduler.firesBetween(astro, NOW, DAY_END)).thenReturn(List.of(NOW.plusSeconds(1800)));
        when(scheduler.firesBetween(tide, NOW, DAY_END)).thenReturn(List.of());
        Instant astroNext = NOW.plusSeconds(1800);
        when(scheduler.calculateNextFireTime(astro)).thenReturn(astroNext);
        when(scheduler.calculateNextFireTime(tide)).thenReturn(Instant.parse("2027-03-08T02:00:00Z"));

        SchedulerJobOverviewService.NextScheduled next = service().summary().nextScheduled();

        assertThat(next).isEqualTo(new SchedulerJobOverviewService.NextScheduled("astro_conditions_refresh",
                "Name of astro_conditions_refresh", astroNext));
        verify(scheduler, never()).calculateNextFireTime(poller);
        verify(scheduler, never()).calculateNextFireTime(hourly);
        verify(scheduler, never()).calculateNextFireTime(paused);
    }

    @Test
    @DisplayName("nextScheduled is null when every job repeats, is paused or has no computable fire")
    void summary_nextScheduledNull() {
        SchedulerJobConfigEntity poller = config("batch_result_polling", ScheduleType.FIXED_DELAY,
                SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity broken = config("broken", ScheduleType.CRON, SchedulerJobStatus.ACTIVE);
        when(scheduler.getAll()).thenReturn(List.of(poller, broken));
        when(fireLog.findNewestPerJob()).thenReturn(List.of());
        when(scheduler.firesBetween(broken, NOW, DAY_END)).thenReturn(List.of());
        when(scheduler.calculateNextFireTime(broken)).thenReturn(null);

        assertThat(service().summary().nextScheduled()).isNull();
    }

    @Test
    @DisplayName("an empty scheduler summarises to zero counts with every status and type present")
    void summary_empty() {
        when(scheduler.getAll()).thenReturn(List.of());
        when(fireLog.findNewestPerJob()).thenReturn(List.of());

        SchedulerJobOverviewService.Summary summary = service().summary();

        assertThat(summary.total()).isZero();
        assertThat(summary.byStatus().values()).containsOnly(0);
        assertThat(summary.byType().keySet()).containsExactly("CRON", "FIXED_DELAY", "SOLAR_OFFSET");
        assertThat(summary.nextScheduled()).isNull();
    }
}
