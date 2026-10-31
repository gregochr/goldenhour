package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.model.BatchSummary;
import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunKind;
import com.gregochr.goldenhour.model.runs.RunListItem;
import com.gregochr.goldenhour.model.runs.RunListResponse;
import com.gregochr.goldenhour.model.runs.RunListStatus;
import com.gregochr.goldenhour.model.runs.RunRange;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.service.BatchSummaryDeriver;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Service tests for the merged Runs list over real repositories (H2, no Docker): the global exclusion,
 * grouping, spend, median, bounds, running counts and cursor paging.
 */
@DataJpaTest
@Import({AdminRunsService.class, AdminRunsServiceTest.ClockConfig.class})
class AdminRunsServiceTest {

    /** Saturday 31 October 2026, noon UTC: GMT (British Summer Time ended on 25 October). */
    private static final Instant NOON = Instant.parse("2026-10-31T12:00:00Z");

    private static final LocalDateTime NOON_UTC = LocalDateTime.of(2026, 10, 31, 12, 0);

    /** A clock the test can move. */
    static final class SettableClock extends Clock {
        private Instant instant = NOON;

        void set(Instant value) {
            this.instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Supplies the movable clock as the application {@link Clock}. */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        SettableClock clock() {
            return new SettableClock();
        }
    }

    @Autowired
    private AdminRunsService service;

    @Autowired
    private SettableClock clock;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PipelineRunRepository pipelineRuns;

    @MockitoBean
    private BatchSummaryDeriver deriver;

    @MockitoBean
    private DynamicSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        clock.set(NOON);
    }

    private JobRunEntity job(RunType type, LocalDateTime startedAt, boolean completed, int succeeded, int failed,
            Long cost) {
        JobRunEntity run = JobRunEntity.builder().runType(type).startedAt(startedAt)
                .completedAt(completed ? startedAt.plusMinutes(10) : null)
                .durationMs(completed ? 600_000L : null)
                .succeeded(succeeded).failed(failed).locationsProcessed(succeeded + failed)
                .triggeredManually(false).totalCostMicroDollars(cost).exchangeRateGbpPerUsd(0.8).build();
        return em.persist(run);
    }

    private JobRunEntity doneJob(RunType type, LocalDateTime startedAt) {
        return job(type, startedAt, true, 5, 0, null);
    }

    private PipelineRunEntity cycle(CycleType type, Instant trigger, PipelineRunStatus status) {
        PipelineRunEntity run = new PipelineRunEntity(type, trigger);
        run.setStatus(status);
        if (status != PipelineRunStatus.RUNNING) {
            run.setCompletedAt(trigger.plusSeconds(600));
        }
        return em.persist(run);
    }

    private ForecastBatchEntity batch(String anthropicId, BatchType type, Long cycleId, Long jobRunId,
            BatchStatus status) {
        ForecastBatchEntity batch = new ForecastBatchEntity(anthropicId, type, 10, NOON.plusSeconds(86_400));
        batch.setPipelineRunId(cycleId);
        batch.setJobRunId(jobRunId);
        batch.setStatus(status);
        return em.persist(batch);
    }

    private void commit() {
        em.flush();
        em.clear();
    }

    private RunListResponse list(RunRange range, RunGroup kind, boolean attention) {
        return service.list(range, kind, attention, null, AdminRunsService.MAX_SIZE);
    }

    private static List<Long> ids(RunListResponse response, RunKind kind) {
        return response.items().stream().filter(i -> i.kind() == kind).map(RunListItem::id).toList();
    }

    @Test
    @DisplayName("a batch of a cycle that started before the range stays hidden, and so does its cycle row")
    void exclusionIsGlobalNotJustCyclesInRange() {
        PipelineRunEntity oldCycle = cycle(CycleType.NIGHTLY, Instant.parse("2026-10-29T01:00:00Z"),
                PipelineRunStatus.RUNNING);
        JobRunEntity cycleBatchRun = job(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 3, 0), false, 0, 0,
                null);
        JobRunEntity standalone = doneJob(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 4, 0));
        batch("msgbatch_old", BatchType.FORECAST, oldCycle.getId(), cycleBatchRun.getId(), BatchStatus.SUBMITTED);
        commit();

        RunListResponse response = list(RunRange.TODAY, null, false);

        assertThat(ids(response, RunKind.JOB)).containsExactly(standalone.getId());
        assertThat(ids(response, RunKind.CYCLE)).isEmpty();
        assertThat(response.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("a disposition anchor run is hidden, and the notes fallback hides an unlinked cycle batch run")
    void dispositionAndNotesFallback() {
        PipelineRunEntity nightly = cycle(CycleType.NIGHTLY, Instant.parse("2026-10-31T01:00:00Z"),
                PipelineRunStatus.COMPLETED);
        JobRunEntity anchor = doneJob(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 1, 5));
        JobRunEntity unlinked = doneJob(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 1, 6));
        unlinked.setNotes("Anthropic batch: msgbatch_cycle");
        JobRunEntity handStarted = doneJob(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 2, 0));
        handStarted.setNotes("Anthropic batch: msgbatch_hand");
        batch("msgbatch_cycle", BatchType.FORECAST, nightly.getId(), null, BatchStatus.COMPLETED);
        batch("msgbatch_hand", BatchType.FORECAST, null, handStarted.getId(), BatchStatus.COMPLETED);
        em.flush();
        pipelineRuns.recordDispositionJobRun(nightly.getId(), anchor.getId());
        em.clear();

        RunListResponse response = list(RunRange.TODAY, null, false);

        assertThat(ids(response, RunKind.JOB)).containsExactly(handStarted.getId());
        assertThat(ids(response, RunKind.CYCLE)).containsExactly(nightly.getId());
    }

    @Test
    @DisplayName("an aurora batch lands in AURORA, a forecast batch and a batch with no row in BATCHES")
    void batchTypeGrouping() {
        JobRunEntity aurora = doneJob(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 5, 0));
        JobRunEntity forecast = doneJob(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 6, 0));
        JobRunEntity noRow = doneJob(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 7, 0));
        doneJob(RunType.AURORA_EVALUATION, LocalDateTime.of(2026, 10, 31, 8, 0));
        batch("msgbatch_a", BatchType.AURORA, null, aurora.getId(), BatchStatus.COMPLETED);
        batch("msgbatch_f", BatchType.FORECAST, null, forecast.getId(), BatchStatus.COMPLETED);
        commit();

        RunListResponse all = list(RunRange.TODAY, null, false);
        RunListResponse auroraOnly = list(RunRange.TODAY, RunGroup.AURORA, false);
        RunListResponse batchesOnly = list(RunRange.TODAY, RunGroup.BATCHES, false);

        assertThat(all.kindCounts()).containsEntry("ALL", 4).containsEntry("AURORA", 2)
                .containsEntry("BATCHES", 2).containsEntry("CYCLES", 0).containsEntry("TIDE", 0);
        assertThat(ids(auroraOnly, RunKind.JOB)).contains(aurora.getId()).doesNotContain(forecast.getId());
        assertThat(auroraOnly.items()).filteredOn(i -> i.id().equals(aurora.getId()))
                .extracting(RunListItem::name).containsExactly("Aurora batch");
        assertThat(ids(batchesOnly, RunKind.JOB)).containsExactlyInAnyOrder(forecast.getId(), noRow.getId());
        assertThat(batchesOnly.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("stale open runs are FAILED 'Did not finish' and never counted running; Ask has no duration")
    void staleAndAsk() {
        JobRunEntity staleWeather = job(RunType.WEATHER, NOON_UTC.minusHours(7), false, 0, 0, null);
        JobRunEntity freshWeather = job(RunType.WEATHER, NOON_UTC.minusHours(5), false, 0, 0, null);
        JobRunEntity staleBatchStillWithinCeiling = job(RunType.SCHEDULED_BATCH, NOON_UTC.minusHours(25), false, 0,
                0, null);
        JobRunEntity ask = doneJob(RunType.ASK, NOON_UTC.minusHours(2));
        commit();

        RunListResponse response = list(RunRange.WEEK, null, false);

        RunListItem stale = byId(response, staleWeather.getId());
        assertThat(stale.status()).isEqualTo(RunListStatus.FAILED);
        assertThat(stale.note()).isEqualTo("Did not finish");
        assertThat(stale.durationSeconds()).isNull();
        assertThat(byId(response, freshWeather.getId()).status()).isEqualTo(RunListStatus.RUNNING);
        assertThat(byId(response, staleBatchStillWithinCeiling.getId()).status()).isEqualTo(RunListStatus.RUNNING);
        assertThat(byId(response, ask.getId()).durationSeconds()).isNull();
        assertThat(response.runningCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("runningCount and liveCycleId ignore every filter; an open run from before the range counts")
    void runningIgnoresFilters() {
        PipelineRunEntity live = cycle(CycleType.INTRADAY, Instant.parse("2026-10-30T13:00:00Z"),
                PipelineRunStatus.RUNNING);
        PipelineRunEntity newerLive = cycle(CycleType.NIGHTLY, Instant.parse("2026-10-31T01:00:00Z"),
                PipelineRunStatus.RUNNING);
        clock.set(Instant.parse("2026-10-31T00:30:00Z"));
        job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 0, 10), false, 0, 0, null);
        job(RunType.TIDE, LocalDateTime.of(2026, 10, 30, 22, 0), false, 0, 0, null);
        doneJob(RunType.BRIEFING, LocalDateTime.of(2026, 10, 31, 0, 20));
        commit();

        RunListResponse filtered = list(RunRange.TODAY, RunGroup.BRIEFING, true);

        assertThat(filtered.items()).isEmpty();
        assertThat(filtered.runningCount()).isEqualTo(4);
        assertThat(filtered.liveCycleId()).isEqualTo(newerLive.getId());
        assertThat(live.getId()).isNotEqualTo(newerLive.getId());
    }

    @Test
    @DisplayName("spend counts cycle batches once: the disposition anchor is batch 1's run, never counted twice")
    void spendCountsEachRunOnce() {
        PipelineRunEntity nightly = cycle(CycleType.NIGHTLY, Instant.parse("2026-10-31T01:00:00Z"),
                PipelineRunStatus.COMPLETED);
        JobRunEntity first = job(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 1, 5), true, 5, 0, 100L);
        JobRunEntity second = job(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 1, 6), true, 5, 0, 200L);
        job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 3, 0), true, 5, 0, 50L);
        batch("msgbatch_1", BatchType.FORECAST, nightly.getId(), first.getId(), BatchStatus.COMPLETED);
        batch("msgbatch_2", BatchType.FORECAST, nightly.getId(), second.getId(), BatchStatus.FAILED);
        batch("msgbatch_3", BatchType.FORECAST, nightly.getId(), null, BatchStatus.SUBMITTED);
        em.flush();
        pipelineRuns.recordDispositionJobRun(nightly.getId(), first.getId());
        em.clear();

        RunListResponse response = list(RunRange.TODAY, null, false);

        RunListItem cycleRow = response.items().stream().filter(i -> i.kind() == RunKind.CYCLE).findFirst()
                .orElseThrow();
        assertThat(cycleRow.costMicroDollars()).isEqualTo(300L);
        assertThat(cycleRow.costGbpRate()).isEqualTo(0.8);
        assertThat(cycleRow.batchesTotal()).isEqualTo(3);
        assertThat(cycleRow.batchCount()).isEqualTo(3);
        assertThat(cycleRow.batchesDone()).isEqualTo(2);
        assertThat(response.summary().spendMicroDollars()).isEqualTo(350L);
        assertThat(response.summary().spendGbpRate()).isEqualTo(0.8);
        assertThat(response.summary().runs()).isEqualTo(2);
        assertThat(response.summary().cycles()).isEqualTo(1);
        assertThat(response.summary().other()).isEqualTo(1);
    }

    @Test
    @DisplayName("summary and counts follow range and kind but not attention; total follows every filter")
    void summaryScoping() {
        cycle(CycleType.NIGHTLY, Instant.parse("2026-10-31T01:00:00Z"), PipelineRunStatus.DEGRADED);
        job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 3, 0), true, 8, 2, null);
        job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 4, 0), true, 1, 9, null);
        doneJob(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 5, 0));
        doneJob(RunType.BRIEFING, LocalDateTime.of(2026, 10, 31, 6, 0));
        commit();

        RunListResponse tideAttention = list(RunRange.TODAY, RunGroup.TIDE, true);

        assertThat(tideAttention.total()).isEqualTo(2);
        assertThat(tideAttention.items()).hasSize(2);
        assertThat(tideAttention.summary().runs()).isEqualTo(3);
        assertThat(tideAttention.summary().partial()).isEqualTo(1);
        assertThat(tideAttention.summary().failed()).isEqualTo(1);
        assertThat(tideAttention.summary().degraded()).isZero();
        assertThat(tideAttention.summary().needAttention()).isEqualTo(2);
        assertThat(tideAttention.attentionCount()).isEqualTo(3);
        assertThat(tideAttention.kindCounts()).containsEntry("ALL", 5).containsEntry("TIDE", 3)
                .containsEntry("BRIEFING", 1).containsEntry("CYCLES", 1);
    }

    @Test
    @DisplayName("the median covers finished overnight cycles only, null with no sample")
    void medianOfOvernightCycles() {
        assertThat(list(RunRange.WEEK, null, false).summary().medianOvernightSeconds()).isNull();
        assertThat(list(RunRange.WEEK, null, false).summary().medianOvernightSample()).isZero();

        cycle(CycleType.NIGHTLY, Instant.parse("2026-10-31T01:00:00Z"), PipelineRunStatus.COMPLETED);
        cycle(CycleType.NIGHTLY, Instant.parse("2026-10-30T01:00:00Z"), PipelineRunStatus.FAILED);
        cycle(CycleType.INTRADAY, Instant.parse("2026-10-30T13:00:00Z"), PipelineRunStatus.COMPLETED);
        cycle(CycleType.NIGHTLY, Instant.parse("2026-10-29T01:00:00Z"), PipelineRunStatus.RUNNING);
        commit();
        RunListResponse one = list(RunRange.WEEK, null, false);
        assertThat(one.summary().medianOvernightSeconds()).isEqualTo(600L);
        assertThat(one.summary().medianOvernightSample()).isEqualTo(1);

        PipelineRunEntity longer = new PipelineRunEntity(CycleType.NIGHTLY, Instant.parse("2026-10-28T01:00:00Z"));
        longer.setStatus(PipelineRunStatus.DEGRADED);
        longer.setCompletedAt(Instant.parse("2026-10-28T01:20:00Z"));
        em.persist(longer);
        commit();
        RunListResponse two = list(RunRange.WEEK, null, false);
        assertThat(two.summary().medianOvernightSeconds()).isEqualTo(900L);
        assertThat(two.summary().medianOvernightSample()).isEqualTo(2);
    }

    @Test
    @DisplayName("median: odd sample, even sample, none")
    void medianHelper() {
        assertThat(AdminRunsService.median(List.of())).isNull();
        assertThat(AdminRunsService.median(List.of(5L))).isEqualTo(5L);
        assertThat(AdminRunsService.median(List.of(1L, 5L, 9L))).isEqualTo(5L);
        assertThat(AdminRunsService.median(List.of(1L, 5L, 7L, 9L))).isEqualTo(6L);
    }

    @Test
    @DisplayName("TODAY, WEEK and MONTH start at UK midnight, across the BST to GMT change")
    void boundsAtClockChange() {
        // 26 Oct 2026: GMT. 25 Oct 01:00 UTC was the change; before it the UK was on BST (UTC+1).
        clock.set(Instant.parse("2026-10-26T10:00:00Z"));
        JobRunEntity todayStart = doneJob(RunType.TIDE, LocalDateTime.of(2026, 10, 26, 0, 0));
        JobRunEntity beforeToday = doneJob(RunType.TIDE, LocalDateTime.of(2026, 10, 25, 23, 59));
        // WEEK starts at UK midnight on 20 Oct, which is 19 Oct 23:00 UTC (still BST).
        JobRunEntity weekStart = doneJob(RunType.TIDE, LocalDateTime.of(2026, 10, 19, 23, 0));
        JobRunEntity beforeWeek = doneJob(RunType.TIDE, LocalDateTime.of(2026, 10, 19, 22, 59));
        // MONTH starts at UK midnight on 27 Sep (BST), which is 26 Sep 23:00 UTC.
        JobRunEntity monthStart = doneJob(RunType.TIDE, LocalDateTime.of(2026, 9, 26, 23, 0));
        JobRunEntity beforeMonth = doneJob(RunType.TIDE, LocalDateTime.of(2026, 9, 26, 22, 59));
        commit();

        assertThat(ids(list(RunRange.TODAY, null, false), RunKind.JOB)).containsExactly(todayStart.getId());
        assertThat(ids(list(RunRange.WEEK, null, false), RunKind.JOB)).contains(weekStart.getId(),
                beforeToday.getId(), todayStart.getId()).doesNotContain(beforeWeek.getId());
        assertThat(ids(list(RunRange.MONTH, null, false), RunKind.JOB)).contains(monthStart.getId(),
                beforeWeek.getId()).doesNotContain(beforeMonth.getId());
    }

    @Test
    @DisplayName("a cycle triggered before the UK day starts is outside TODAY")
    void cycleBounds() {
        clock.set(Instant.parse("2026-10-26T10:00:00Z"));
        PipelineRunEntity inside = cycle(CycleType.NIGHTLY, Instant.parse("2026-10-26T00:00:00Z"),
                PipelineRunStatus.COMPLETED);
        cycle(CycleType.NIGHTLY, Instant.parse("2026-10-25T23:59:59Z"), PipelineRunStatus.COMPLETED);
        commit();

        assertThat(ids(list(RunRange.TODAY, null, false), RunKind.CYCLE)).containsExactly(inside.getId());
    }

    @Test
    @DisplayName("cursor paging: a row inserted at the head between pages neither shifts nor duplicates")
    void cursorPagingStableAgainstHeadInserts() {
        List<JobRunEntity> runs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            runs.add(doneJob(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 1 + i, 0)));
        }
        commit();

        RunListResponse first = service.list(RunRange.TODAY, null, false, null, 2);
        assertThat(first.items()).extracting(RunListItem::id)
                .containsExactly(runs.get(4).getId(), runs.get(3).getId());
        assertThat(first.nextCursor()).isNotNull();

        doneJob(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 11, 0));
        commit();

        RunListResponse second = service.list(RunRange.TODAY, null, false, RunCursor.decode(first.nextCursor()), 2);
        assertThat(second.items()).extracting(RunListItem::id)
                .containsExactly(runs.get(2).getId(), runs.get(1).getId());
        RunListResponse third = service.list(RunRange.TODAY, null, false, RunCursor.decode(second.nextCursor()), 2);
        assertThat(third.items()).extracting(RunListItem::id).containsExactly(runs.get(0).getId());
        assertThat(third.nextCursor()).isNull();

        assertThat(second.total()).isEqualTo(6);
    }

    @Test
    @DisplayName("the summary and counts do not depend on the cursor")
    void summaryIndependentOfCursor() {
        for (int i = 0; i < 4; i++) {
            job(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 1 + i, 0), true, 5, 0, 10L);
        }
        commit();

        RunListResponse first = service.list(RunRange.TODAY, null, false, null, 2);
        RunListResponse second = service.list(RunRange.TODAY, null, false, RunCursor.decode(first.nextCursor()), 2);

        assertThat(second.items()).hasSize(2);
        assertThat(second.summary()).isEqualTo(first.summary());
        assertThat(second.kindCounts()).isEqualTo(first.kindCounts());
        assertThat(second.total()).isEqualTo(first.total());
        assertThat(second.attentionCount()).isEqualTo(first.attentionCount());
        assertThat(first.summary().spendMicroDollars()).isEqualTo(40L);
    }

    @Test
    @DisplayName("a cycle and a job run with the same start order job first, then by id descending")
    void tieOrdering() {
        Instant same = Instant.parse("2026-10-31T03:00:00Z");
        PipelineRunEntity cycle = cycle(CycleType.INTRADAY, same, PipelineRunStatus.COMPLETED);
        JobRunEntity job = doneJob(RunType.TIDE, LocalDateTime.ofInstant(same, ZoneOffset.UTC));
        commit();

        RunListResponse response = list(RunRange.TODAY, null, false);

        assertThat(response.items()).extracting(RunListItem::kind).containsExactly(RunKind.JOB, RunKind.CYCLE);
        assertThat(response.items()).extracting(RunListItem::id).containsExactly(job.getId(), cycle.getId());
    }

    @Test
    @DisplayName("more job runs than the scan cap sets truncated")
    void truncatedPastScanCap() {
        List<JobRunEntity> bulk = new ArrayList<>();
        for (int i = 0; i < AdminRunsService.SCAN_CAP + 1; i++) {
            bulk.add(JobRunEntity.builder().runType(RunType.WEATHER)
                    .startedAt(LocalDateTime.of(2026, 10, 31, 0, 0).plusSeconds(i)).completedAt(NOON_UTC)
                    .succeeded(1).failed(0).locationsProcessed(1).triggeredManually(false).build());
        }
        bulk.forEach(em::persist);
        commit();

        RunListResponse response = service.list(RunRange.TODAY, null, false, null, 1);

        assertThat(response.truncated()).isTrue();
        assertThat(response.total()).isEqualTo(AdminRunsService.SCAN_CAP);
    }

    @Test
    @DisplayName("scheduled batches on the page carry their scope; other rows do not")
    void batchSummaryAttached() {
        JobRunEntity batchRun = doneJob(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 2, 0));
        JobRunEntity tide = doneJob(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 3, 0));
        commit();
        BatchSummary scope = new BatchSummary("T+0 to T+1", List.of("SUNRISE"), "SONNET", 12, 3, false);
        JobRunEntity expected = em.find(JobRunEntity.class, batchRun.getId());
        when(deriver.derive(expected)).thenReturn(Optional.of(scope));

        RunListResponse response = list(RunRange.TODAY, null, false);

        assertThat(byId(response, batchRun.getId()).batchSummary()).isEqualTo(scope);
        assertThat(byId(response, tide.getId()).batchSummary()).isNull();
    }

    @Test
    @DisplayName("nextOvernightAt is the scheduler's next nightly fire, and null when it has none or throws")
    void nextOvernight() {
        SchedulerJobConfigEntity nightly = SchedulerJobConfigEntity.builder()
                .jobKey(ForecastCycleJobs.NIGHTLY_JOB).build();
        SchedulerJobConfigEntity other = SchedulerJobConfigEntity.builder().jobKey("aurora_poll").build();
        Instant fire = Instant.parse("2026-11-01T02:30:00Z");

        when(scheduler.getAll()).thenReturn(List.of(other, nightly));
        when(scheduler.calculateNextFireTime(nightly)).thenReturn(fire);
        assertThat(list(RunRange.TODAY, null, false).nextOvernightAt()).isEqualTo(fire);

        when(scheduler.getAll()).thenReturn(List.of(other));
        assertThat(list(RunRange.TODAY, null, false).nextOvernightAt()).isNull();

        when(scheduler.getAll()).thenThrow(new IllegalStateException("scheduler down"));
        assertThat(list(RunRange.TODAY, null, false).nextOvernightAt()).isNull();
    }

    @Test
    @DisplayName("job detail: the run with served status, group, name and its batch; a cycle's batch works too")
    void jobDetail() {
        PipelineRunEntity nightly = cycle(CycleType.NIGHTLY, Instant.parse("2026-10-31T01:00:00Z"),
                PipelineRunStatus.COMPLETED);
        JobRunEntity run = job(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 31, 1, 5), true, 8, 2, 100L);
        run.setNotes("Anthropic batch: msgbatch_d");
        batch("msgbatch_d", BatchType.AURORA, nightly.getId(), run.getId(), BatchStatus.COMPLETED);
        commit();

        var detail = service.jobDetail(run.getId()).orElseThrow();

        assertThat(detail.run().id()).isEqualTo(run.getId());
        assertThat(detail.run().notes()).isEqualTo("Anthropic batch: msgbatch_d");
        assertThat(detail.status()).isEqualTo(RunListStatus.PARTIAL);
        assertThat(detail.note()).isEqualTo("2 requests errored");
        assertThat(detail.group()).isEqualTo(RunGroup.AURORA);
        assertThat(detail.name()).isEqualTo("Aurora batch");
        assertThat(detail.triggeredManually()).isFalse();
        assertThat(detail.batch().anthropicBatchId()).isEqualTo("msgbatch_d");
        assertThat(detail.batch().pipelineRunId()).isEqualTo(nightly.getId());
        assertThat(detail.batch().status()).isEqualTo(BatchStatus.COMPLETED);

        JobRunEntity plain = doneJob(RunType.TIDE, LocalDateTime.of(2026, 10, 31, 2, 0));
        commit();
        assertThat(service.jobDetail(plain.getId()).orElseThrow().batch()).isNull();
        assertThat(service.jobDetail(-1L)).isEmpty();
    }

    private static RunListItem byId(RunListResponse response, Long id) {
        return response.items().stream().filter(i -> i.kind() == RunKind.JOB && i.id().equals(id)).findFirst()
                .orElseThrow();
    }
}
